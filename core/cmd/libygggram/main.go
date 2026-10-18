// File: core/cmd/libygggram/main.go
package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"encoding/hex"
	"encoding/json"
	"sync"
	"time"
	"unsafe"

	"github.com/yggdrasil-network/yggdrasil-go/src/core"
	yggcrypto "github.com/ygggram/core/pkg/crypto"
	"github.com/ygggram/core/pkg/transport"
)

type EngineInstance struct {
	mu       sync.RWMutex
	yggCore  *core.Core
	keys     *yggcrypto.KeyHierarchy
	dialer   *transport.AntiDPIDialer
	dataDir  string
	started  time.Time
}

var globalEngine = &EngineInstance{}

//export YggGramInitCore
func YggGramInitCore(mnemonicC *C.char, androidFilesDirC *C.char, whitelistSniC *C.char) *C.char {
	mnemonic := C.GoString(mnemonicC)
	androidFilesDir := C.GoString(androidFilesDirC)
	whitelistSNI := C.GoString(whitelistSniC)

	globalEngine.mu.Lock()
	defer globalEngine.mu.Unlock()

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

//export YggGramFreeString
func YggGramFreeString(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
