//go:build windows

package main

// "Insulin Diary Power Control.exe" is installer, uninstaller and program in one file:
//   - run from anywhere else (e.g. Downloads): installs or updates itself into
//     %LOCALAPPDATA%\Programs\Insulin Diary, adds Start menu + desktop shortcuts and an
//     entry in Settings > Apps (with uninstall), then starts the installed copy;
//   - run from the install folder: it is the program;
//   - "--uninstall": removes it again (the diary is kept unless you choose otherwise).

import (
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	advapi32 = syscall.NewLazyDLL("advapi32.dll")
)

const (
	productName  = "Insulin Diary Power Control"
	shortcutName = "Insulin Diary"
	uninstKey    = `Software\Microsoft\Windows\CurrentVersion\Uninstall\InsulinDiary`
	runKey       = `Software\Microsoft\Windows\CurrentVersion\Run`
	runValue     = "InsulinDiary"

	mbOK          = 0x0
	mbYesNo       = 0x4
	mbRetryCancel = 0x5
	mbIconError   = 0x10
	mbIconQuest   = 0x20
	mbIconWarn    = 0x30
	mbIconInfo    = 0x40
	mbDefButton2  = 0x100
	idYes         = 6
	idRetry       = 4
)

func msgBox(text string, flags uintptr) int {
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString(productName)
	r, _, _ := user32.NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), flags|0x10000 /*MB_SETFOREGROUND*/)
	return int(r)
}

// ---------------------------------------------------------------- installer texts (Windows display language)

