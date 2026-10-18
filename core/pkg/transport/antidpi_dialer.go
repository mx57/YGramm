// File: core/pkg/transport/antidpi_dialer.go
package transport

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"

	utls "github.com/refraction-networking/utls"
)

type BypassMode string

const (
	ModeWhitelistCDNWSS BypassMode = "WSS_CDN_WHITELIST"
	ModeVLESSReality    BypassMode = "VLESS_REALITY_UTLS"
	ModeShadowTLSv3     BypassMode = "SHADOWTLS_V3"
	ModeQUICPadding     BypassMode = "QUIC_UDP_PADDING"
)

type AntiDPIConfig struct {
	Mode              BypassMode
	WhitelistSNI      string
	UTLSProfile       string
	CDNWSEndpoint     string
	ShadowTLSPassword string
	QUICPadToMTU      int
	DialTimeout       time.Duration
}

type AntiDPIDialer struct {
	mu  sync.RWMutex
	cfg AntiDPIConfig
}

func NewAntiDPIDialer(cfg AntiDPIConfig) *AntiDPIDialer {
	if cfg.DialTimeout <= 0 {
		cfg.DialTimeout = 10 * time.Second
	}
	if cfg.QUICPadToMTU <= 0 {
		cfg.QUICPadToMTU = 1360
	}
	return &AntiDPIDialer{cfg: cfg}
}

func (d *AntiDPIDialer) UpdateConfig(cfg AntiDPIConfig) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if cfg.DialTimeout <= 0 {
		cfg.DialTimeout = 10 * time.Second
	}
	d.cfg = cfg
}

func (d *AntiDPIDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	d.mu.RLock()
	cfg := d.cfg
	d.mu.RUnlock()

	baseDialer := &net.Dialer{
		Timeout:   cfg.DialTimeout,
		KeepAlive: 25 * time.Second,
	}
	rawConn, err := baseDialer.DialContext(ctx, network, address)
	if err != nil {
		return nil, fmt.Errorf("antidpi raw dial failed: %w", err)
	}

	sniHost := cfg.WhitelistSNI
	if sniHost == "" {
		host, _, splitErr := net.SplitHostPort(address)
		if splitErr == nil {
			sniHost = host
		} else {
			sniHost = address
		}
	}

	helloID := selectClientHelloID(cfg.UTLSProfile)
	uCfg := &utls.Config{
		ServerName:         sniHost,
		InsecureSkipVerify: false,
		MinVersion:         tls.VersionTLS12,
	}

	uConn := utls.UClient(rawConn, uCfg, helloID)
	if err := uConn.HandshakeContext(ctx); err != nil {
		_ = rawConn.Close()
		return nil, fmt.Errorf("utls handshake failed (sni=%s): %w", sniHost, err)
	}

	if cfg.Mode == ModeWhitelistCDNWSS && cfg.CDNWSEndpoint != "" {
		wsConn, err := upgradeWebSocketOverUTLS(uConn, sniHost, cfg.CDNWSEndpoint)
		if err != nil {
			_ = uConn.Close()
			return nil, err
		}
		return &PaddedPacketConnWrapper{Conn: wsConn, padMTU: cfg.QUICPadToMTU}, nil
	}

	return &PaddedPacketConnWrapper{Conn: uConn, padMTU: cfg.QUICPadToMTU}, nil
}

func selectClientHelloID(profile string) utls.ClientHelloID {
	switch profile {
	case "HelloIOS_18_Auto":
		return utls.HelloIOS_Auto
	case "HelloFirefox_132_Auto":
		return utls.HelloFirefox_Auto
	default:
		return utls.HelloChrome_Auto
	}
}

func upgradeWebSocketOverUTLS(conn net.Conn, sniHost, rawEndpoint string) (net.Conn, error) {
	parsedURL, err := url.Parse(rawEndpoint)
	if err != nil {
		return nil, fmt.Errorf("invalid ws endpoint: %w", err)
	}
	path := parsedURL.RequestURI()
	if path == "" {
		path = "/ygg-ws"
	}

	nonce := make([]byte, 16)
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	wsKey := fmt.Sprintf("%x", sha256.Sum256(nonce))[:24]

	req := fmt.Sprintf(
		"GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nUser-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\r\n\r\n",
		path, sniHost, wsKey,
	)
	if _, err := conn.Write([]byte(req)); err != nil {
		return nil, fmt.Errorf("failed writing ws upgrade: %w", err)
	}

	respBuf := make([]byte, 1024)
	n, err := conn.Read(respBuf)
	if err != nil {
		return nil, fmt.Errorf("failed reading ws upgrade response: %w", err)
	}
	statusLine := string(respBuf[:n])
	if len(statusLine) < 12 || statusLine[9:12] != fmt.Sprintf("%d", http.StatusSwitchingProtocols) {
		return nil, errors.New("cdn websocket upgrade rejected by peer")
	}
	return conn, nil
}

type PaddedPacketConnWrapper struct {
	net.Conn
	padMTU int
}

func (w *PaddedPacketConnWrapper) Write(b []byte) (int, error) {
	if w.padMTU <= 0 || len(b) >= w.padMTU || len(b) > 65000 {
		return w.Conn.Write(b)
	}
	header := make([]byte, 4)
	binary.BigEndian.PutUint16(header[0:2], uint16(len(b)))
	padLen := (w.padMTU - len(b) - 4)
	if padLen < 0 {
		padLen = 0
	}
	if padLen > 256 {
		padLen = 64
	}
	binary.BigEndian.PutUint16(header[2:4], uint16(padLen))
	frame := make([]byte, 4+len(b)+padLen)
	copy(frame[0:4], header)
	copy(frame[4:4+len(b)], b)
	_, _ = rand.Read(frame[4+len(b):])
	if _, err := w.Conn.Write(frame); err != nil {
		return 0, err
	}
	return len(b), nil
}
