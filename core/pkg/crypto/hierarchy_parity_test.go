// File: core/pkg/crypto/hierarchy_parity_test.go
package crypto_test

import (
	"encoding/hex"
	"net"
	"testing"

	yggcrypto "github.com/ygggram/core/pkg/crypto"
	"github.com/yggdrasil-network/yggdrasil-go/src/address"
)

// Parity vectors shared with app/src/test/java/com/example/engine/YggCryptoParityTest.kt.
// If either derivation changes, both test suites must fail together — the Kotlin side
// advertises these keys to contacts while the Go core actually runs the node, so any
// divergence silently breaks message delivery.
var parityVectors = []struct {
	mnemonic string
	nodePub  string
	masterFp string
	ipv6     string
}{
	{
		mnemonic: "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
		nodePub:  "3cdaefc58b86fcae39fd859029b573bb30b66ea103bb0f635f8d3653fa17bb0d",
		masterFp: "7449eb29528b6c28",
		ipv6:     "202:1928:81d3:a3c8:1a8e:3013:d37e:b254",
	},
	{
		mnemonic: "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
		nodePub:  "ae4c2aa69a48086edd529fc351163fc10739b9a473f74dcb230f7fd16a1b576a",
		masterFp: "bc9e4b426a9e191a",
		ipv6:     "200:a367:aab2:cb6f:ef22:455a:c079:5dd3",
	},
	{
		mnemonic: "witch combs handshakes peeking jugs unroofed reviving headlock rebooking headstand befogging deicing",
		nodePub:  "46068858adc43b0283485c47163e115d4ae7d95bebd85da26403e20b35c912ec",
		masterFp: "9d97b121460c4d7b",
		ipv6:     "201:e7e5:de9d:48ef:13f5:f2de:8ee3:a707",
	},
}

func TestSLIP0010ParityVectors(t *testing.T) {
	for _, tc := range parityVectors {
		keys, err := yggcrypto.DeriveSLIP0010Hierarchy(tc.mnemonic, 1)
		if err != nil {
			t.Fatalf("derive for %q: %v", tc.mnemonic, err)
		}
		if got := hex.EncodeToString(keys.YggNodePublicKey); got != tc.nodePub {
			t.Errorf("nodePub for %q: got %s want %s", tc.mnemonic, got, tc.nodePub)
		}
		if keys.MasterFingerprint != tc.masterFp {
			t.Errorf("masterFp for %q: got %s want %s", tc.mnemonic, keys.MasterFingerprint, tc.masterFp)
		}
		addr := address.AddrForKey(keys.YggNodePublicKey)
		if got := net.IP(addr[:]).String(); got != tc.ipv6 {
			t.Errorf("ipv6 for %q: got %s want %s", tc.mnemonic, got, tc.ipv6)
		}
	}
}
