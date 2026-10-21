package device

import (
	"context"
	"errors"
	"testing"
	"time"

	"connectrpc.com/connect"

	castorv1 "github.com/stupside/castor/gen/castor/v1"
)

type controlledFamily struct {
	stub
	connected *bool
}

func (controlledFamily) Type() Type { return "dlna" }

func (f controlledFamily) Connect(context.Context, Info) (Device, error) {
	*f.connected = true
	return nil, errors.New("control stub")
}

func TestExternalDiscoveryUsesDeviceIdentityAndKeepsTheDirectoryContract(t *testing.T) {
	find := ExternalDiscovery(func(context.Context) ([]*castorv1.Device, error) {
		living := &castorv1.Device{Id: "native-tv", Name: "Living room", Type: castorv1.DeviceType_DEVICE_TYPE_DLNA, Address: "http://192.0.2.20/description.xml"}
		return []*castorv1.Device{living, living, {Id: "unknown", Type: castorv1.DeviceType(99)}, {Id: "no-address", Type: castorv1.DeviceType_DEVICE_TYPE_DLNA}, {Id: "unsupported", Type: castorv1.DeviceType_DEVICE_TYPE_ROKU, Address: "http://192.0.2.21"}, nil}, nil
	})
	connected := false
	r := Registry{Families: []Family{controlledFamily{connected: &connected}}, Timeout: time.Second}
	d := NewDirectory(r, find)
	listed, err := d.ListDevices(t.Context(), &castorv1.ListDevicesRequest{})
	if err != nil || len(listed.GetDevices()) != 1 {
		t.Fatalf("ListDevices = %v, %v, want one external device", listed, err)
	}
	device := listed.GetDevices()[0]
	if device.GetId() != "dlna:native-tv" || device.GetName() != "Living room" {
		t.Fatalf("external device identity changed: %v", device)
	}
	info, err := d.Target(t.Context(), &castorv1.Target{Target: &castorv1.Target_DeviceId{DeviceId: device.GetId()}})
	if err != nil || info.Address != "http://192.0.2.20/description.xml" {
		t.Fatalf("external device target = %+v, %v", info, err)
	}
	_, _ = r.Connect(t.Context(), info)
	if !connected {
		t.Fatal("external discovery bypassed the device family used for control")
	}
}

func TestExternalDiscoveryFailureIsReportedByTheDeviceAPI(t *testing.T) {
	failure := errors.New("external discovery failed")
	r := Registry{
		Timeout: time.Second,
	}
	directory := NewDirectory(r, ExternalDiscovery(func(context.Context) ([]*castorv1.Device, error) { return nil, failure }))
	_, err := directory.ListDevices(t.Context(), &castorv1.ListDevicesRequest{})
	if !errors.Is(err, failure) || connect.CodeOf(err) != connect.CodeUnavailable {
		t.Fatalf("ListDevices error = %v, want unavailable with external discovery error", err)
	}
	_, err = directory.Target(t.Context(), &castorv1.Target{Target: &castorv1.Target_DeviceId{DeviceId: "dlna:native-tv"}})
	if !errors.Is(err, failure) || connect.CodeOf(err) != connect.CodeUnavailable {
		t.Fatalf("Target error = %v, want unavailable with external discovery error", err)
	}
}
