package main

// Phone sync and app install over the home Wi-Fi.
//
// Security:
//   - Every installation creates its own random 256-bit master key (key.bin). Its SHA-256
//     fingerprint is the "diary ID" shown on both devices, so a phone knows which PC it belongs to.
//   - Every QR code gets a fresh 256-bit key derived from the master key and a random nonce
//     (HMAC-SHA256), so no two codes are ever alike. The key travels only inside the QR code
//     (after "#", which browsers never send), never over the network.
//   - All diary data between phone and PC is encrypted and authenticated with AES-256-GCM
//     under that key. A code works for one sync and expires after 5 minutes.
//   - App updates are offered inside the encrypted answer together with the APK's SHA-256,
//     which the phone checks before installing (Android also checks the app's signature).

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed phone/InsulinDiary.apk
var apk []byte

//go:embed phone/version.json
var apkVersionJSON []byte

type apkInfo struct {
	VersionCode int    `json:"versionCode"`
	VersionName string `json:"versionName"`
}

var phoneApp = func() apkInfo {
	var v apkInfo
	_ = json.Unmarshal(apkVersionJSON, &v)
	return v
}()

var apkSHA = func() string { s := sha256.Sum256(apk); return hex.EncodeToString(s[:]) }()

const (
	codeLife     = 5 * time.Minute  // a code can be used for a sync this long
	downloadLife = 15 * time.Minute // its install page / app download stays available this long
)

type session struct {
	id      string
	key     []byte
	created time.Time
	used    bool
}

type pending struct {
	id         string
	device     string
	appVersion int
	state      json.RawMessage
	taken      bool
	reply      chan replyMsg
	created    time.Time
}

type replyMsg struct {
	ID    string          `json:"id"`
	State json.RawMessage `json:"state"`
	Stats json.RawMessage `json:"stats"`
	Error string          `json:"error"`
}

// ---------- keys ----------

func (a *App) loadMaster() {
	f := filepath.Join(a.dir, "key.bin")
	b, err := os.ReadFile(f)
	if err != nil || len(b) != 32 {
		b = make([]byte, 32)
		if _, err := rand.Read(b); err != nil {
			panic(err)
		}
		_ = os.WriteFile(f, b, 0o600)
	}
	a.master = b
	sum := sha256.Sum256(append([]byte("insulindiary-id|"), b...))
	h := strings.ToUpper(hex.EncodeToString(sum[:8]))
	a.diaryID = h[0:4] + "-" + h[4:8] + "-" + h[8:12] + "-" + h[12:16]
}

func (a *App) newSession() *session {
	nonce := make([]byte, 16)
	_, _ = rand.Read(nonce)
	m := hmac.New(sha256.New, a.master)
	m.Write([]byte("IDSYNC2|"))
	m.Write(nonce)
	key := m.Sum(nil) // 256-bit key, unique to this code and this user
	idSum := sha256.Sum256(key)
	return &session{id: hex.EncodeToString(idSum[:16]), key: key, created: time.Now()}
}

func seal(key []byte, aad string, plain []byte) []byte {
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	nonce := make([]byte, gcm.NonceSize())
	_, _ = rand.Read(nonce)
	return gcm.Seal(nonce, nonce, plain, []byte(aad))
}

func open(key []byte, aad string, data []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(data) < gcm.NonceSize()+gcm.Overhead() {
		return nil, errors.New("short")
	}
	return gcm.Open(nil, data[:gcm.NonceSize()], data[gcm.NonceSize():], []byte(aad))
}

// ---------- local API used by the PC window ----------

