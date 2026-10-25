// File: core/cmd/libygggram/main.go
//
// JNI bridge for the real Yggdrasil Go core, loaded by YggNativeCoreBridge.kt as
// libygggram.so. Each exported Java_com_example_engine_YggNativeCoreBridge_*
// symbol maps 1:1 to the Kotlin external functions; every call returns a JSON
// string {"ok":bool,...} and never panics across the boundary.
//
// The embedded core runs as a genuine Yggdrasil node on the 0200::/7 overlay:
// it derives the node identity from the profile mnemonic, joins the configured
// peering URIs via AddPeer and exposes peer link state through GetPeers.
// Application payloads travel through the real overlay via Core.WriteTo /
// Core.ReadFrom (ironwood session traffic addressed by the peer's Ed25519 node
// public key), one independent node per active profile.
//
// Build (Android arm64-v8a, NDK r28, API 24+):
//
//	GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
//	CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android24-clang" \
//	go build -buildmode=c-shared -o ../app/src/main/jniLibs/arm64-v8a/libygggram.so ./cmd/libygggram
package main

/*
#cgo android CFLAGS: -DHAVE_JNI
#include <jni.h>
#include <stdlib.h>
#include <string.h>

// Copies a Java string into a malloc'd C buffer (release the JNI copy immediately).
static char* jni_to_cstring(JNIEnv *env, jstring str) {
    if (str == NULL) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, str, NULL);
    if (utf == NULL) return NULL;
    char *copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, str, utf);
    return copy;
}

// Wraps a malloc'd C buffer into a Java string and frees the buffer.
static jstring jni_from_cstring(JNIEnv *env, char *cstr) {
    jstring result;
    if (cstr == NULL) {
        result = (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"native allocation failed\"}");
    } else {
        result = (*env)->NewStringUTF(env, cstr);
        free(cstr);
    }
    return result;
}
*/
import "C"
import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/url"
	"sort"
	"strconv"
	"sync"
	"time"
	"unsafe"

	iwt "github.com/Arceliar/ironwood/types"
	"github.com/yggdrasil-network/yggdrasil-go/src/config"
	"github.com/yggdrasil-network/yggdrasil-go/src/core"
	yggcrypto "github.com/ygggram/core/pkg/crypto"
)

const overlayRxQueueLen = 256

type OverlayRxPacket struct {
	Payload         []byte
	SenderPubKeyHex string
}

type EngineInstance struct {
	mu        sync.RWMutex
	yggCore   *core.Core
	keys      *yggcrypto.KeyHierarchy
	dataDir   string
	started   time.Time
	overlayRx chan OverlayRxPacket
	done      chan struct{}
	listeners []*core.Listener
}

var (
	enginesMu sync.RWMutex
	engines   = map[string]*EngineInstance{}
)

type discardLogger struct{}

func (discardLogger) Printf(string, ...interface{}) {}
func (discardLogger) Println(...interface{})        {}
func (discardLogger) Infof(string, ...interface{})  {}
func (discardLogger) Infoln(...interface{})         {}
func (discardLogger) Warnf(string, ...interface{})  {}
func (discardLogger) Warnln(...interface{})         {}
func (discardLogger) Errorf(string, ...interface{}) {}
func (discardLogger) Errorln(...interface{})        {}
func (discardLogger) Debugf(string, ...interface{}) {}
func (discardLogger) Debugln(...interface{})        {}
func (discardLogger) Traceln(...interface{})        {}

// Ring buffer of core/link log lines surfaced through nativeGetLinkLog so the
// Kotlin side can explain why peer dials fail (DNS, TLS, timeouts) instead of
// silently reporting "no live peers".
const linkLogCap = 240

var (
	linkLogMu   sync.Mutex
	linkLogRing = make([]string, 0, linkLogCap)
)

func appendLinkLog(format string, args ...interface{}) {
	line := time.Now().Format("15:04:05.000") + " " + fmt.Sprintf(format, args...)
	linkLogMu.Lock()
	if len(linkLogRing) >= linkLogCap {
		linkLogRing = linkLogRing[1:]
	}
	linkLogRing = append(linkLogRing, line)
	linkLogMu.Unlock()
}

type captureLogger struct{}

