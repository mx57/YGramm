// File: core/cmd/libygggram/main.go
package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"context"
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"net/url"
	"sync"
	"time"
	"unsafe"

	"github.com/yggdrasil-network/yggdrasil-go/src/core"
	yggcrypto "github.com/ygggram/core/pkg/crypto"
	"github.com/ygggram/core/pkg/transport"
)

type EngineInstance struct {
	mu      sync.RWMutex
	yggCore *core.Core
	keys    *yggcrypto.KeyHierarchy
	dialer  *transport.AntiDPIDialer
	dataDir string
	started time.Time
}

var globalEngine = &EngineInstance{}

//export YggGramInitCore
func YggGramInitCore(mnemonicC *C.char, androidFilesDirC *C.char, whitelistSniC *C.char) *C.char {
	mnemonic := C.GoString(mnemonicC)
	androidFilesDir := C.GoString(androidFilesDirC)
	whitelistSNI := C.GoString(whitelistSniC)

	globalEngine.mu.Lock()
	defer globalEngine.mu.Unlock()

	if globalEngine.yggCore != nil {
		globalEngine.yggCore.Stop()
		globalEngine.yggCore = nil
	}

	dataDir, err := yggcrypto.ResolvePlatformDataDir(androidFilesDir)
	if err != nil {
		return C.CString(`{"ok":false,"error":"` + err.Error() + `"}`)
	}
	keys, err := yggcrypto.DeriveSLIP0010Hierarchy(mnemonic, 1)
	if err != nil {
		return C.CString(`{"ok":false,"error":"` + err.Error() + `"}`)
	}

	node, err := core.New(keys.YggNodePrivateKey, nil)
	if err != nil {
		return C.CString(`{"ok":false,"error":"` + err.Error() + `"}`)
	}

	globalEngine.yggCore = node
	globalEngine.keys = keys
	globalEngine.dataDir = dataDir
	globalEngine.started = time.Now()
	globalEngine.dialer = transport.NewAntiDPIDialer(transport.AntiDPIConfig{
		Mode:         transport.ModeWhitelistCDNWSS,
		WhitelistSNI: whitelistSNI,
		UTLSProfile:  "HelloChrome_128_Auto",
		QUICPadToMTU: 1360,
	})

	resp := map[string]any{
		"ok":                true,
		"dataDir":           dataDir,
		"ipv6Address":       node.Address().String(),
		"subnet":            node.Subnet().String(),
		"publicKeyHex":      hex.EncodeToString(keys.YggNodePublicKey),
		"masterFingerprint": keys.MasterFingerprint,
	}
	raw, _ := json.Marshal(resp)
	return C.CString(string(raw))
}

//export YggGramAddPeer
func YggGramAddPeer(peerUriC *C.char, sintfC *C.char) *C.char {
	peerUri := C.GoString(peerUriC)
	sintf := C.GoString(sintfC)

	globalEngine.mu.RLock()
	node := globalEngine.yggCore
	globalEngine.mu.RUnlock()

	if node == nil {
		return C.CString(`{"ok":false,"error":"yggdrasil core is not initialized"}`)
	}
	u, err := url.Parse(peerUri)
	if err != nil {
		return C.CString(`{"ok":false,"error":"invalid peer URI: ` + err.Error() + `"}`)
	}
	if err := node.AddPeer(u, sintf); err != nil {
		return C.CString(`{"ok":false,"error":"` + err.Error() + `"}`)
	}
	return C.CString(`{"ok":true,"uri":"` + peerUri + `"}`)
}

//export YggGramRemovePeer
func YggGramRemovePeer(peerUriC *C.char, sintfC *C.char) *C.char {
	peerUri := C.GoString(peerUriC)
	sintf := C.GoString(sintfC)

	globalEngine.mu.RLock()
	node := globalEngine.yggCore
	globalEngine.mu.RUnlock()

	if node == nil {
		return C.CString(`{"ok":false,"error":"yggdrasil core is not initialized"}`)
	}
	u, err := url.Parse(peerUri)
	if err != nil {
		return C.CString(`{"ok":false,"error":"invalid peer URI: ` + err.Error() + `"}`)
	}
	if err := node.RemovePeer(u, sintf); err != nil {
		return C.CString(`{"ok":false,"error":"` + err.Error() + `"}`)
	}
	return C.CString(`{"ok":true}`)
}

//export YggGramGetPeersJSON
func YggGramGetPeersJSON() *C.char {
	globalEngine.mu.RLock()
	node := globalEngine.yggCore
	globalEngine.mu.RUnlock()

	if node == nil {
		return C.CString(`{"ok":false,"peers":[]}`)
	}
	peers := node.GetPeers()
	raw, _ := json.Marshal(map[string]any{
		"ok":    true,
		"peers": peers,
	})
	return C.CString(string(raw))
}

//export YggGramSendOverlayPacket
func YggGramSendOverlayPacket(destPubKeyHexC *C.char, payloadJsonC *C.char) *C.char {
	destHex := C.GoString(destPubKeyHexC)
	payload := C.GoString(payloadJsonC)

	globalEngine.mu.RLock()
	node := globalEngine.yggCore
	globalEngine.mu.RUnlock()

	if node == nil {
		return C.CString(`{"ok":false,"error":"yggdrasil core is not initialized"}`)
	}
	pubBytes, err := hex.DecodeString(destHex)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return C.CString(`{"ok":false,"error":"invalid destination Ed25519 public key"}`)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	conn, err := node.DialContext(ctx, ed25519.PublicKey(pubBytes))
	if err != nil {
		return C.CString(`{"ok":false,"error":"` + err.Error() + `"}`)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err := conn.Write([]byte(payload + "\n")); err != nil {
		return C.CString(`{"ok":false,"error":"` + err.Error() + `"}`)
	}
	return C.CString(`{"ok":true}`)
}

//export YggGramFreeString
func YggGramFreeString(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
