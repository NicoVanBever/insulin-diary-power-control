package main

// Network detection: which of this PC's network connections a phone can use for sync,
// and (on Windows) whether the network type and firewall let the phone in.

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"
)

// Adapter is one IPv4 address on one of this PC's network connections.
type Adapter struct {
	Index   int    `json:"index"` // interface index (matches Windows' network profiles)
	Name    string `json:"name"`  // e.g. "Wi-Fi", "Ethernet"
	Desc    string `json:"desc"`  // driver description, e.g. "Intel(R) Wi-Fi 6 AX201"
	Kind    string `json:"kind"`  // wifi, ethernet, hotspot, usb-phone, bluetooth, vpn, virtual, other
	IP      string `json:"ip"`
	Prefix  int    `json:"prefix"`  // network size, e.g. 24 for 255.255.255.0
	Gateway bool   `json:"gateway"` // has a router (a "real" network, not a host-only one)
	Network string `json:"network"` // Windows network name (Wi-Fi name), when known
	Profile string `json:"profile"` // Windows network type: Private, Public, DomainAuthenticated
}

var virtualWords = []string{"virtualbox", "vmware", "hyper-v", "vethernet", "wsl", "docker", "loopback", "npcap", "pseudo", "kernel debug"}
var vpnWords = []string{"vpn", "tap-", "tap adapter", "tap-windows", "wireguard", "openvpn", "tailscale", "zerotier", "wintun", "anyconnect", "fortinet", "globalprotect", "nordlynx", "proton", "hamachi", "cloudflare warp"}

// classify works out the kind of connection from its name, driver description and type code.
func classify(name, desc string, ifType int, ip string) string {
	s := strings.ToLower(name + " " + desc)
	has := func(words []string) bool {
		for _, w := range words {
			if strings.Contains(s, w) {
				return true
			}
		}
		return false
	}
	switch {
	case strings.Contains(s, "wi-fi direct") || strings.Contains(s, "hosted network") || strings.HasPrefix(ip, "192.168.137."):
		return "hotspot" // the PC's own mobile hotspot
	case strings.Contains(s, "remote ndis") || strings.Contains(s, "rndis") || strings.Contains(s, "usb tether") || strings.Contains(s, "internet sharing device"):
		return "usb-phone" // phone connected by USB cable with USB tethering on
	case strings.Contains(s, "bluetooth"):
		return "bluetooth"
	case has(vpnWords):
		return "vpn"
	case has(virtualWords):
		return "virtual"
	case ifType == 71 || strings.Contains(s, "wi-fi") || strings.Contains(s, "wifi") || strings.Contains(s, "wireless") || strings.Contains(s, "wlan") || strings.Contains(s, "802.11"):
		return "wifi"
	case ifType == 6 || strings.Contains(s, "ethernet") || strings.HasPrefix(s, "eth") || strings.HasPrefix(s, "en"):
		return "ethernet"
	}
	return "other"
}

// rank: lower is better. Real home networks first; VPN / virtual adapters are never offered to the phone.
func rank(a Adapter) int {
	switch a.Kind {
	case "wifi", "ethernet":
		if a.Gateway {
			return 0
		}
		return 2
	case "usb-phone":
		return 1
	case "hotspot":
		return 2
	case "other":
		if a.Gateway {
			return 1
		}
		return 3
	case "bluetooth":
		return 4
	case "virtual":
		return 1 // a virtual switch that carries the real network (it has the router)
	}
	return 9
}

func usable(a Adapter) bool {
	ip := net.ParseIP(a.IP).To4()
	return ip != nil && ip.IsPrivate() && a.Kind != "vpn" && (a.Kind != "virtual" || a.Gateway)
}

// syncAdapters: the connections a phone can reach this PC on, best first (at most 6).
func syncAdapters() []Adapter {
	var out []Adapter
	for _, a := range listAdapters() {
		if usable(a) {
			out = append(out, a)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return rank(out[i]) < rank(out[j]) })
	if len(out) > 6 {
		out = out[:6]
	}
	return out
}

func privateIPs() []string {
	if t := os.Getenv("ID_TEST_IPS"); t != "" { // used only by automated tests
		return strings.Split(t, ",")
	}
	var ips []string
	for _, a := range syncAdapters() {
		ips = append(ips, a.IP)
	}
	return ips
}

func prefixes() []int {
	if t := os.Getenv("ID_TEST_IPS"); t != "" {
		p := make([]int, len(strings.Split(t, ",")))
		for i := range p {
			p[i] = 24
		}
		return p
	}
	var out []int
	for _, a := range syncAdapters() {
		out = append(out, a.Prefix)
	}
	return out
}