func (captureLogger) Printf(format string, args ...interface{}) { appendLinkLog(format, args...) }
func (captureLogger) Println(args ...interface{})               { appendLinkLog("%v", args...) }
func (captureLogger) Infof(format string, args ...interface{}) {
	appendLinkLog("INFO "+format, args...)
}
func (captureLogger) Infoln(args ...interface{}) { appendLinkLog("INFO %v", args...) }
func (captureLogger) Warnf(format string, args ...interface{}) {
	appendLinkLog("WARN "+format, args...)
}
func (captureLogger) Warnln(args ...interface{}) { appendLinkLog("WARN %v", args...) }
func (captureLogger) Errorf(format string, args ...interface{}) {
	appendLinkLog("ERR "+format, args...)
}
func (captureLogger) Errorln(args ...interface{}) { appendLinkLog("ERR %v", args...) }
func (captureLogger) Debugf(format string, args ...interface{}) {
	appendLinkLog("DBG "+format, args...)
}
func (captureLogger) Debugln(args ...interface{}) { appendLinkLog("DBG %v", args...) }
func (captureLogger) Traceln(args ...interface{}) { appendLinkLog("TRC %v", args...) }

func YggGramGetLinkLogImpl() string {
	linkLogMu.Lock()
	defer linkLogMu.Unlock()
	lines := make([]string, len(linkLogRing))
	copy(lines, linkLogRing)
	raw, err := json.Marshal(map[string]any{"ok": true, "lines": lines})
	if err != nil {
		return `{"ok":false,"error":"log marshal failed"}`
	}
	return string(raw)
}

func engineJSON(ok bool, extra map[string]any) string {
	resp := map[string]any{"ok": ok}
	for k, v := range extra {
		resp[k] = v
	}
	raw, _ := json.Marshal(resp)
	return string(raw)
}

// A panic that escapes a cgo export aborts the whole Android process, so every JNI entry
// point runs through this and turns a panic into a normal {"ok":false} envelope instead.
func guardedCall(fn func() string) (out string) {
	defer func() {
		if r := recover(); r != nil {
			out = engineJSON(false, map[string]any{"error": fmt.Sprintf("native panic: %v", r)})
		}
	}()
	return fn()
}

// Public peer links flap: the core re-dials a dropped link with an exponential
// backoff that grows up to over an hour, which leaves the node without any
// route for long stretches. The watchdog interrupts that backoff so a dropped
// public peer is re-dialled within seconds.
const peerWatchdogInterval = 12 * time.Second

func watchPeerLinks(engine *EngineInstance) {
	ticker := time.NewTicker(peerWatchdogInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			engine.mu.RLock()
			node := engine.yggCore
			engine.mu.RUnlock()
			if node == nil {
				return
			}
			node.RetryPeersNow()
		case <-engine.done:
			return
		}
	}
}

// withCappedBackoff keeps the reconnect backoff short: the core default grows
// beyond an hour, so one flapping public peer can silence the whole node.
func withCappedBackoff(peerUri string) string {
	u, err := url.Parse(peerUri)
	if err != nil {
		return peerUri
	}
	q := u.Query()
	if q.Get("maxbackoff") == "" {
		q.Set("maxbackoff", "20s")
		u.RawQuery = q.Encode()
	}
	return u.String()
}

