package main

import (
	"net/http"
	"os"
	"path/filepath"
)

// POST /api/phoneapp – puts the Android app that is built into this program in a folder and shows it in
// File Explorer, so it can be copied to a phone by USB cable (or any other way) without any network.
func (a *App) handlePhoneApp(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", 405)
		return
	}
	dir := filepath.Join(a.dir, "Phone app")
	_ = os.MkdirAll(dir, 0o755)
	p := filepath.Join(dir, "InsulinDiary-"+phoneApp.VersionName+".apk")
	if err := os.WriteFile(p, apk, 0o644); err != nil {
		writeJSON(w, 500, map[string]any{"error": err.Error()})
		return
	}
	revealFile(p)
	writeJSON(w, 200, map[string]any{"path": p, "version": phoneApp.VersionName})
}
