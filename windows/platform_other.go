//go:build !windows

// Test build for non-Windows systems: serves the app without opening a window.
package main

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"time"
)

var autostart bool

func getAutostart() bool         { return autostart }
func setAutostart(on bool) error { autostart = on; return nil }

func main() {
	dir := os.Getenv("ID_DIR")
	if dir == "" {
		dir, _ = os.MkdirTemp("", "insulindiary")
	}
	_ = os.MkdirAll(dir, 0o755)
	ln, err := net.Listen("tcp", "127.0.0.1:"+localPort)
	if err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
	app := newApp(dir)
	fmt.Println("serving http://127.0.0.1:" + localPort + "/ data in " + dir)
	_ = (&http.Server{Handler: app.localHandler(), ReadHeaderTimeout: 10 * time.Second}).Serve(ln)
}
