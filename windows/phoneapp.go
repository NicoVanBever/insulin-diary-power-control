package main

import (
	"net/http"
)

// POST /api/phoneapp – puts the Android app that is built into this program in a folder and shows it in
// File Explorer, so it can be copied to a phone by USB cable (or any other way) without any network.
func (a *App) handlePhoneApp(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", 405)
		return
	}
	p := a.phoneAppFile()
	if p == "" {
		writeJSON(w, 500, map[string]any{"error": "Couldn't save the phone app file."})
		return
	}
	revealFile(p)
	writeJSON(w, 200, map[string]any{"path": p, "version": phoneApp.VersionName})
}