var msgs = map[string]map[string]string{
	"en": {"newer": "A newer version (%s) of Insulin Diary is already installed. Opening it.", "update": "Update Insulin Diary from version %s to %s?\n\nYour diary, settings and alarms are kept.",
		"install":  "Install %s %s?\n\n• Installed for your Windows account only (no administrator rights needed)\n• Start menu and desktop shortcut \"%s\"\n• Includes the Android phone app: install it on your phone from the \"Sync with phone\" screen\n\nFree software by NeQo.",
		"nofolder": "Could not create the folder:\n%s\n\n%s", "busy": "Insulin Diary could not be copied, probably because it is still running.\n\nClose all Insulin Diary windows, wait a few seconds, then click Retry.\n\n(%s)",
		"remove": "Remove Insulin Diary from this computer?", "deldata": "Also delete your diary from this computer?\n\n%s\n\nChoose No to keep it (recommended): reinstalling later brings everything back.",
		"removed": "Insulin Diary has been removed.", "kept": "Your diary is still saved in:\n%s", "port": "Insulin Diary could not start because port %s is used by another program.", "nowin": "Could not open the app window:\n%s"},
	"nl": {"newer": "Er is al een nieuwere versie (%s) van Insulin Diary geïnstalleerd. Die wordt geopend.", "update": "Insulin Diary bijwerken van versie %s naar %s?\n\nUw dagboek, instellingen en wekkers blijven bewaard.",
		"install":  "%s %s installeren?\n\n• Alleen voor uw Windows-account (geen beheerdersrechten nodig)\n• Snelkoppeling \"%s\" in het Startmenu en op het bureaublad\n• Inclusief de Android-app: installeer die via het scherm \"Synchroniseren met telefoon\"\n\nVrije software van NeQo.",
		"nofolder": "Kon de map niet maken:\n%s\n\n%s", "busy": "Insulin Diary kon niet worden gekopieerd, waarschijnlijk omdat het nog draait.\n\nSluit alle vensters van Insulin Diary, wacht een paar seconden en klik op Opnieuw.\n\n(%s)",
		"remove": "Insulin Diary van deze computer verwijderen?", "deldata": "Ook uw dagboek van deze computer verwijderen?\n\n%s\n\nKies Nee om het te bewaren (aanbevolen): bij opnieuw installeren is alles terug.",
		"removed": "Insulin Diary is verwijderd.", "kept": "Uw dagboek staat nog in:\n%s", "port": "Insulin Diary kon niet starten omdat poort %s door een ander programma wordt gebruikt.", "nowin": "Kon het appvenster niet openen:\n%s"},
	"fr": {"newer": "Une version plus récente (%s) d'Insulin Diary est déjà installée. Ouverture.", "update": "Mettre à jour Insulin Diary de la version %s vers %s ?\n\nVotre journal, vos réglages et vos alarmes sont conservés.",
		"install":  "Installer %s %s ?\n\n• Installé pour votre compte Windows uniquement (pas besoin de droits administrateur)\n• Raccourci « %s » dans le menu Démarrer et sur le bureau\n• Inclut l'app Android : installez-la depuis l'écran « Synchroniser avec le téléphone »\n\nLogiciel libre de NeQo.",
		"nofolder": "Impossible de créer le dossier :\n%s\n\n%s", "busy": "Insulin Diary n'a pas pu être copié, sans doute parce qu'il est encore ouvert.\n\nFermez toutes les fenêtres d'Insulin Diary, attendez quelques secondes, puis cliquez sur Réessayer.\n\n(%s)",
		"remove": "Supprimer Insulin Diary de cet ordinateur ?", "deldata": "Supprimer aussi votre journal de cet ordinateur ?\n\n%s\n\nChoisissez Non pour le garder (conseillé) : une réinstallation retrouvera tout.",
		"removed": "Insulin Diary a été supprimé.", "kept": "Votre journal est toujours enregistré dans :\n%s", "port": "Insulin Diary n'a pas pu démarrer car le port %s est utilisé par un autre programme.", "nowin": "Impossible d'ouvrir la fenêtre de l'app :\n%s"},
	"de": {"newer": "Eine neuere Version (%s) von Insulin Diary ist bereits installiert. Sie wird geöffnet.", "update": "Insulin Diary von Version %s auf %s aktualisieren?\n\nIhr Tagebuch, Ihre Einstellungen und Alarme bleiben erhalten.",
		"install":  "%s %s installieren?\n\n• Nur für Ihr Windows-Konto (keine Administratorrechte nötig)\n• Verknüpfung „%s“ im Startmenü und auf dem Desktop\n• Enthält die Android-App: Installieren Sie sie über den Bildschirm „Mit Handy synchronisieren“\n\nFreie Software von NeQo.",
		"nofolder": "Der Ordner konnte nicht erstellt werden:\n%s\n\n%s", "busy": "Insulin Diary konnte nicht kopiert werden, wahrscheinlich weil es noch läuft.\n\nSchließen Sie alle Fenster von Insulin Diary, warten Sie einige Sekunden und klicken Sie auf Wiederholen.\n\n(%s)",
		"remove": "Insulin Diary von diesem Computer entfernen?", "deldata": "Auch Ihr Tagebuch von diesem Computer löschen?\n\n%s\n\nWählen Sie Nein, um es zu behalten (empfohlen): Bei einer Neuinstallation ist alles wieder da.",
		"removed": "Insulin Diary wurde entfernt.", "kept": "Ihr Tagebuch ist weiterhin gespeichert in:\n%s", "port": "Insulin Diary konnte nicht starten, weil Port %s von einem anderen Programm benutzt wird.", "nowin": "Das App-Fenster konnte nicht geöffnet werden:\n%s"},
}

var uiLang = func() string {
	r, _, _ := syscall.NewLazyDLL("kernel32.dll").NewProc("GetUserDefaultUILanguage").Call()
	switch r & 0x3ff {
	case 0x13:
		return "nl"
	case 0x0c:
		return "fr"
	case 0x07:
		return "de"
	}
	return "en"
}()

func m(key string, a ...any) string { return fmt.Sprintf(msgs[uiLang][key], a...) }

func appDir() string { // the diary, key and backups
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		base = os.TempDir()
	}
	return filepath.Join(base, "InsulinDiary")
}

func installDir() string {
	return filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", "Insulin Diary")
}

func installedExe() string { return filepath.Join(installDir(), "InsulinDiary.exe") }

func samePath(a, b string) bool { return strings.EqualFold(filepath.Clean(a), filepath.Clean(b)) }