func YggGramInitCoreImpl(profileID, mnemonic, androidFilesDir string) string {
	if profileID == "" {
		return `{"ok":false,"error":"profileId is required"}`
	}
	enginesMu.Lock()
	defer enginesMu.Unlock()

	if existing := engines[profileID]; existing != nil {
		existing.mu.Lock()
		node := existing.yggCore
		existing.yggCore = nil
		existing.mu.Unlock()
		if node != nil {
			node.Stop()
		}
		// Stop the old peer-link watchdog, otherwise it keeps calling
		// RetryPeersNow on the stopped core after re-init.
		close(existing.done)
		delete(engines, profileID)
	}

	dataDir, err := yggcrypto.ResolvePlatformDataDir(androidFilesDir)
	if err != nil {
		return `{"ok":false,"error":"` + err.Error() + `"}`
	}
	keys, err := yggcrypto.DeriveSLIP0010Hierarchy(mnemonic, 1)
	if err != nil {
		return `{"ok":false,"error":"` + err.Error() + `"}`
	}

	cfg := &config.NodeConfig{}
	cfg.PrivateKey = config.KeyBytes(keys.YggNodePrivateKey)
	if err := cfg.GenerateSelfSignedCertificate(); err != nil {
		return `{"ok":false,"error":"` + err.Error() + `"}`
	}

	node, err := core.New(cfg.Certificate, captureLogger{})
	if err != nil {
		return `{"ok":false,"error":"` + err.Error() + `"}`
	}

	engine := &EngineInstance{
		yggCore:   node,
		keys:      keys,
		dataDir:   dataDir,
		started:   time.Now(),
		overlayRx: make(chan OverlayRxPacket, overlayRxQueueLen),
		done:      make(chan struct{}),
	}
	engines[profileID] = engine

	// A node that only dials is a leaf: nobody can peer with it, so it can neither be
	// reached nor carry anyone else's traffic. 5985 is the port the public yggdrasil
	// nodes expect, and the TLS listener tries 443 because that is the port an
	// allow-list lets through. Every bind is best effort - an unprivileged Android
	// process is refused on low ports and a second profile finds the port taken - so
	// the outcome goes into the journal instead of failing the init.
	for _, uri := range []string{"tcp://0.0.0.0:5985", "tls://0.0.0.0:443"} {
		u, err := url.Parse(uri)
		if err != nil {
			appendLinkLog("ERR bad listen uri %s: %v", uri, err)
			continue
		}
		l, err := node.Listen(u, "")
		if err != nil {
			appendLinkLog("ERR listener %s refused: %v", uri, err)
			continue
		}
		engine.listeners = append(engine.listeners, l)
		appendLinkLog("INFO listener %s bound to %s", uri, l.Addr().String())
	}

	go watchPeerLinks(engine)

	go func() {
		buf := make([]byte, 65535)
		for {
			n, from, err := node.ReadFrom(buf)
			if err != nil {
				return
			}
			if n <= 0 {
				continue
			}
			senderHex := ""
			if addr, ok := from.(iwt.Addr); ok {
				senderHex = hex.EncodeToString(addr)
			}
			payload := append([]byte(nil), buf[:n]...)
			select {
			case engine.overlayRx <- OverlayRxPacket{Payload: payload, SenderPubKeyHex: senderHex}:
			default:
				// Queue full: drop the oldest packet to keep the loop live.
				select {
				case <-engine.overlayRx:
				default:
				}
				select {
				case engine.overlayRx <- OverlayRxPacket{Payload: payload, SenderPubKeyHex: senderHex}:
				default:
				}
			}
		}
	}()

	subnet := node.Subnet()
	resp := map[string]any{
		"ok":                true,
		"profileId":         profileID,
		"dataDir":           dataDir,
		"ipv6Address":       node.Address().String(),
		"subnet":            subnet.String(),
		"publicKeyHex":      hex.EncodeToString(keys.YggNodePublicKey),
		"masterFingerprint": keys.MasterFingerprint,
	}
	raw, _ := json.Marshal(resp)
	return string(raw)
}

func YggGramStopCoreImpl(profileID string) string {
	enginesMu.Lock()
	defer enginesMu.Unlock()
	engine := engines[profileID]
	if engine == nil {
		return `{"ok":true}`
	}
	engine.mu.RLock()
	node := engine.yggCore
	engine.mu.RUnlock()
	if node != nil {
		node.Stop()
	}
	close(engine.done)
	delete(engines, profileID)
	return `{"ok":true}`
}

func YggGramStopAllImpl() string {
	enginesMu.Lock()
	defer enginesMu.Unlock()
	for id, engine := range engines {
		engine.mu.RLock()
		node := engine.yggCore
		engine.mu.RUnlock()
		if node != nil {
			node.Stop()
		}
		close(engine.done)
		delete(engines, id)
	}
	return `{"ok":true}`
}

func lookupEngine(profileID string) *EngineInstance {
	enginesMu.RLock()
	defer enginesMu.RUnlock()
	return engines[profileID]
}

func YggGramAddPeerImpl(profileID, peerUri, sintf string) string {
	engine := lookupEngine(profileID)
	if engine == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	engine.mu.RLock()
	node := engine.yggCore
	engine.mu.RUnlock()
	if node == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	u, err := url.Parse(withCappedBackoff(peerUri))
	if err != nil {
		return `{"ok":false,"error":"invalid peer URI: ` + err.Error() + `"}`
	}
	if err := node.AddPeer(u, sintf); err != nil {
		return `{"ok":false,"error":"` + err.Error() + `"}`
	}
	return engineJSON(true, map[string]any{"uri": peerUri, "profileId": profileID})
}

