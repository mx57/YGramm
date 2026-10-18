// File: core/pkg/crypto/slip0010_vault.go
package crypto

import (
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"

	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/chacha20poly1305"
)

type KeyHierarchy struct {
	MasterFingerprint string
	YggNodePrivateKey ed25519.PrivateKey
	YggNodePublicKey  ed25519.PublicKey
	ChatX25519Secret  [32]byte
	BackupMasterKey   [32]byte
}

func ResolvePlatformDataDir(androidFilesDir string) (string, error) {
	if runtime.GOOS == "android" {
		if androidFilesDir == "" {
			return "", errors.New("android filesDir must be provided to Go core")
		}
		dir := filepath.Join(androidFilesDir, "ygggram_store")
		return dir, os.MkdirAll(dir, 0o700)
	}
	if runtime.GOOS == "windows" {
		appData := os.Getenv("APPDATA")
		if appData == "" {
			home, err := os.UserHomeDir()
			if err != nil {
				return "", err
			}
			appData = filepath.Join(home, "AppData", "Roaming")
		}
		dir := filepath.Join(appData, "YggGram")
		return dir, os.MkdirAll(dir, 0o700)
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(home, ".config", "ygggram")
	return dir, os.MkdirAll(dir, 0o700)
}

func DeriveSLIP0010Hierarchy(mnemonic string, epoch uint32) (*KeyHierarchy, error) {
	if len(mnemonic) < 16 {
		return nil, errors.New("mnemonic phrase is too short")
	}
	mac := hmac.New(sha512.New, []byte("ed25519 seed"))
	_, _ = mac.Write([]byte(mnemonic))
	master := mac.Sum(nil)

	fpHash := sha256.Sum256(master)
	fingerprint := hex.EncodeToString(fpHash[:8])

	nodeSeed := deriveChildSeed(master, 0, epoch)
	nodePriv := ed25519.NewKeyFromSeed(nodeSeed[:32])
	nodePub := nodePriv.Public().(ed25519.PublicKey)

	chatSeed := deriveChildSeed(master, 1, epoch)
	var chatKey [32]byte
	copy(chatKey[:], chatSeed[:32])

	backupSeed := deriveChildSeed(master, 3, 0)
	var backupKey [32]byte
	copy(backupKey[:], backupSeed[:32])

	return &KeyHierarchy{
		MasterFingerprint: fingerprint,
		YggNodePrivateKey: nodePriv,
		YggNodePublicKey:  nodePub,
		ChatX25519Secret:  chatKey,
		BackupMasterKey:   backupKey,
	}, nil
}

func deriveChildSeed(master []byte, purpose uint32, index uint32) []byte {
	mac := hmac.New(sha512.New, master[:32])
	buf := make([]byte, 9)
	buf[0] = 0x00
	binary.BigEndian.PutUint32(buf[1:5], 0x80000000|purpose)
	binary.BigEndian.PutUint32(buf[5:9], 0x80000000|index)
	_, _ = mac.Write(buf)
	return mac.Sum(nil)
}

func SealBackupContainerXChaCha20(plaintext []byte, passphrase string, masterKey [32]byte) ([]byte, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return nil, err
	}
	secretMaterial := append([]byte(passphrase), masterKey[:]...)
	derivedKey := argon2.IDKey(secretMaterial, salt, 4, 64*1024, 4, chacha20poly1305.KeySize)

	aead, err := chacha20poly1305.NewX(derivedKey)
	if err != nil {
		return nil, fmt.Errorf("failed creating xchacha20poly1305: %w", err)
	}
	nonce := make([]byte, chacha20poly1305.NonceSizeX)
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	ciphertext := aead.Seal(nil, nonce, plaintext, salt)
	header := append([]byte("YGGBAK01"), salt...)
	header = append(header, nonce...)
	return append(header, ciphertext...), nil
}

func OpenBackupContainerXChaCha20(container []byte, passphrase string, masterKey [32]byte) ([]byte, error) {
	minLen := 8 + 16 + chacha20poly1305.NonceSizeX + chacha20poly1305.Overhead
	if len(container) < minLen || string(container[:8]) != "YGGBAK01" {
		return nil, errors.New("invalid YGGBAK01 container header")
	}
	salt := container[8:24]
	nonce := container[24 : 24+chacha20poly1305.NonceSizeX]
	ciphertext := container[24+chacha20poly1305.NonceSizeX:]

	secretMaterial := append([]byte(passphrase), masterKey[:]...)
	derivedKey := argon2.IDKey(secretMaterial, salt, 4, 64*1024, 4, chacha20poly1305.KeySize)

	aead, err := chacha20poly1305.NewX(derivedKey)
	if err != nil {
		return nil, err
	}
	return aead.Open(nil, nonce, ciphertext, salt)
}