func main() {
	self, _ := os.Executable()
	if len(os.Args) > 1 && os.Args[1] == "--uninstall" {
		uninstall()
		return
	}
	if !samePath(self, installedExe()) {
		install(self)
		return
	}
	runApp()
}

// ---------------------------------------------------------------- install / update

func install(self string) {
	current := regGetString(uninstKey, "DisplayVersion")
	_, statErr := os.Stat(installedExe())
	installed := current != "" && statErr == nil

	if installed && versionCmp(current, appVersion) >= 0 {
		// Same or newer version already installed: just open it.
		if versionCmp(current, appVersion) > 0 {
			msgBox(m("newer", current), mbIconInfo)
		}
		start(installedExe())
		return
	}
	var q string
	if installed {
		q = m("update", current, appVersion)
	} else {
		q = m("install", productName, appVersion, shortcutName)
	}
	if msgBox(q, mbYesNo|mbIconQuest) != idYes {
		return
	}

	askRunningAppToQuit()
	if err := os.MkdirAll(installDir(), 0o755); err != nil {
		msgBox(m("nofolder", installDir(), err.Error()), mbIconError)
		return
	}
	for {
		err := copyFile(self, installedExe())
		if err == nil {
			break
		}
		if msgBox(m("busy", err.Error()), mbRetryCancel|mbIconWarn) != idRetry {
			return
		}
		askRunningAppToQuit()
	}

	makeShortcuts()
	size := 0
	if st, err := os.Stat(installedExe()); err == nil {
		size = int(st.Size() / 1024)
	}
	regSetStrings(uninstKey, map[string]string{
		"DisplayName":     productName,
		"DisplayVersion":  appVersion,
		"Publisher":       "NeQo",
		"DisplayIcon":     installedExe() + ",0",
		"InstallLocation": installDir(),
		"UninstallString": `"` + installedExe() + `" --uninstall`,
		"Comments":        "Free software (public domain). Glucose & insulin diary with phone sync.",
	})
	regSetDWORDs(uninstKey, map[string]uint32{"NoModify": 1, "NoRepair": 1, "EstimatedSize": uint32(size)})

	// Earlier versions: start-up entry pointed at a copy in the data folder.
	if getAutostart() {
		_ = setAutostart(true)
	}
	_ = os.Remove(filepath.Join(appDir(), "InsulinDiary.exe"))

	start(installedExe())
}

// askRunningAppToQuit stops a running copy (it would block replacing the file).
func askRunningAppToQuit() {
	c := &http.Client{Timeout: 2 * time.Second}
	req, _ := http.NewRequest(http.MethodPost, "http://127.0.0.1:"+localPort+"/api/quit", nil)
	if resp, err := c.Do(req); err == nil {
		resp.Body.Close()
		time.Sleep(1500 * time.Millisecond)
	}
}

func start(exe string) {
	cmd := exec.Command(exe)
	cmd.Dir = filepath.Dir(exe)
	_ = cmd.Start()
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".new"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, dst); err != nil {
		os.Remove(tmp)
		return err
	}
	return nil
}

// versionCmp compares "1.4" style versions.
func versionCmp(a, b string) int {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(pa) || i < len(pb); i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			if x < y {
				return -1
			}
			return 1
		}
	}
	return 0
}

func psQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

func powershell(script string) {
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000 /*CREATE_NO_WINDOW*/}
	_ = cmd.Run()
}

func makeShortcuts() {
	exe := installedExe()
	powershell(`$w = New-Object -ComObject WScript.Shell
foreach ($d in @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'))) {
  $s = $w.CreateShortcut((Join-Path $d ` + psQuote(shortcutName+".lnk") + `))
  $s.TargetPath = ` + psQuote(exe) + `
  $s.WorkingDirectory = ` + psQuote(installDir()) + `
  $s.IconLocation = ` + psQuote(exe+",0") + `
  $s.Description = ` + psQuote("Glucose & insulin diary") + `
  $s.Save()
}`)
}

