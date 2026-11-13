package main

import "testing"

func TestClassifyAndRank(t *testing.T) {
	cases := []struct {
		name, desc string
		typ        int
		ip, want   string
	}{
		{"Wi-Fi", "Intel(R) Wi-Fi 6 AX201 160MHz", 71, "192.168.1.10", "wifi"},
		{"Ethernet", "Realtek PCIe GbE Family Controller", 6, "192.168.1.11", "ethernet"},
		{"Ethernet 3", "VirtualBox Host-Only Ethernet Adapter", 6, "192.168.56.1", "virtual"},
		{"vEthernet (WSL)", "Hyper-V Virtual Ethernet Adapter", 6, "172.29.16.1", "virtual"},
		{"Local Area Connection* 10", "Microsoft Wi-Fi Direct Virtual Adapter #2", 71, "192.168.137.1", "hotspot"},
		{"Ethernet 4", "Remote NDIS based Internet Sharing Device", 6, "192.168.42.17", "usb-phone"},
		{"NordLynx", "NordLynx Tunnel", 53, "10.5.0.2", "vpn"},
		{"Ethernet 2", "TAP-Windows Adapter V9", 6, "10.8.0.6", "vpn"},
	}
	for _, c := range cases {
		if got := classify(c.name, c.desc, c.typ, c.ip); got != c.want {
			t.Errorf("%s: got %s want %s", c.desc, got, c.want)
		}
	}
	wifi := Adapter{Kind: "wifi", IP: "192.168.1.10", Gateway: true}
	vbox := Adapter{Kind: "virtual", IP: "192.168.56.1"}
	ext := Adapter{Kind: "virtual", IP: "192.168.1.20", Gateway: true}
	vpn := Adapter{Kind: "vpn", IP: "10.5.0.2", Gateway: true}
	if usable(vbox) || usable(vpn) || !usable(wifi) || !usable(ext) {
		t.Error("usable")
	}
	if rank(wifi) >= rank(Adapter{Kind: "hotspot"}) {
		t.Error("rank")
	}
}
