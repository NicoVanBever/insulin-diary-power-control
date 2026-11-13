//go:build windows

package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

func cstr(b []byte) string {
	if i := strings.IndexByte(string(b), 0); i >= 0 {
		return string(b[:i])
	}
	return string(b)
}

// listAdapters uses Windows' own adapter list: it knows the adapter type, its driver description and its router.
func listAdapters() []Adapter {
	names := map[int]string{}
	up := map[int]bool{}
	if ifs, err := net.Interfaces(); err == nil {
		for _, in := range ifs {
			names[in.Index] = in.Name
			up[in.Index] = in.Flags&net.FlagUp != 0
		}
	}
	size := uint32(16 * 1024)
	var buf []byte
	for i := 0; i < 3; i++ {
		buf = make([]byte, size)
		err := syscall.GetAdaptersInfo((*syscall.IpAdapterInfo)(unsafe.Pointer(&buf[0])), &size)
		if err == nil {
			break
		}
		if err != syscall.ERROR_BUFFER_OVERFLOW {
			return nil
		}
	}
	var out []Adapter
	for ai := (*syscall.IpAdapterInfo)(unsafe.Pointer(&buf[0])); ai != nil; ai = ai.Next {
		idx := int(ai.Index)
		if v, ok := up[idx]; ok && !v {
			continue
		}
		desc := cstr(ai.Description[:])
		name := names[idx]
		if name == "" {
			name = desc
		}
		gw := false
		for g := &ai.GatewayList; g != nil; g = g.Next {
			if s := cstr(g.IpAddress.String[:]); s != "" && s != "0.0.0.0" {
				gw = true
			}
		}
		for a := &ai.IpAddressList; a != nil; a = a.Next {
			ip := cstr(a.IpAddress.String[:])
			if ip == "" || ip == "0.0.0.0" {
				continue
			}
			ones, _ := net.IPMask(net.ParseIP(cstr(a.IpMask.String[:])).To4()).Size()
			out = append(out, Adapter{Index: idx, Name: name, Desc: desc, Kind: classify(name, desc, int(ai.Type), ip), IP: ip, Prefix: ones, Gateway: gw})
		}
	}
	return out
}

func psEncode(script string) string {
	u := utf16.Encode([]rune(script))
	b := make([]byte, len(u)*2)
	for i, c := range u {
		b[2*i], b[2*i+1] = byte(c), byte(c>>8)
	}
	return base64.StdEncoding.EncodeToString(b)
}

func powershellOut(script string, timeout time.Duration) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", psEncode(script))
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000 /*CREATE_NO_WINDOW*/}
	return cmd.Output()
}

func selfExe() string {
	p, err := os.Executable()
	if err != nil {
		return installedExe()
	}
	return p
}

const fwRuleName = "Insulin Diary Power Control (phone sync)"

type psState struct {
	Profiles []struct {
		Idx  int    `json:"idx"`
		Name string `json:"name"`
		Cat  string `json:"cat"`
	} `json:"profiles"`
	Fw []struct {
		Name    string `json:"name"`
		On      bool   `json:"on"`
		LocalOK string `json:"localOK"`
	} `json:"fw"`
	Rules []struct {
		Name    string `json:"name"`
		On      string `json:"on"`
		Action  string `json:"action"`
		Profile string `json:"profile"`
	} `json:"rules"`
}

// windowsNetState asks Windows for each connection's network type (Public / Private) and for the
// firewall rules that apply to this program. Takes a second or two; called only from the sync screen.
func windowsNetState() (map[int][2]string, *firewallInfo) {
	script := `$ErrorActionPreference='SilentlyContinue'
$exe = ` + psQuote(selfExe()) + `
$p = @(Get-NetConnectionProfile | ForEach-Object { @{ idx = [int]$_.InterfaceIndex; name = [string]$_.Name; cat = [string]$_.NetworkCategory } })
$f = @(Get-NetFirewallProfile -PolicyStore ActiveStore | ForEach-Object { @{ name = [string]$_.Name; on = [bool]($_.Enabled -eq 'True' -or $_.Enabled -eq 1); localOK = [string]$_.AllowLocalFirewallRules } })
$r = @(Get-NetFirewallApplicationFilter -Program $exe | Get-NetFirewallRule | Where-Object { [string]$_.Direction -eq 'Inbound' } | ForEach-Object { @{ name = [string]$_.DisplayName; on = [string]$_.Enabled; action = [string]$_.Action; profile = [string]$_.Profile } })
@{ profiles = $p; fw = $f; rules = $r } | ConvertTo-Json -Depth 4 -Compress`
	out, err := powershellOut(script, 25*time.Second)
	if err != nil {
		return nil, nil
	}
	var st psState
	if json.Unmarshal(out, &st) != nil {
		return nil, nil
	}
	prof := map[int][2]string{}
	for _, p := range st.Profiles {
		prof[p.Idx] = [2]string{p.Name, p.Cat}
	}
	fw := &firewallInfo{On: map[string]bool{}, Allowed: map[string]bool{}, Blocked: map[string]bool{}, LocalOK: true}
	for _, f := range st.Fw {
		fw.On[f.Name] = f.On
		if strings.EqualFold(f.LocalOK, "False") {
			fw.LocalOK = false
		}
	}
	types := []string{"Domain", "Private", "Public"}
	for _, r := range st.Rules {
		if !strings.EqualFold(r.On, "True") {
			continue
		}
		if r.Name == fwRuleName {
			fw.OwnRule = true
		}
		for _, t := range types {
			if r.Profile == "Any" || strings.Contains(r.Profile, t) {
				if strings.EqualFold(r.Action, "Block") {
					fw.Blocked[t] = true
				} else {
					fw.Allowed[t] = true
				}
			}
		}
	}
	return prof, fw
}

// fixNetwork runs, with administrator permission (Windows asks once), a short script that
//   - removes "block" rules Windows created for this program (when "Cancel" was clicked on its question),
//   - allows this program to receive phone syncs on private (home) and domain networks,
//   - marks the given connections as Private (home) networks.
func fixNetwork(makePrivate []int) error {
	exe := selfExe()
	s := `$ErrorActionPreference='SilentlyContinue'
$exe = ` + psQuote(exe) + `
Get-NetFirewallApplicationFilter -Program $exe | Get-NetFirewallRule | Where-Object { [string]$_.Direction -eq 'Inbound' -and [string]$_.Action -eq 'Block' } | Remove-NetFirewallRule
Remove-NetFirewallRule -DisplayName ` + psQuote(fwRuleName) + `
New-NetFirewallRule -DisplayName ` + psQuote(fwRuleName) + ` -Direction Inbound -Action Allow -Program $exe -Protocol TCP -LocalPort ` + lanPort + ` -Profile Private,Domain | Out-Null
`
	for _, i := range makePrivate {
		s += "Set-NetConnectionProfile -InterfaceIndex " + strconv.Itoa(i) + " -NetworkCategory Private\n"
	}
	s += "exit 0\n"
	// The outer PowerShell (normal rights) starts the inner one with "Run as administrator" and waits.
	outer := `try { $p = Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -PassThru -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','` + psEncode(s) + `'; exit $p.ExitCode } catch { exit 1223 }`
	_, err := powershellOut(outer, 3*time.Minute)
	var ee *exec.ExitError
	if errors.As(err, &ee) && ee.ExitCode() == 1223 {
		return errCancelled
	}
	return err
}