func removeShortcuts() {
	powershell(`foreach ($d in @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'))) {
  Remove-Item -LiteralPath (Join-Path $d ` + psQuote(shortcutName+".lnk") + `) -ErrorAction SilentlyContinue
}`)
}

// ---------------------------------------------------------------- uninstall

func uninstall() {
	if msgBox(m("remove"), mbYesNo|mbIconQuest) != idYes {
		return
	}
	askRunningAppToQuit()
	removeShortcuts()
	_ = setAutostart(false)
	regDeleteTree(uninstKey)
	removedData := false
	if msgBox(m("deldata", appDir()), mbYesNo|mbIconWarn|mbDefButton2) == idYes {
		removedData = os.RemoveAll(appDir()) == nil
	}
	// This file is running and can't delete itself: remove the folder once it has exited.
	cmd := exec.Command("cmd.exe", "/c", "ping -n 3 127.0.0.1 >nul & rmdir /s /q \""+installDir()+"\"")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	_ = cmd.Start()
	msg := m("removed")
	if !removedData {
		msg += "\n\n" + m("kept", appDir())
	}
	msgBox(msg, mbIconInfo)
}

// ---------------------------------------------------------------- the program

func findBrowser() string {
	var cands []string
	for _, env := range []string{"ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA"} {
		if b := os.Getenv(env); b != "" {
			cands = append(cands, filepath.Join(b, `Microsoft\Edge\Application\msedge.exe`))
		}
	}
	for _, env := range []string{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"} {
		if b := os.Getenv(env); b != "" {
			cands = append(cands, filepath.Join(b, `Google\Chrome\Application\chrome.exe`))
		}
	}
	for _, c := range cands {
		if st, err := os.Stat(c); err == nil && !st.IsDir() {
			return c
		}
	}
	return ""
}

// openWindow opens the app in its own Edge/Chrome app window and returns the browser process (or nil).
func openWindow(dir, target string) *exec.Cmd {
	browser := findBrowser()
	if browser == "" {
		cmd := exec.Command("rundll32", "url.dll,FileProtocolHandler", target)
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		_ = cmd.Start()
		return nil
	}
	cmd := exec.Command(browser,
		"--app="+target,
		"--user-data-dir="+filepath.Join(dir, "data"),
		"--no-first-run", "--no-default-browser-check", "--disable-sync",
		"--autoplay-policy=no-user-gesture-required",
		"--window-size=1200,900",
	)
	if err := cmd.Start(); err != nil {
		msgBox(m("nowin", err.Error()), mbIconError)
		return nil
	}
	return cmd
}

