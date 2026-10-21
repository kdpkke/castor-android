package hls

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"

	"github.com/stupside/castor/services/mediaserver/internal/source/timeline"
)

// decryptAES128 decrypts an HLS whole-resource AES-128/CBC payload and checks its PKCS#7 padding.
func decryptAES128(sealed, secret []byte, ivHex string) ([]byte, error) {
	if len(secret) != aes.BlockSize {
		return nil, fmt.Errorf("an AES-128 key is 16 bytes, not %d", len(secret))
	}
	digits := strings.TrimPrefix(strings.TrimPrefix(ivHex, "0x"), "0X")
	if len(digits) == 0 || len(digits) > aes.BlockSize*2 {
		return nil, fmt.Errorf("the IV %q is not a 128-bit integer", ivHex)
	}
	iv, err := hex.DecodeString(strings.Repeat("0", aes.BlockSize*2-len(digits)) + digits)
	if err != nil || len(iv) != aes.BlockSize {
		return nil, fmt.Errorf("the IV %q is not 16 bytes", ivHex)
	}
	if len(sealed) == 0 || len(sealed)%aes.BlockSize != 0 {
		return nil, fmt.Errorf("%d encrypted bytes are not whole AES blocks", len(sealed))
	}
	block, err := aes.NewCipher(secret)
	if err != nil {
		return nil, err
	}
	clear := make([]byte, len(sealed))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(clear, sealed)
	pad := int(clear[len(clear)-1])
	if pad == 0 || pad > aes.BlockSize || !bytes.Equal(clear[len(clear)-pad:], bytes.Repeat([]byte{byte(pad)}, pad)) {
		return nil, errors.New("the decrypted resource is not PKCS#7 padded: the key or IV is not the one it was sealed with")
	}
	return clear[:len(clear)-pad], nil
}

const aes128 = "AES-128"

// decryptable is whole-resource AES-128 under a key whose URI serves the raw key bytes.
func decryptable(k timeline.Key) bool {
	return k.Method == aes128 && (k.Format == "" || k.Format == timeline.IdentityFormat)
}
