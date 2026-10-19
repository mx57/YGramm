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
// yggdrasil-go v0.5.x intentionally exposes no per-connection dial API, so
// application payloads keep flowing over the Kotlin transport; the node
// lifecycle is what this bridge owns.
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
	"encoding/hex"
	"encoding/json"
	"net/url"
	"sync"
	"time"
	"unsafe"

	"github.com/yggdrasil-network/yggdrasil-go/src/config"
	"github.com/yggdrasil-network/yggdrasil-go/src/core"
	yggcrypto "github.com/ygggram/core/pkg/crypto"
)

type EngineInstance struct {
	mu      sync.RWMutex
	yggCore *core.Core
	keys    *yggcrypto.KeyHierarchy
	dataDir string
	started time.Time
}

var globalEngine = &EngineInstance{}

type discardLogger struct{}

func (discardLogger) Printf(string, ...interface{})  {}
func (discardLogger) Println(...interface{})         {}
func (discardLogger) Infof(string, ...interface{})   {}
func (discardLogger) Infoln(...interface{})          {}
func (discardLogger) Warnf(string, ...interface{})   {}
func (discardLogger) Warnln(...interface{})          {}
func (discardLogger) Errorf(string, ...interface{})  {}
func (discardLogger) Errorln(...interface{})         {}
func (discardLogger) Debugf(string, ...interface{})  {}
func (discardLogger) Debugln(...interface{})         {}
func (discardLogger) Traceln(...interface{})         {}

func YggGramInitCoreImpl(mnemonic, androidFilesDir string) string {
	globalEngine.mu.Lock()
	defer globalEngine.mu.Unlock()

	if globalEngine.yggCore != nil {
		globalEngine.yggCore.Stop()
		globalEngine.yggCore = nil
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

	node, err := core.New(cfg.Certificate, discardLogger{})
	if err != nil {
		return `{"ok":false,"error":"` + err.Error() + `"}`
	}

	globalEngine.yggCore = node
	globalEngine.keys = keys
	globalEngine.dataDir = dataDir
	globalEngine.started = time.Now()

	subnet := node.Subnet()
	resp := map[string]any{
		"ok":                true,
		"dataDir":           dataDir,
		"ipv6Address":       node.Address().String(),
		"subnet":            subnet.String(),
		"publicKeyHex":      hex.EncodeToString(keys.YggNodePublicKey),
		"masterFingerprint": keys.MasterFingerprint,
	}
	raw, _ := json.Marshal(resp)
	return string(raw)
}

func YggGramStopCoreImpl() string {
	globalEngine.mu.Lock()
	defer globalEngine.mu.Unlock()
	if globalEngine.yggCore == nil {
		return `{"ok":true}`
	}
	globalEngine.yggCore.Stop()
	globalEngine.yggCore = nil
	return `{"ok":true}`
}

func YggGramAddPeerImpl(peerUri, sintf string) string {
	globalEngine.mu.RLock()
	node := globalEngine.yggCore
	globalEngine.mu.RUnlock()

	if node == nil {
		return `{"ok":false,"error":"yggdrasil core is not initialized"}`
	}
	u, err := url.Parse(peerUri)
	if err != nil {
		return `{"ok":false,"error":"invalid peer URI: ` + err.Error() + `"}`
	}
	if err := node.AddPeer(u, sintf); err != nil {
		return `{"ok":false,"error":"` + err.Error() + `"}`
	}
	return `{"ok":true,"uri":"` + peerUri + `"}`
}

func YggGramRemovePeerImpl(peerUri, sintf string) string {
	globalEngine.mu.RLock()
	node := globalEngine.yggCore
	globalEngine.mu.RUnlock()

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

func YggGramGetPeersJSONImpl() string {
	globalEngine.mu.RLock()
	node := globalEngine.yggCore
	globalEngine.mu.RUnlock()

	if node == nil {
		return `{"ok":false,"peers":[]}`
	}
	peers := node.GetPeers()
	raw, _ := json.Marshal(map[string]any{
		"ok":    true,
		"peers": peers,
	})
	return string(raw)
}

// --- JNI entry points (instance methods of com.example.engine.YggNativeCoreBridge) ---

//export Java_com_example_engine_YggNativeCoreBridge_nativeStopCore
func Java_com_example_engine_YggNativeCoreBridge_nativeStopCore(env *C.JNIEnv, thiz C.jobject) C.jstring {
	return C.jni_from_cstring(env, C.CString(YggGramStopCoreImpl()))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeInitCore
func Java_com_example_engine_YggNativeCoreBridge_nativeInitCore(env *C.JNIEnv, thiz C.jobject, mnemonicC C.jstring, filesDirC C.jstring) C.jstring {
	mnemonic := C.jni_to_cstring(env, mnemonicC)
	filesDir := C.jni_to_cstring(env, filesDirC)
	result := YggGramInitCoreImpl(valueOrEmpty(mnemonic), valueOrEmpty(filesDir))
	C.free(unsafe.Pointer(mnemonic))
	C.free(unsafe.Pointer(filesDir))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeAddPeer
func Java_com_example_engine_YggNativeCoreBridge_nativeAddPeer(env *C.JNIEnv, thiz C.jobject, peerUriC C.jstring, sintfC C.jstring) C.jstring {
	peerUri := C.jni_to_cstring(env, peerUriC)
	sintf := C.jni_to_cstring(env, sintfC)
	result := YggGramAddPeerImpl(valueOrEmpty(peerUri), valueOrEmpty(sintf))
	C.free(unsafe.Pointer(peerUri))
	C.free(unsafe.Pointer(sintf))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeRemovePeer
func Java_com_example_engine_YggNativeCoreBridge_nativeRemovePeer(env *C.JNIEnv, thiz C.jobject, peerUriC C.jstring, sintfC C.jstring) C.jstring {
	peerUri := C.jni_to_cstring(env, peerUriC)
	sintf := C.jni_to_cstring(env, sintfC)
	result := YggGramRemovePeerImpl(valueOrEmpty(peerUri), valueOrEmpty(sintf))
	C.free(unsafe.Pointer(peerUri))
	C.free(unsafe.Pointer(sintf))
	return C.jni_from_cstring(env, C.CString(result))
}

//export Java_com_example_engine_YggNativeCoreBridge_nativeGetPeersJson
func Java_com_example_engine_YggNativeCoreBridge_nativeGetPeersJson(env *C.JNIEnv, thiz C.jobject) C.jstring {
	result := YggGramGetPeersJSONImpl()
	return C.jni_from_cstring(env, C.CString(result))
}

func valueOrEmpty(s *C.char) string {
	if s == nil {
		return ""
	}
	return C.GoString(s)
}

func main() {}