func YggGramRemovePeerImpl(profileID, peerUri, sintf string) string {
	engine := lookupEngine(profileID)
	if engine == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	engine.mu.RLock()
	node := engine.yggCore
	engine.mu.RUnlock()
	if node == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	u, err := url.Parse(peerUri)
	if err != nil {
		return `{"ok":false,"error":"invalid peer URI: ` + err.Error() + `"}`
	}
	if err := node.RemovePeer(u, sintf); err != nil {
		return `{"ok":false,"error":"` + err.Error() + `"}`
	}
	return `{"ok":true}`
}

func YggGramGetPeersJSONImpl(profileID string) string {
	engine := lookupEngine(profileID)
	if engine == nil {
		return `{"ok":false,"peers":[],"upCount":0,"total":0}`
	}
	engine.mu.RLock()
	node := engine.yggCore
	engine.mu.RUnlock()
	if node == nil {
		return `{"ok":false,"peers":[],"upCount":0,"total":0}`
	}
	peers := node.GetPeers()
	upCount := 0
	for _, p := range peers {
		if p.Up {
			upCount++
		}
	}
	selfHex := hex.EncodeToString(node.PublicKey())
	knownNodes := 0
	for _, t := range node.GetTree() {
		if hex.EncodeToString(t.Key) != selfHex {
			knownNodes++
		}
	}
	sessions := make([]map[string]any, 0, 8)
	for _, s := range node.GetSessions() {
		if len(sessions) >= 32 {
			break
		}
		sessions = append(sessions, map[string]any{
			"keyHex": hex.EncodeToString(s.Key),
			"rx":     s.RXBytes,
			"tx":     s.TXBytes,
		})
	}
	raw, _ := json.Marshal(map[string]any{
		"ok":           true,
		"profileId":    profileID,
		"peers":        peers,
		"upCount":      upCount,
		"total":        len(peers),
		"knownNodes":   knownNodes,
		"sessionCount": len(sessions),
		"sessions":     sessions,
	})
	return string(raw)
}

// YggGramRetryPeersImpl interrupts the core's reconnect backoff so a node that
// lost its public peers re-dials them immediately instead of waiting out the
// exponential backoff.
func YggGramRetryPeersImpl(profileID string) string {
	engine := lookupEngine(profileID)
	if engine == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized","upCount":0,"total":0}`
	}
	engine.mu.RLock()
	node := engine.yggCore
	engine.mu.RUnlock()
	if node == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized","upCount":0,"total":0}`
	}
	node.RetryPeersNow()
	peers := node.GetPeers()
	upCount := 0
	for _, p := range peers {
		if p.Up {
			upCount++
		}
	}
	return engineJSON(true, map[string]any{
		"profileId": profileID,
		"upCount":   upCount,
		"total":     len(peers),
	})
}

func YggGramSendOverlayImpl(profileID, destPubKeyHex, payloadBase64 string) string {
	engine := lookupEngine(profileID)
	if engine == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	engine.mu.RLock()
	node := engine.yggCore
	engine.mu.RUnlock()
	if node == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	destKey, err := hex.DecodeString(destPubKeyHex)
	if err != nil || len(destKey) != 32 {
		return `{"ok":false,"error":"recipient Ed25519 public key must be 64 hex chars"}`
	}
	payload, err := base64.StdEncoding.DecodeString(payloadBase64)
	if err != nil {
		return `{"ok":false,"error":"payload is not valid base64: ` + err.Error() + `"}`
	}
	if _, err := node.WriteTo(payload, iwt.Addr(destKey)); err != nil {
		return `{"ok":false,"error":"overlay WriteTo failed: ` + err.Error() + `"}`
	}
	return engineJSON(true, map[string]any{"bytes": len(payload), "profileId": profileID})
}

