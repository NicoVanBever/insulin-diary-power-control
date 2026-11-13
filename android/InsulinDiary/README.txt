INSULIN DIARY – ANDROID APP (source project)

Build the installable app (.apk) with Android Studio (free, Windows/Mac/Linux):

 1. Install Android Studio from developer.android.com/studio and open it once
    (let it download the Android SDK it suggests).
 2. File > Open… and choose this "InsulinDiary" folder. Wait until the
    "Gradle sync" at the bottom finishes (first time: a few minutes).
 3. Build > Build App Bundle(s) / APK(s) > Build APK(s).
 4. Click "locate" in the message that appears. The file is
    app/build/outputs/apk/debug/app-debug.apk
 5. Copy it to your phone (USB, Google Drive, e-mail…), open it and allow
    "Install unknown apps" for the app you opened it with.

   Or: connect the phone with USB debugging on and press the green Run ▶ button.

On the phone the first time:
 - Allow notifications (needed for alarms).
 - Android 14+: if alarms only show a banner instead of a full-screen alarm,
   open Settings > Apps > Insulin Diary > "Full screen notifications" and allow it.
 - Sync: tap Sync > "Scan code on PC". The scanner comes from Google Play
   services and may download a small module the first time.

Signing key (important for updates):
 - insulindiary-signing.jks + keystore.properties are YOUR app's signing key.
   Android only installs an update over the existing app when it is signed with
   the same key - otherwise you would have to uninstall (which deletes the diary
   on the phone). Keep this folder safe and build future versions from it.

What's inside:
 - app/src/main/assets/index.html   the diary itself (same file as the Windows app)
 - MainActivity.kt / SyncClient.kt  app window, QR scan + Wi-Fi sync, saving files
 - Alarms.kt / AlarmService.kt /
   AlarmActivity.kt                 alarms that ring on the alarm volume, also when
                                    the app is closed or the phone is locked
Your diary is stored only on the phone (app storage, plus 14 daily copies).