func runApp() {
	dir := appDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		msgBox(m("nofolder", dir, err.Error()), mbIconError)
		return
	}
	home := "http://127.0.0.1:" + localPort + "/"

	ln, err := net.Listen("tcp", "127.0.0.1:"+localPort)
	if err != nil {
		// Already running? Then just open another window onto it.
		if resp, e := http.Get(home + "api/hello"); e == nil {
			b, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if strings.TrimSpace(string(b)) == "insulindiary" {
				openWindow(dir, home)
				return
			}
		}
		msgBox(m("port", localPort), mbIconError)
		return
	}
	app := newApp(dir)
	go func() { _ = (&http.Server{Handler: app.localHandler(), ReadHeaderTimeout: 10 * time.Second}).Serve(ln) }()

	// Move entries from the very first version (kept in the window's own storage) on first start.
	target := home
	old := filepath.Join(dir, "Insulin-Diary.html")
	if _, e := os.Stat(app.dataFile()); errors.Is(e, os.ErrNotExist) {
		if _, e2 := os.Stat(old); e2 == nil {
			if os.WriteFile(old, page, 0o644) == nil {
				target = "file:///" + strings.ReplaceAll(old, `\`, "/") + "#migrate=" + url.QueryEscape(home)
			}
		}
	}
	cmd := openWindow(dir, target)
	if cmd != nil {
		_ = cmd.Wait()
	}
	app.keepAlive()
}

// ---------------------------------------------------------------- start with Windows

func getAutostart() bool { return regGetString(runKey, runValue) != "" }

func setAutostart(on bool) error {
	if !on {
		return regDeleteValue(runKey, runValue)
	}
	return regSetStringsErr(runKey, map[string]string{runValue: `"` + installedExe() + `"`})
}

// ---------------------------------------------------------------- registry (HKEY_CURRENT_USER)

func regCreate(path string) (syscall.Handle, error) {
	var h syscall.Handle
	k, _ := syscall.UTF16PtrFromString(path)
	r, _, _ := advapi32.NewProc("RegCreateKeyExW").Call(uintptr(syscall.HKEY_CURRENT_USER), uintptr(unsafe.Pointer(k)), 0, 0, 0,
		uintptr(syscall.KEY_SET_VALUE|syscall.KEY_QUERY_VALUE), 0, uintptr(unsafe.Pointer(&h)), 0)
	if r != 0 {
		return 0, syscall.Errno(r)
	}
	return h, nil
}

func regSetStringsErr(path string, vals map[string]string) error {
	h, err := regCreate(path)
	if err != nil {
		return err
	}
	defer syscall.RegCloseKey(h)
	for name, val := range vals {
		n, _ := syscall.UTF16PtrFromString(name)
		v, _ := syscall.UTF16FromString(val)
		r, _, _ := advapi32.NewProc("RegSetValueExW").Call(uintptr(h), uintptr(unsafe.Pointer(n)), 0, uintptr(syscall.REG_SZ), uintptr(unsafe.Pointer(&v[0])), uintptr(len(v)*2))
		if r != 0 {
			return syscall.Errno(r)
		}
	}
	return nil
}

func regSetStrings(path string, vals map[string]string) { _ = regSetStringsErr(path, vals) }

func regSetDWORDs(path string, vals map[string]uint32) {
	h, err := regCreate(path)
	if err != nil {
		return
	}
	defer syscall.RegCloseKey(h)
	for name, val := range vals {
		n, _ := syscall.UTF16PtrFromString(name)
		v := val
		advapi32.NewProc("RegSetValueExW").Call(uintptr(h), uintptr(unsafe.Pointer(n)), 0, uintptr(syscall.REG_DWORD), uintptr(unsafe.Pointer(&v)), 4)
	}
}

func regGetString(path, name string) string {
	var h syscall.Handle
	k, _ := syscall.UTF16PtrFromString(path)
	if syscall.RegOpenKeyEx(syscall.HKEY_CURRENT_USER, k, 0, syscall.KEY_QUERY_VALUE, &h) != nil {
		return ""
	}
	defer syscall.RegCloseKey(h)
	n, _ := syscall.UTF16PtrFromString(name)
	var typ, size uint32
	if syscall.RegQueryValueEx(h, n, nil, &typ, nil, &size) != nil || size == 0 {
		return ""
	}
	buf := make([]uint16, size/2+1)
	if syscall.RegQueryValueEx(h, n, nil, &typ, (*byte)(unsafe.Pointer(&buf[0])), &size) != nil {
		return ""
	}
	return syscall.UTF16ToString(buf)
}

func regDeleteValue(path, name string) error {
	var h syscall.Handle
	k, _ := syscall.UTF16PtrFromString(path)
	if err := syscall.RegOpenKeyEx(syscall.HKEY_CURRENT_USER, k, 0, syscall.KEY_SET_VALUE, &h); err != nil {
		return nil
	}
	defer syscall.RegCloseKey(h)
	n, _ := syscall.UTF16PtrFromString(name)
	r, _, _ := advapi32.NewProc("RegDeleteValueW").Call(uintptr(h), uintptr(unsafe.Pointer(n)))
	if r != 0 && syscall.Errno(r) != syscall.ERROR_FILE_NOT_FOUND {
		return syscall.Errno(r)
	}
	return nil
}

func regDeleteTree(path string) {
	k, _ := syscall.UTF16PtrFromString(path)
	advapi32.NewProc("RegDeleteTreeW").Call(uintptr(syscall.HKEY_CURRENT_USER), uintptr(unsafe.Pointer(k)))
}
