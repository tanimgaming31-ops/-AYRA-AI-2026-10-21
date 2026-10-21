# AYRA AI — Android Companion + Optional OpenJarvis API

An original dark-gold Android companion UI inspired by the supplied reference image, with an original **static AI avatar illustration** (not a live rigged 3D model).

This is **not** the OpenJarvis project bundled into an APK. It is a new Android client with an optional integration with a separately running [OpenJarvis](https://github.com/open-jarvis/OpenJarvis) OpenAI-compatible API. OpenJarvis remains owned by its authors.

## Available implementations (require device tests)

- Native Android application built with WebViewAssetLoader, single-file HTML/CSS/JS interface and Java Android bridges
- Gemini text chat using **your own API key** and `gemini-2.5-flash` (subject to model availability/quota). Key stored locally in WebView localStorage; requests go directly to Google from native networking. Save/Test/Replace/Remove.
- OpenJarvis chat over `/v1/chat/completions` plus `/health` connection check; needs a **separately running and reachable** OpenJarvis server and configured inference engine/model.
- Android `SpeechRecognizer` push-to-talk, Android TextToSpeech read-out; mic and speaker do not run simultaneously through these controls.
- Chat history and editable memory text saved locally in WebView localStorage. Export JSON backup to an Android location of your choice.
- Android actions: launch YouTube/WhatsApp/IMO/Messenger/Instagram (installed known packages; browser fallback where available), open Android camera, capture and select images, send images to Gemini vision, flashlight, Android alarm app with confirmation, Wi-Fi settings, battery reading and contact picker -> dialer.
- NotificationListenerService: after explicit Notification Access granted, read recent notifications posted during the listener session. Not sent to AI automatically.
- Scheduled reminders via Android `AlarmManager.setAndAllowWhileIdle` + local notification. Reminders may be delayed by OS and do **not** survive reboot in this version.
- Calculator, AI translator, TXT import for Gemini/OpenJarvis summarization, PDF open via installed PDF reader, app permissions screen.

### Not implemented

Not a native on-phone OpenJarvis inference engine or downloaded local LLM, no all-the-time listening, no wake-word, no foreground background microphone, no animated rigged 3D character, no true phoneme lip-sync, no fully automatic WhatsApp/IMO/Messenger messages or replies, no screenshot capture, no background scheduled OpenJarvis agent inside APK, no automated PDF extraction, no full autonomous phone controls. A visible feature/command does **not** imply these actions are implemented.

## Build APK from only an Android phone, no PC

1. Create a **new GitHub repository** e.g. `AYRA-AI` (do not upload into your old Maya repository without a backup).
2. Unzip this ZIP using Files/ZArchiver. Upload **all items in this ZIP** to the root of the new GitHub repository, **including hidden `.github/workflows/build-apk.yml`**. Keep the directory structure exactly.
3. In GitHub, open **Actions → Build AYRA Android APK → Run workflow**. A push to `main` also triggers it.
4. When the action succeeds, open that run → **Artifacts → AYRA-AI-debug-APK** → download the ZIP; extract `app-debug.apk` and install it. Android may ask to allow installation from the browser/files app.
5. Launch AYRA, visit Settings → Gemini → enter **your own key** → Save & Test. Or select OpenJarvis and enter the HTTPS or local network server URL, model name, and optional bearer token.
6. Grant microphone/camera/notification permissions only when needed. Grant notification listener access separately via Settings → Notification Access.

The provided workflow builds a **debug APK** signed by Android's default debug signing. No signing keys are committed. This is a source package, **not a prebuilt APK**. A successful Actions run is needed to obtain the installer.

## OpenJarvis bridge setup

On a separate compatible machine or server with OpenJarvis configured and a running model/inference backend, follow official instructions: [installation](https://open-jarvis.github.io/OpenJarvis/getting-started/installation/) and [API server](https://open-jarvis.github.io/OpenJarvis/deployment/api-server/). For example, on that machine:

```sh
uv sync --extra server
uv run jarvis serve --host 127.0.0.1 --port 8000 --agent orchestrator
```

The above binds only to localhost on **that machine**. Android cannot access it as `localhost`; use an authenticated HTTPS reverse proxy or secure private network (or appropriate trusted LAN exposure) to make it reachable. Do not expose OpenJarvis without authentication over the public Internet. Set server URL (base, not `/v1`), select the model configured on the server, then use **Save & Test Server**. The app uses `/health` for tests and `/v1/chat/completions` for chat. The server may route to an orchestrator agent depending on its configuration, but the Android app does not provide an interface to all OpenJarvis built-in tools. Avoid HTTP on untrusted networks; the Android manifest allows it for trusted private LAN testing.

## Privacy and limits

- Gemini key and memory stored locally by the WebView. They are sent to Google **only when you make Gemini calls**; memory is included in the system instruction. The Gemini key is not sent to OpenJarvis.
- OpenJarvis server URL and optional token stored locally, and token is transmitted only to your configured server. Chat and memory are transmitted when OpenJarvis is selected.
- Chat history, API secrets and backup files are not encrypted at rest inside Android local app data. Keep your Android device protected, and store backups safely.
- Notification access is sensitive, and reading notifications is only available while the listener runs after user permission.
- The static character portrait animates slightly as a whole for visual style; there is **no 3D rigging, eye movement or mouth lip-sync**.
- This code has not been compiled into an Android APK or device-tested in this environment. Build and test in GitHub Actions and report any logs/issues.
- UI labels are English; AI chat and speech languages depend on chosen model and installed Android services.

## Troubleshooting

- **Mic blocked:** Settings → Apps → AYRA AI → Permissions → Microphone → Allow while using. Enable Google speech recognition and internet if needed; microphone uses the Android native speech recognizer, not WebView microphone.
- **No voice:** install/enable the phone's Text-to-Speech engine and choose a supported language. Voice is the system voice, not guaranteed female.
- **OpenJarvis offline:** confirm the server is running and reachable from the Android phone using its actual IP/HTTPS hostname. `localhost` on the phone is not the server computer.
- **Clone WhatsApp:** only the primary known WhatsApp package is looked up here. Vendor-specific clones need a separate discovered package/launcher implementation.
- **Notifications empty:** enable Notification Access and wait for a **new** notification. Existing notifications are not retroactively loaded.

Built on Android SDK 35, Java 17, AGP 8.7.3 and Gradle 8.9. Source code is your original app frontend; OpenJarvis integration follows their documented API.
