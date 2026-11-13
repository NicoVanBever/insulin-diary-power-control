// Insulin Diary for Windows: a small local server that serves the app page,
// keeps the diary in a file on disk and relays encrypted Wi-Fi syncs with the phone app (see sync.go).
package main

import (
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

//go:embed app.html
var page []byte

const (
	appVersion = "1.7"
	localPort  = "47621"
	lanPort    = "47622"
	maxBody    = 256 << 20
)

type App struct {
	dir      string
	mu       sync.Mutex
	lastPing time.Time

	master   []byte              // this user's 256-bit key (key.bin), never leaves the PC
	diaryID  string              // short public fingerprint of the master key
	sessions map[string]*session // one per QR code
	current  string              // id of the code shown right now
	bad      int
	pend     *pending
	dls      map[string]time.Time // one-time update download links
	lanOnce  sync.Once
	lanErr   error

	netCache *netCheck // last network check (sync screen)
	netAt    time.Time
	lastPoll time.Time // the sync screen asks every second while it is open
	lanLog   []lanEvent
}

func newApp(dir string) *App {
	a := &App{dir: dir, lastPing: time.Now(), sessions: map[string]*session{}, dls: map[string]time.Time{}}
	a.loadMaster()
	return a
}

func (a *App) dataFile() string { return filepath.Join(a.dir, "diary.json") }

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

// ---------- local (127.0.0.1) side: only this PC's app window can use it ----------

func (a *App) localHandler() http.Handler {
	mux := http.NewServeMux()
	servePage := func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" && r.URL.Path != "/migrate" && r.URL.Path != "/index.html" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Frame-Options", "DENY")
		_, _ = w.Write(page)
	}
	mux.HandleFunc("/", servePage)
	mux.HandleFunc("/api/hello", func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "insulindiary") })
	mux.HandleFunc("/api/ping", func(w http.ResponseWriter, r *http.Request) {
		a.mu.Lock()
		a.lastPing = time.Now()
		a.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/api/data", a.handleData)
	mux.HandleFunc("/api/quit", func(w http.ResponseWriter, r *http.Request) { // used by the installer before an update
		if r.Method != http.MethodPost {
			http.Error(w, "method", 405)
			return
		}
		w.WriteHeader(http.StatusNoContent)
		go func() { time.Sleep(300 * time.Millisecond); os.Exit(0) }()
	})
	mux.HandleFunc("/api/sync/open", a.handleOpen)
	mux.HandleFunc("/api/sync/poll", a.handlePoll)
	mux.HandleFunc("/api/sync/reply", a.handleReply)
	mux.HandleFunc("/api/net", a.handleNet)
	mux.HandleFunc("/api/net/log", a.handleNetLog)
	mux.HandleFunc("/api/phoneapp", a.handlePhoneApp)
	mux.HandleFunc("/api/net/fix", a.handleNetFix)
	mux.HandleFunc("/api/autostart", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			var in struct{ On bool }
			if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&in); err != nil {
				http.Error(w, "bad request", 400)
				return
			}
			if err := setAutostart(in.On); err != nil {
				writeJSON(w, 500, map[string]any{"on": getAutostart(), "error": err.Error()})
				return
			}
		}
		writeJSON(w, 200, map[string]any{"on": getAutostart()})
	})
	// Guard: only requests addressed to this exact local origin (blocks DNS-rebinding and other web pages).
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host := r.Host
		if host != "127.0.0.1:"+localPort && host != "localhost:"+localPort {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			o := r.Header.Get("Origin")
			if o != "" && o != "http://127.0.0.1:"+localPort && o != "http://localhost:"+localPort {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
		}
		mux.ServeHTTP(w, r)
	})
}

func (a *App) handleData(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		b, err := os.ReadFile(a.dataFile())
		if errors.Is(err, os.ErrNotExist) {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(b)
	case http.MethodPut:
		b, err := io.ReadAll(io.LimitReader(r.Body, maxBody))
		if err != nil || !json.Valid(b) {
			http.Error(w, "bad data", 400)
			return
		}
		a.mu.Lock()
		err = a.saveData(b)
		a.mu.Unlock()
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		http.Error(w, "method", 405)
	}
}

// saveData writes atomically and keeps one dated copy per day for the last 14 days.
func (a *App) saveData(b []byte) error {
	bdir := filepath.Join(a.dir, "backups")
	_ = os.MkdirAll(bdir, 0o755)
	day := filepath.Join(bdir, "diary-"+time.Now().Format("2006-01-02")+".json")
	if _, err := os.Stat(day); errors.Is(err, os.ErrNotExist) {
		if old, err := os.ReadFile(a.dataFile()); err == nil {
			_ = os.WriteFile(day, old, 0o644)
		}
		if list, err := filepath.Glob(filepath.Join(bdir, "diary-*.json")); err == nil && len(list) > 14 {
			sort.Strings(list)
			for _, f := range list[:len(list)-14] {
				_ = os.Remove(f)
			}
		}
	}
	tmp := a.dataFile() + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, a.dataFile())
}

func (a *App) handlePoll(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	a.lastPoll = time.Now()
	p := a.pend
	if p == nil || p.taken {
		a.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
		return
	}
	p.taken = true
	a.mu.Unlock()
	writeJSON(w, 200, map[string]any{"id": p.id, "device": p.device, "appVersion": p.appVersion, "state": p.state})
}

func (a *App) handleReply(w http.ResponseWriter, r *http.Request) {
	var in replyMsg
	if err := json.NewDecoder(io.LimitReader(r.Body, maxBody)).Decode(&in); err != nil {
		http.Error(w, "bad request", 400)
		return
	}
	a.mu.Lock()
	p := a.pend
	a.mu.Unlock()
	if p == nil || p.id != in.ID {
		http.Error(w, "no such sync", 404)
		return
	}
	select {
	case p.reply <- in:
	default:
	}
	w.WriteHeader(http.StatusNoContent)
}

// keepAlive returns once the app window has stopped sending heartbeats (closed),
// ignoring gaps caused by the computer sleeping.
func (a *App) keepAlive() {
	last := time.Now()
	for {
		time.Sleep(5 * time.Second)
		now := time.Now()
		a.mu.Lock()
		if now.Sub(last) > 20*time.Second { // the computer was asleep
			a.lastPing = now
		}
		idle := now.Sub(a.lastPing)
		busy := a.pend != nil
		a.mu.Unlock()
		last = now
		if idle > 45*time.Second && !busy {
			return
		}
	}
}
