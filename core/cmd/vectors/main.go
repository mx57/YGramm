// File: core/cmd/vectors/main.go — one-off generator for cross-language parity vectors
package main

import (
	"encoding/hex"
	"fmt"
	"net"

	yggcrypto "github.com/ygggram/core/pkg/crypto"
	"github.com/yggdrasil-network/yggdrasil-go/src/address"
)

func main() {
	phrases := []string{
		"abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
		"zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
		"witch combs handshakes peeking jugs unroofed reviving headlock rebooking headstand befogging deicing",
	}
	for _, p := range phrases {
		k, err := yggcrypto.DeriveSLIP0010Hierarchy(p, 1)
		if err != nil {
			panic(err)
		}
		addr := address.AddrForKey(k.YggNodePublicKey)
		fmt.Printf("%s\n  nodePub=%s\n  masterFp=%s\n  ipv6=%s\n",
			p,
			hex.EncodeToString(k.YggNodePublicKey),
			k.MasterFingerprint,
			net.IP(addr[:]).String())
	}
}