// scanTargets lists the addresses on this PC's networks where a phone might be waiting
// for a reverse connection (at most 1024 per network, nearest first).
func scanTargets() []string {
	if t := os.Getenv("ID_TEST_SCAN"); t != "" {
		return strings.Split(t, ",")
	}
	seen := map[string]bool{}
	var out []string
	for _, a := range syncAdapters() {
		ip := net.ParseIP(a.IP).To4()
		if ip == nil {
			continue
		}
		pfx := a.Prefix
		if pfx < 22 || pfx > 30 {
			pfx = 24 // big networks: scan the 254 addresses around this PC
		}
		mask := uint32(0xffffffff) << (32 - pfx)
		me := binary.BigEndian.Uint32(ip)
		base, size := me&mask, uint32(1)<<(32-pfx)
		for i := uint32(1); i < size-1; i++ {
			v := base + i
			if v == me {
				continue
			}
			b := make(net.IP, 4)
			binary.BigEndian.PutUint32(b, v)
			s := b.String()
			if !seen[s] {
				seen[s] = true
				out = append(out, s)
			}
		}
	}
	return out
}

// ---------- the network check shown on the PC's sync screen ----------

type netCheck struct {
	Adapters []Adapter     `json:"adapters"`
	Ignored  []Adapter     `json:"ignored"`  // VPN / virtual adapters, listed for information
	Firewall *firewallInfo `json:"firewall"` // nil when unknown (not Windows, or the check failed)
	CanFix   bool          `json:"canFix"`
	Recent   []lanEvent    `json:"recent"` // last phone connections to this PC
	Checked  int64         `json:"checked"`
}

type firewallInfo struct {
	On      map[string]bool `json:"on"`      // per network type: Domain / Private / Public
	Allowed map[string]bool `json:"allowed"` // an "allow" rule for this program covers that type
	Blocked map[string]bool `json:"blocked"` // a "block" rule for this program covers that type
	LocalOK bool            `json:"localOK"` // false when a company policy ignores local rules
	OwnRule bool            `json:"ownRule"` // our own rule exists
}

func buildNetCheck() netCheck {
	all := listAdapters()
	profiles, fw := windowsNetState() // nil, nil outside Windows
	var c netCheck
	for _, a := range all {
		if p, ok := profiles[a.Index]; ok {
			a.Network, a.Profile = p[0], p[1]
		}
		if usable(a) {
			c.Adapters = append(c.Adapters, a)
		} else if a.Kind == "vpn" || a.Kind == "virtual" {
			c.Ignored = append(c.Ignored, a)
		}
	}
	sort.SliceStable(c.Adapters, func(i, j int) bool { return rank(c.Adapters[i]) < rank(c.Adapters[j]) })
	c.Firewall = fw
	c.CanFix = fw != nil
	c.Checked = time.Now().UnixMilli()
	return c
}

var errCancelled = errors.New("cancelled")

// ---------- local API: GET /api/net (network check), POST /api/net/fix ----------

func (a *App) handleNet(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	c, fresh := a.netCache, a.netCache != nil && time.Since(a.netAt) < 15*time.Second && r.URL.Query().Get("fresh") == ""
	a.mu.Unlock()
	if !fresh {
		v := buildNetCheck()
		c = &v
		a.mu.Lock()
		a.netCache, a.netAt = c, time.Now()
		a.mu.Unlock()
	}
	out := *c
	a.mu.Lock()
	out.Recent = append([]lanEvent(nil), a.lanLog...)
	a.mu.Unlock()
	writeJSON(w, 200, out)
}

func (a *App) handleNetFix(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", 405)
		return
	}
	var in struct {
		Private []int `json:"private"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in)
	err := fixNetwork(in.Private)
	a.mu.Lock()
	a.netCache = nil
	a.mu.Unlock()
	switch {
	case err == nil:
		writeJSON(w, 200, map[string]any{"ok": true})
	case errors.Is(err, errCancelled):
		writeJSON(w, 200, map[string]any{"ok": false, "cancelled": true})
	default:
		writeJSON(w, 200, map[string]any{"ok": false, "error": err.Error()})
	}
}

// GET /api/net/log – the last phone connections (cheap; the sync screen asks every few seconds).
func (a *App) handleNetLog(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	out := append([]lanEvent(nil), a.lanLog...)
	a.mu.Unlock()
	writeJSON(w, 200, out)
}
