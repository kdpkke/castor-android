package hls

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"fmt"
	"testing"

	"github.com/stupside/castor/services/mediaserver/internal/source/timeline"
)

func TestDecoderDecryptsAnHLSInitSection(t *testing.T) {
	clear := []byte("initialization")
	secret, iv := bytes.Repeat([]byte{7}, aes.BlockSize), bytes.Repeat([]byte{9}, aes.BlockSize)
	pad := aes.BlockSize - len(clear)%aes.BlockSize
	sealed := append(bytes.Clone(clear), bytes.Repeat([]byte{byte(pad)}, pad)...)
	block, err := aes.NewCipher(secret)
	if err != nil {
		t.Fatal(err)
	}
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(sealed, sealed)
	section := timeline.Map{Key: timeline.Key{Method: "AES-128", URI: "key", IV: fmt.Sprintf("0x%x", iv), Format: timeline.IdentityFormat}}
	keyReads := 0
	keys := func(_ context.Context, uri string) ([]byte, error) {
		if uri != "key" {
			t.Fatalf("read key %q", uri)
		}
		keyReads++
		return secret, nil
	}
	got, err := (Decoder{}).Map(t.Context(), section, sealed, keys)
	if err != nil || !bytes.Equal(got, clear) || keyReads != 1 {
		t.Fatalf("decoded init = %q, key reads = %d, error = %v", got, keyReads, err)
	}
	section.Key.Format = "com.example.drm"
	got, err = (Decoder{}).Map(t.Context(), section, sealed, keys)
	if err != nil || !bytes.Equal(got, sealed) || keyReads != 1 {
		t.Fatalf("unsupported key changed init: key reads = %d, error = %v", keyReads, err)
	}
}