// YggGramOverlayRouteImpl reports whether the node's pathfinder already knows a
// route to destPubKeyHex.
//
// WriteTo is not proof of anything: ironwood hands the frame to the pathfinder,
// which forwards it only when an entry exists in its paths table. Otherwise it
// starts a route lookup and caches the packet as a rumour — and silently drops it
// if nobody ever answers. So "hasPath" is the only honest way to tell a datagram
// that is on its way from one that started a search.
func YggGramOverlayRouteImpl(profileID, destPubKeyHex string) string {
	engine := lookupEngine(profileID)
	if engine == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	engine.mu.RLock()
	node := engine.yggCore
	engine.mu.RUnlock()
	if node == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	dest, err := hex.DecodeString(destPubKeyHex)
	if err != nil || len(dest) != 32 {
		return `{"ok":false,"error":"recipient Ed25519 public key must be 64 hex chars"}`
	}
	destHex := hex.EncodeToString(dest)
	paths := node.GetPaths()
	// Mirror the pathfinder's own forwarding condition: it sends whenever an entry for
	// dest exists. A directly peered node has an entry with an empty path, so keying on
	// the hop count would call a live route "not found".
	hasPath := false
	hops := 0
	for _, p := range paths {
		if hex.EncodeToString(p.Key) == destHex {
			hasPath = true
			hops = len(p.Path)
			break
		}
	}
	return engineJSON(true, map[string]any{
		"hasPath": hasPath,
		"hops":    hops,
		"paths":   len(paths),
	})
}

// livePeerCount returns the number of public peer links that are currently up.
func livePeerCount(node *core.Core) int {
	up := 0
	for _, p := range node.GetPeers() {
		if p.Up {
			up++
		}
	}
	return up
}

// overlayProbeTarget is a remote Yggdrasil node the overlay probe can address.
type overlayProbeTarget struct {
	key      ed25519.PublicKey
	multiHop bool // reached through the overlay instead of a direct link
	fresh    bool // no encrypted session with that node yet
}

// YggGramOverlayProbeImpl performs a real round trip through the 0200::/7 overlay.
//
// A node cannot address itself: the pathfinder keeps no route to its own key and
// the encrypted session layer refuses to build a session with itself, so a
// self-addressed datagram never leaves the node. The probe therefore addresses a
// remote node taken from the routing tree. The encrypted layer creates the session
// only after the remote peer answers with a session acknowledgement, so the
// appearance of that session proves that the datagram reached a remote node
// through the overlay and the answer came back.
func YggGramOverlayProbeImpl(profileID string, timeoutMs int) string {
	engine := lookupEngine(profileID)
	if engine == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	engine.mu.RLock()
	node := engine.yggCore
	engine.mu.RUnlock()
	if node == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	if timeoutMs <= 0 {
		timeoutMs = 20000
	}

	selfHex := hex.EncodeToString(node.PublicKey())
	direct := map[string]bool{}
	for _, p := range node.PacketConn.PacketConn.Debug.GetPeers() {
		direct[hex.EncodeToString(p.Key)] = true
	}
	sessionsBefore := map[string]core.SessionInfo{}
	for _, s := range node.GetSessions() {
		sessionsBefore[hex.EncodeToString(s.Key)] = s
	}

	targets := make([]overlayProbeTarget, 0, 16)
	seen := map[string]bool{}
	appendTarget := func(key ed25519.PublicKey) {
		h := hex.EncodeToString(key)
		if h == selfHex || seen[h] {
			return
		}
		seen[h] = true
		_, hasSession := sessionsBefore[h]
		targets = append(targets, overlayProbeTarget{key: key, multiHop: !direct[h], fresh: !hasSession})
	}
	for _, p := range node.GetPaths() {
		appendTarget(p.Key)
	}
	for _, t := range node.GetTree() {
		appendTarget(t.Key)
	}

	base := map[string]any{
		"profileId":  profileID,
		"livePeers":  livePeerCount(node),
		"knownNodes": len(targets),
	}
	if len(targets) == 0 {
		return engineJSON(false, map[string]any{
			"profileId":  profileID,
			"livePeers":  livePeerCount(node),
			"knownNodes": 0,
			"error":      "overlay routing tree is empty: no remote node known yet",
		})
	}

	// Multi-hop targets first: they prove that the datagram was routed through
	// the overlay instead of a single direct link. Fresh sessions come first as
	// well, because only a new session proves a new round trip.
	sort.SliceStable(targets, func(i, j int) bool {
		if targets[i].multiHop != targets[j].multiHop {
			return targets[i].multiHop
		}
		return targets[i].fresh && !targets[j].fresh
	})

	payload := []byte(`{"type":"PROBE","sentAt":` + strconv.FormatInt(time.Now().UnixMilli(), 10) + `}`)
	deadline := time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	for _, target := range targets {
		if time.Now().After(deadline) {
			break
		}
		if _, err := node.WriteTo(payload, iwt.Addr(target.key)); err != nil {
			continue
		}
		attemptStart := time.Now()
		attemptDeadline := attemptStart.Add(8 * time.Second)
		if attemptDeadline.After(deadline) {
			attemptDeadline = deadline
		}
		for time.Now().Before(attemptDeadline) {
			time.Sleep(200 * time.Millisecond)
			for _, s := range node.GetSessions() {
				keyHex := hex.EncodeToString(s.Key)
				if keyHex != hex.EncodeToString(target.key) {
					continue
				}
				before, hadBefore := sessionsBefore[keyHex]
				if hadBefore && s.TXBytes <= before.TXBytes {
					continue
				}
				base["rttMs"] = time.Since(attemptStart).Milliseconds()
				base["remoteKeyHex"] = keyHex
				base["remoteIsDirectPeer"] = direct[keyHex]
				base["multiHop"] = target.multiHop
				base["txBytes"] = len(payload)
				base["sessionTxBytes"] = s.TXBytes
				base["sessionRxBytes"] = s.RXBytes
				return engineJSON(true, base)
			}
		}
	}
	return engineJSON(false, map[string]any{
		"profileId":  profileID,
		"livePeers":  livePeerCount(node),
		"knownNodes": len(targets),
		"error":      "no remote node answered the overlay handshake within " + strconv.Itoa(timeoutMs) + " ms",
	})
}