func (a *App) handleOpen(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", 405)
		return
	}
	a.lanOnce.Do(func() { a.lanErr = a.startLAN() })
	if a.lanErr != nil {
		writeJSON(w, 500, map[string]any{"error": a.lanErr.Error()})
		return
	}
	s := a.newSession()
	a.mu.Lock()
	for id, old := range a.sessions {
		if time.Since(old.created) > downloadLife {
			delete(a.sessions, id)
		}
	}
	a.sessions[s.id] = s
	a.current, a.bad = s.id, 0
	a.mu.Unlock()

	ips := privateIPs()
	url := ""
	if len(ips) > 0 {
		url = fmt.Sprintf("http://%s:%s/p/%s#k=%s&d=%s", ips[0], lanPort, s.id,
			base64.RawURLEncoding.EncodeToString(s.key), strings.ReplaceAll(a.diaryID, "-", ""))
		if len(ips) > 1 {
			url += "&i=" + strings.Join(ips[1:], ",")
		}
		var m []string
		for _, p := range prefixes() {
			m = append(m, strconv.Itoa(p))
		}
		url += "&m=" + strings.Join(m, ",") + "&r=1" // network sizes (for the phone's network check); PC can connect back
	}
	writeJSON(w, 200, map[string]any{
		"url": url, "ips": ips, "port": lanPort, "expiresIn": int(codeLife.Seconds()),
		"diaryId": a.diaryID, "app": phoneApp,
	})
}

// ---------- LAN side ----------

func (a *App) startLAN() error {
	ln, err := net.Listen("tcp", "0.0.0.0:"+lanPort)
	if err != nil {
		return err
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/ping", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, map[string]any{"app": "insulindiary"}) })
	mux.HandleFunc("/p/", a.handleInstallPage)
	mux.HandleFunc("/app/", a.handleAppDownload)
	mux.HandleFunc("/u/", a.handleUpdateDownload)
	mux.HandleFunc("/s/", a.handleSync)
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = srv.Serve(ln) }()
	go a.reverseLoop()
	return nil
}

func (a *App) sessionFor(id string, forSync bool) *session {
	a.mu.Lock()
	defer a.mu.Unlock()
	s := a.sessions[id]
	if s == nil {
		return nil
	}
	if forSync {
		if id != a.current || s.used || time.Since(s.created) > codeLife {
			return nil
		}
		return s
	}
	if time.Since(s.created) > downloadLife {
		return nil
	}
	return s
}

