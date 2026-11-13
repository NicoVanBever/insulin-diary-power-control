//go:build !windows

package main

import (
	"errors"
	"net"
)

// listAdapters (test build): every IPv4 address on every connection that is up.
func listAdapters() []Adapter {
	var out []Adapter
	ifs, _ := net.Interfaces()
	for _, in := range ifs {
		if in.Flags&net.FlagUp == 0 || in.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := in.Addrs()
		for _, ad := range addrs {
			ipn, ok := ad.(*net.IPNet)
			if !ok || ipn.IP.To4() == nil {
				continue
			}
			ones, _ := ipn.Mask.Size()
			ip := ipn.IP.To4().String()
			out = append(out, Adapter{Index: in.Index, Name: in.Name, Kind: classify(in.Name, "", 0, ip), IP: ip, Prefix: ones, Gateway: true})
		}
	}
	return out
}

func windowsNetState() (map[int][2]string, *firewallInfo) { return nil, nil }

func fixNetwork(makePrivate []int) error { return errors.New("only available on Windows") }