func YggGramPollOverlayImpl(profileID string, timeoutMs int) string {
	engine := lookupEngine(profileID)
	if engine == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	if timeoutMs <= 0 {
		timeoutMs = 1
	}
	select {
	case pkt := <-engine.overlayRx:
		return engineJSON(true, map[string]any{
			"payloadBase64":   base64.StdEncoding.EncodeToString(pkt.Payload),
			"senderPubKeyHex": pkt.SenderPubKeyHex,
			"profileId":       profileID,
		})
	case <-time.After(time.Duration(timeoutMs) * time.Millisecond):
		return engineJSON(true, map[string]any{"timeout": true, "profileId": profileID})
	}
}

// --- JNI entry points (instance methods of com.example.engine.YggNativeCoreBridge) ---

//export Java_com_example_engine_YggNativeCoreBridge_nativeStopCore
func Java_com_example_engine_YggNativeCoreBridge_nativeStopCore(env *C.JNIEnv, thiz C.jobject, profileIdC C.jstring) C.jstring {
	profileId := C.jni_to_cstring(env, profileIdC)
	result := guardedCall(func() string { return YggGramStopCoreImpl(valueOrEmpty(profileId)) })
	C.free(unsafe.Pointer(profileId))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeStopAllCores
func Java_com_example_engine_YggNativeCoreBridge_nativeStopAllCores(env *C.JNIEnv, thiz C.jobject) C.jstring {
	return C.jni_from_cstring(env, C.CString(guardedCall(YggGramStopAllImpl)))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeInitCore
func Java_com_example_engine_YggNativeCoreBridge_nativeInitCore(env *C.JNIEnv, thiz C.jobject, profileIdC C.jstring, mnemonicC C.jstring, filesDirC C.jstring) C.jstring {
	profileId := C.jni_to_cstring(env, profileIdC)
	mnemonic := C.jni_to_cstring(env, mnemonicC)
	filesDir := C.jni_to_cstring(env, filesDirC)
	result := guardedCall(func() string {
		return YggGramInitCoreImpl(valueOrEmpty(profileId), valueOrEmpty(mnemonic), valueOrEmpty(filesDir))
	})
	C.free(unsafe.Pointer(profileId))
	C.free(unsafe.Pointer(mnemonic))
	C.free(unsafe.Pointer(filesDir))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeAddPeer
func Java_com_example_engine_YggNativeCoreBridge_nativeAddPeer(env *C.JNIEnv, thiz C.jobject, profileIdC C.jstring, peerUriC C.jstring, sintfC C.jstring) C.jstring {
	profileId := C.jni_to_cstring(env, profileIdC)
	peerUri := C.jni_to_cstring(env, peerUriC)
	sintf := C.jni_to_cstring(env, sintfC)
	result := guardedCall(func() string {
		return YggGramAddPeerImpl(valueOrEmpty(profileId), valueOrEmpty(peerUri), valueOrEmpty(sintf))
	})
	C.free(unsafe.Pointer(profileId))
	C.free(unsafe.Pointer(peerUri))
	C.free(unsafe.Pointer(sintf))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeRemovePeer
func Java_com_example_engine_YggNativeCoreBridge_nativeRemovePeer(env *C.JNIEnv, thiz C.jobject, profileIdC C.jstring, peerUriC C.jstring, sintfC C.jstring) C.jstring {
	profileId := C.jni_to_cstring(env, profileIdC)
	peerUri := C.jni_to_cstring(env, peerUriC)
	sintf := C.jni_to_cstring(env, sintfC)
	result := guardedCall(func() string {
		return YggGramRemovePeerImpl(valueOrEmpty(profileId), valueOrEmpty(peerUri), valueOrEmpty(sintf))
	})
	C.free(unsafe.Pointer(profileId))
	C.free(unsafe.Pointer(peerUri))
	C.free(unsafe.Pointer(sintf))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeGetPeersJson
func Java_com_example_engine_YggNativeCoreBridge_nativeGetPeersJson(env *C.JNIEnv, thiz C.jobject, profileIdC C.jstring) C.jstring {
	profileId := C.jni_to_cstring(env, profileIdC)
	result := guardedCall(func() string { return YggGramGetPeersJSONImpl(valueOrEmpty(profileId)) })
	C.free(unsafe.Pointer(profileId))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeSendOverlay
func Java_com_example_engine_YggNativeCoreBridge_nativeSendOverlay(env *C.JNIEnv, thiz C.jobject, profileIdC C.jstring, destKeyC C.jstring, payloadC C.jstring) C.jstring {
	profileId := C.jni_to_cstring(env, profileIdC)
	destKey := C.jni_to_cstring(env, destKeyC)
	payload := C.jni_to_cstring(env, payloadC)
	result := guardedCall(func() string {
		return YggGramSendOverlayImpl(valueOrEmpty(profileId), valueOrEmpty(destKey), valueOrEmpty(payload))
	})
	C.free(unsafe.Pointer(profileId))
	C.free(unsafe.Pointer(destKey))
	C.free(unsafe.Pointer(payload))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeOverlayRoute
func Java_com_example_engine_YggNativeCoreBridge_nativeOverlayRoute(env *C.JNIEnv, thiz C.jobject, profileIdC C.jstring, destKeyC C.jstring) C.jstring {
	profileId := C.jni_to_cstring(env, profileIdC)
	destKey := C.jni_to_cstring(env, destKeyC)
	result := guardedCall(func() string {
		return YggGramOverlayRouteImpl(valueOrEmpty(profileId), valueOrEmpty(destKey))
	})
	C.free(unsafe.Pointer(profileId))
	C.free(unsafe.Pointer(destKey))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativePollOverlay
func Java_com_example_engine_YggNativeCoreBridge_nativePollOverlay(env *C.JNIEnv, thiz C.jobject, profileIdC C.jstring, timeoutMs C.jint) C.jstring {
	profileId := C.jni_to_cstring(env, profileIdC)
	result := guardedCall(func() string { return YggGramPollOverlayImpl(valueOrEmpty(profileId), int(timeoutMs)) })
	C.free(unsafe.Pointer(profileId))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeGetLinkLog
func Java_com_example_engine_YggNativeCoreBridge_nativeGetLinkLog(env *C.JNIEnv, thiz C.jobject) C.jstring {
	result := guardedCall(func() string { return YggGramGetLinkLogImpl() })
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeRetryPeers
func Java_com_example_engine_YggNativeCoreBridge_nativeRetryPeers(env *C.JNIEnv, thiz C.jobject, profileIdC C.jstring) C.jstring {
	profileId := C.jni_to_cstring(env, profileIdC)
	result := guardedCall(func() string { return YggGramRetryPeersImpl(valueOrEmpty(profileId)) })
	C.free(unsafe.Pointer(profileId))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeProbeOverlay
func Java_com_example_engine_YggNativeCoreBridge_nativeProbeOverlay(env *C.JNIEnv, thiz C.jobject, profileIdC C.jstring, timeoutMsC C.jint) C.jstring {
	profileId := C.jni_to_cstring(env, profileIdC)
	result := guardedCall(func() string { return YggGramOverlayProbeImpl(valueOrEmpty(profileId), int(timeoutMsC)) })
	C.free(unsafe.Pointer(profileId))
	return C.jni_from_cstring(env, C.CString(result))
}

func valueOrEmpty(s *C.char) string {
	if s == nil {
		return ""
	}
	return C.GoString(s)
}

func main() {}
