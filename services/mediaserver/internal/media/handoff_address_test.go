package media

import (
	"net/url"
	"testing"
)

func TestReceiverSourceAddressesExcludeLocalListeners(t *testing.T) {
	for _, host := range []string{"127.0.0.1", "127.8.0.4", "[::1]", "[::ffff:127.0.0.1]", "0.0.0.0", "[::ffff:0.0.0.0]", "[::]", "localhost", "video.localhost."} {
		t.Run(host, func(t *testing.T) {
			address, _ := url.Parse("http://" + host + "/live.m3u8")
			program := Program{Inputs: []Input{{ID: PrimaryInputID, URL: address}}}
			if program.SelfFetchable() {
				t.Fatal("local listener can be handed to a receiver")
			}
		})
	}
	for _, host := range []string{"192.168.1.2", "media.example"} {
		address, _ := url.Parse("https://" + host + "/live.m3u8")
		program := Program{Inputs: []Input{{ID: PrimaryInputID, URL: address}}}
		if !program.SelfFetchable() {
			t.Fatalf("ordinary source %s was refused", host)
		}
	}
}