func serveAPK(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="InsulinDiary-`+phoneApp.VersionName+`.apk"`)
	w.Header().Set("Content-Length", strconv.Itoa(len(apk)))
	w.Header().Set("Cache-Control", "no-store")
	_, _ = io.Copy(w, bytes.NewReader(apk))
}

// GET /app/<code id>/InsulinDiary.apk – first install, from the page the phone camera opens.
func (a *App) handleAppDownload(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/app/"), "/")
	if len(parts) != 2 || a.sessionFor(parts[0], false) == nil {
		http.Error(w, "This download link has expired. Show a new code on your PC and scan it again.", http.StatusGone)
		return
	}
	serveAPK(w)
}

// GET /u/<one-time token>/InsulinDiary.apk – update, offered inside an encrypted sync answer.
func (a *App) handleUpdateDownload(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/u/"), "/")
	a.mu.Lock()
	exp, ok := a.dls[parts[0]]
	a.mu.Unlock()
	if len(parts) != 2 || !ok || time.Now().After(exp) {
		http.Error(w, "This update link has expired. Sync again to get a new one.", http.StatusGone)
		return
	}
	serveAPK(w)
}

// POST /s/<code id> – encrypted sync (the phone connects to the PC).
func (a *App) handleSync(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/s/")
	if r.Method != http.MethodPost {
		http.Error(w, "method", 405)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBody))
	if err != nil {
		return
	}
	status, ctype, out := a.runSync(r.Context(), id, body, r.Header.Get("X-Device"), r.Header.Get("X-App-Version"))
	w.Header().Set("Content-Type", ctype)
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(out)
}

func jsonErr(status int, msg string) (int, string, []byte) {
	b, _ := json.Marshal(map[string]any{"ok": false, "error": msg})
	return status, "application/json", b
}

// runSync unlocks the phone's diary, hands it to the PC window, waits for the merged answer and
// returns it encrypted. Used for both directions of connection (phone → PC and PC → phone).
func (a *App) runSync(ctx context.Context, id string, body []byte, dev, verS string) (int, string, []byte) {
	s := a.sessionFor(id, true)
	if s == nil {
		a.mu.Lock()
		a.bad++
		a.mu.Unlock()
		return jsonErr(403, "This code has expired or was already used. Scan the new code on your PC.")
	}
	plain, err := open(s.key, "req|"+s.id, body)
	if err != nil || !json.Valid(plain) {
		a.mu.Lock()
		a.bad++
		if a.bad > 20 {
			a.current = "" // too many failed attempts: the PC shows a new code
		}
		a.mu.Unlock()
		return jsonErr(403, "The data couldn't be unlocked with this code. Scan the newest code on your PC.")
	}
	a.mu.Lock()
	if s.used || (a.pend != nil && time.Since(a.pend.created) < 2*time.Minute) {
		a.mu.Unlock()
		return jsonErr(409, "The PC is busy with another sync. Try again in a moment.")
	}
	s.used = true
	if len(dev) > 60 {
		dev = dev[:60]
	}
	ver, _ := strconv.Atoi(verS)
	p := &pending{id: randHex(8), device: dev, appVersion: ver, state: plain, reply: make(chan replyMsg, 1), created: time.Now()}
	a.pend = p
	a.mu.Unlock()
	defer func() {
		a.mu.Lock()
		if a.pend == p {
			a.pend = nil
		}
		a.mu.Unlock()
	}()

	var rep replyMsg
	select {
	case rep = <-p.reply:
	case <-time.After(60 * time.Second):
		return jsonErr(504, "The PC didn't answer. Keep Insulin Diary open on the \"Sync with phone\" screen and try again.")
	case <-ctx.Done():
		return jsonErr(499, "cancelled")
	}

	host, _ := os.Hostname()
	out := map[string]any{"ok": rep.Error == "" && len(rep.State) > 0, "host": host, "diaryId": a.diaryID}
	if rep.Error != "" || len(rep.State) == 0 {
		out["error"] = rep.Error
	} else {
		out["state"], out["stats"] = rep.State, rep.Stats
		if phoneApp.VersionCode > ver && len(apk) > 0 {
			tok := randHex(16)
			a.mu.Lock()
			a.dls[tok] = time.Now().Add(30 * time.Minute)
			a.mu.Unlock()
			out["update"] = map[string]any{
				"versionCode": phoneApp.VersionCode, "versionName": phoneApp.VersionName,
				"sha256": apkSHA, "size": len(apk), "path": "/u/" + tok + "/InsulinDiary.apk",
			}
		}
	}
	plainOut, _ := json.Marshal(out)
	return 200, "application/octet-stream", seal(s.key, "res|"+s.id, plainOut)
}

// ---------- reverse connection ----------
//
// When the phone can't reach the PC (usually the Windows firewall, which blocks incoming connections but
// not outgoing ones), the phone waits on port 47623 instead. While the sync screen is open, the PC looks
// for it on its own networks and connects out to it:
//   GET  http://<phone>:47623/r/<code id>   → the phone's encrypted diary (only for the code it scanned)
//   POST http://<phone>:47623/r/<code id>   ← the PC's encrypted answer
// The data is exactly what would have gone the other way, encrypted with the same one-time key.

const revPort = "47623"

func (a *App) reverseLoop() {
	for {
		time.Sleep(4 * time.Second)
		a.mu.Lock()
		s := a.sessions[a.current]
		active := time.Since(a.lastPoll) < 4*time.Second && s != nil && !s.used && a.pend == nil && time.Since(s.created) < codeLife
		a.mu.Unlock()
		if active {
			a.reverseScan(s)
		}
	}
}

func (a *App) reverseScan(s *session) {
	var (
		mu   sync.Mutex
		hits []string
		wg   sync.WaitGroup
	)
	sem := make(chan struct{}, 96)
	for _, ip := range scanTargets() {
		wg.Add(1)
		sem <- struct{}{}
		go func(ip string) {
			defer wg.Done()
			defer func() { <-sem }()
			c, err := net.DialTimeout("tcp", net.JoinHostPort(ip, revPort), 350*time.Millisecond)
			if err != nil {
				return
			}
			c.Close()
			mu.Lock()
			hits = append(hits, ip)
			mu.Unlock()
		}(ip)
	}
	wg.Wait()
	for _, ip := range hits {
		if a.reverseSync(ip, s) {
			return
		}
	}
}

var revClient = &http.Client{Timeout: 70 * time.Second}

// reverseSync returns true when the device at ip was the phone waiting for this code.
func (a *App) reverseSync(ip string, s *session) bool {
	base := "http://" + net.JoinHostPort(ip, revPort) + "/r/" + s.id
	resp, err := (&http.Client{Timeout: 15 * time.Second}).Get(base)
	if err != nil {
		return false
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, maxBody))
	resp.Body.Close()
	if resp.StatusCode != 200 || resp.Header.Get("X-Insulin-Diary") != "1" { // another device, or a phone waiting for a different code
		return false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 65*time.Second)
	defer cancel()
	status, ctype, out := a.runSync(ctx, s.id, body, resp.Header.Get("X-Device"), resp.Header.Get("X-App-Version"))
	req, _ := http.NewRequest(http.MethodPost, base, bytes.NewReader(out))
	req.Header.Set("Content-Type", ctype)
	req.Header.Set("X-Status", strconv.Itoa(status))
	if r2, err := revClient.Do(req); err == nil {
		r2.Body.Close()
	}
	return true
}

// GET /p/<code id> – what the phone's camera opens when the app isn't installed yet (in the phone's language).
var installText = map[string]map[string]string{
	"en": {"title": "Insulin Diary for Android", "from": "from your PC", "dl": "Download the app", "s1": "Tap <b>Download the app</b>.", "s2": "Open the downloaded file. If Android asks, allow installing apps from your browser, then tap <b>Install</b>.", "s3": "Open <b>Insulin Diary</b> → <b>Sync</b> → <b>Scan code on PC</b> and scan the code on your PC again.", "note": "Already have the app? You don't need this page — open the app and use <b>Sync → Scan code on PC</b>. Newer versions are offered automatically when you sync.", "exp": "<b>This code has expired.</b><br>Click <b>Show a new code</b> in Insulin Diary on your PC and scan it again.", "foot": "For Android phones only. This page comes straight from your own PC over your Wi-Fi."},
	"nl": {"title": "Insulin Diary voor Android", "from": "van uw pc", "dl": "App downloaden", "s1": "Tik op <b>App downloaden</b>.", "s2": "Open het gedownloade bestand. Vraagt Android erom, sta dan installeren vanuit uw browser toe en tik op <b>Installeren</b>.", "s3": "Open <b>Insulin Diary</b> → <b>Sync</b> → <b>Code op pc scannen</b> en scan de code op uw pc opnieuw.", "note": "Heeft u de app al? Dan heeft u deze pagina niet nodig — open de app en gebruik <b>Sync → Code op pc scannen</b>. Nieuwere versies worden bij synchroniseren vanzelf aangeboden.", "exp": "<b>Deze code is verlopen.</b><br>Klik in Insulin Diary op uw pc op <b>Nieuwe code tonen</b> en scan opnieuw.", "foot": "Alleen voor Android-telefoons. Deze pagina komt rechtstreeks van uw eigen pc via uw wifi."},
	"fr": {"title": "Insulin Diary pour Android", "from": "depuis votre PC", "dl": "Télécharger l'app", "s1": "Touchez <b>Télécharger l'app</b>.", "s2": "Ouvrez le fichier téléchargé. Si Android le demande, autorisez l'installation depuis votre navigateur, puis touchez <b>Installer</b>.", "s3": "Ouvrez <b>Insulin Diary</b> → <b>Synchro</b> → <b>Scanner le code sur le PC</b> et scannez à nouveau le code sur votre PC.", "note": "Vous avez déjà l'app ? Cette page est inutile — ouvrez l'app et utilisez <b>Synchro → Scanner le code sur le PC</b>. Les nouvelles versions sont proposées automatiquement lors de la synchro.", "exp": "<b>Ce code a expiré.</b><br>Cliquez sur <b>Afficher un nouveau code</b> dans Insulin Diary sur votre PC et scannez-le à nouveau.", "foot": "Pour téléphones Android uniquement. Cette page vient directement de votre PC via votre Wi-Fi."},
	"de": {"title": "Insulin Diary für Android", "from": "von Ihrem PC", "dl": "App herunterladen", "s1": "Tippen Sie auf <b>App herunterladen</b>.", "s2": "Öffnen Sie die heruntergeladene Datei. Wenn Android fragt, erlauben Sie die Installation über Ihren Browser und tippen Sie auf <b>Installieren</b>.", "s3": "Öffnen Sie <b>Insulin Diary</b> → <b>Sync</b> → <b>Code am PC scannen</b> und scannen Sie den Code am PC erneut.", "note": "Sie haben die App schon? Dann brauchen Sie diese Seite nicht — öffnen Sie die App und nutzen Sie <b>Sync → Code am PC scannen</b>. Neuere Versionen werden beim Synchronisieren automatisch angeboten.", "exp": "<b>Dieser Code ist abgelaufen.</b><br>Klicken Sie in Insulin Diary am PC auf <b>Neuen Code anzeigen</b> und scannen Sie erneut.", "foot": "Nur für Android-Handys. Diese Seite kommt direkt von Ihrem eigenen PC über Ihr WLAN."},
}

func pickLang(acceptLanguage string) string {
	for _, part := range strings.Split(acceptLanguage, ",") {
		l := strings.ToLower(strings.TrimSpace(part))
		if len(l) >= 2 {
			if _, ok := installText[l[:2]]; ok {
				return l[:2]
			}
		}
	}
	return "en"
}

var installTpl = template.Must(template.New("p").Parse(`<!doctype html>
<html lang="{{.Lang}}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{.T.title}}</title>
<style>
:root{--bg:#f5f6f8;--card:#fff;--ink:#1b1f24;--muted:#5d6672;--line:#e2e5e9;--accent:#1f6feb;--soft:#e8f0fe}
@media (prefers-color-scheme:dark){:root{--bg:#0f1216;--card:#171b21;--ink:#e6e9ed;--muted:#9aa4b0;--line:#2a3038;--accent:#2a6ae0;--soft:#16233a}}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,-apple-system,Roboto,sans-serif}
main{max-width:480px;margin:0 auto;padding:28px 16px 40px}
.logo{width:56px;height:56px;border-radius:16px;background:#1f6feb;display:grid;place-items:center}
h1{font-size:24px;margin:16px 0 4px}.muted{color:var(--muted)}
.btn{display:block;text-align:center;background:var(--accent);color:#fff;text-decoration:none;font-weight:700;font-size:18px;padding:18px;border-radius:16px;margin:22px 0}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px 18px;margin-top:14px}
ol{margin:0;padding-left:20px}li{margin:6px 0}.note{background:var(--soft);border-radius:12px;padding:12px 14px;font-size:14px;margin-top:14px}
</style></head><body><main>
<div class="logo"><svg width="30" height="30" viewBox="0 0 24 24" fill="#fff" aria-hidden="true"><path d="M12 2.5s6.5 7 6.5 11.5a6.5 6.5 0 0 1-13 0C5.5 9.5 12 2.5 12 2.5z"/></svg></div>
<h1>{{.T.title}}</h1>
<div class="muted">{{.Version}} · {{.T.from}} "{{.Host}}"</div>
{{if .OK}}
<a class="btn" href="/app/{{.ID}}/InsulinDiary.apk">{{.T.dl}} ({{.Size}})</a>
<div class="card"><ol><li>{{.H.s1}}</li><li>{{.H.s2}}</li><li>{{.H.s3}}</li></ol></div>
<div class="note">{{.H.note}}</div>
{{else}}
<div class="card">{{.H.exp}}</div>
{{end}}
<p class="muted" style="font-size:13px;margin-top:22px">{{.T.foot}}</p>
</main></body></html>`))

func (a *App) handleInstallPage(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/p/")
	host, _ := os.Hostname()
	ok := a.sessionFor(id, false) != nil
	lang := pickLang(r.Header.Get("Accept-Language"))
	t := installText[lang]
	h := map[string]template.HTML{}
	for _, k := range []string{"s1", "s2", "s3", "note", "exp"} {
		h[k] = template.HTML(t[k]) // fixed texts written in this file
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	if !ok {
		w.WriteHeader(http.StatusGone)
	}
	_ = installTpl.Execute(w, map[string]any{
		"OK": ok, "ID": id, "Host": host, "Version": phoneApp.VersionName, "Lang": lang, "T": t, "H": h,
		"Size": fmt.Sprintf("%.1f MB", float64(len(apk))/1e6),
	})
}
