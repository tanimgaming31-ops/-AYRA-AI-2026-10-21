# AYRA Phase 1–2: isolated OpenJarvis-inspired local agent

Target repository: `tanimgaming31-ops/-AYRA-AI-2026-10-21`
Branch: `feature/openjarvis-agent`
Baseline main commit when branch was created: `b11ea36e5fd65ea71a75fec10c54163dbf45ff49`

## Isolation / data safety

- No changes to any Maya repository or files.
- All feature development commits are on this feature branch, **not main**.
- Existing AYRA chat, custom Memory textarea, settings and API keys remain in their original WebView localStorage keys; none were migrated, renamed, reset or deleted.
- **Safety preview:** The feature branch's debug build has application ID `com.ayra.assistant.agentpreview` and label `AYRA Agent Preview`. It installs **beside** the older AYRA application instead of overwriting it. The preview starts with separate, empty app-private storage; it does **not** automatically import the older app's key/chats/memory. Never uninstall your original app to test this feature branch. If a future release should upgrade the original package in place, keep its original application ID and *original signing certificate*; the test APK is not such an upgrade.
- New notes and planned task history are stored in separate private Android SQLite database `ayra_agent_v1.db` version 1. Ordinary app updates retain it; uninstalling/removing app data removes it.
- Existing Export backup action now also includes `agentNotes` and `agentTaskHistory` when running in the APK. There is **no restore/import flow yet**. Protect exported JSON: it contains private memories and chat history.
- The new checkbox "Include matching SQLite notes in AI chat prompts" defaults **off**. Turning it on can send matching note text to Gemini or to the configured OpenJarvis server. It does not change how the older Memory textarea is used.

## Implemented in branch

1. `AyraMemoryStore.java`: local SQLite notes (save / list / keyword relevance / delete) and persistently logged planner tasks.
2. `AyraCommandRouter.java`: exact, allowlisted English and selected Bengali Android actions. No arbitrary shell, sending messages, deleting files or tool instructions from model output.
3. `AyraAgentController.java`: validates and processes requests from the Android WebView bridge.
4. `MainActivity.java`: extra JSON bridge methods; existing Gemini, Live Voice, tools and OpenJarvis REST connection code remain in place.
5. `index.html`: separate Memory 2.0 panel, opt-in AI recall, Agent Planner, explicit Run approval button, and Task History. No new background service.
6. `AyraCommandRouterTest.java`: five JVM tests for accepted actions, denied message commands, flashlight, times and Bengali commands.
7. `build-apk.yml`: runs `:app:testDebugUnitTest` before `:app:assembleDebug` and accepts pushes to the feature branch.

### How to use on Android

- GitHub → AYRA repository → Actions → **Build AYRA Android APK** → choose `feature/openjarvis-agent` and Run workflow if needed.
- After successful run, download `AYRA-AI-debug-APK` artifact, unzip and install `app-debug.apk` as a **separate AYRA Agent Preview app** on your Android phone. Keep original AYRA installed. Enter an API key in preview separately if needed.
- **Settings → Smart Memory 2.0** → Save note → Show saved notes or Search.
- **Tools → Agent Planner** → type `open youtube` / `turn on flashlight` / `check battery` / `set alarm for 7:05`; tap **Plan action**, inspect, then **Run approved action**. Android might request its own permission or confirmation.
- **Tools → Agent Planner → Refresh history** shows planned, unsupported and requested tasks.

### Safe output states

- `planned`: saved on device, **not performed**.
- `unsupported`: task rejected and **cannot run**.
- `requested`: native dispatch attempted; **not a verified success**.

## Tests and acceptance

Static inspection and local smoke tests can be run before CI, but APK functionality must be tested on a real Android device. GitHub Actions passing is a build/unit-test check, not proof of microphone/network/permission operation.

- JVM: `gradle :app:testDebugUnitTest` (requires Android toolchain and Gradle).
- APK: `gradle :app:assembleDebug`.
- Verify the original AYRA app is still installed with its old chat and Memory intact; the preview has separate storage.
- Save a note, force-close and restart, then confirm it is still present.
- Search matching keywords and verify unrelated notes are excluded.
- Check the memory opt-in is off by default.
- Plan then cancel an action. Confirm no phone action runs until **Run approved action**.
- Try `send a WhatsApp message`. Confirm it is rejected.
- Plan `open youtube`; approve and confirm Android opens installed app or fallback.
- Check Task History says `requested`, not `completed`.
- Export and inspect JSON for legacy `memory`, `chats`, and new `agentNotes`.

## Limitations / next phases

This implements a **small on-device allowlisted command planner**, not Stanford OpenJarvis Python's full autonomous agent or an offline large language model. The existing OpenJarvis server integration still needs a separately running real server. No new AI inference server, auto-message sender, PDF extractor, screenshot tool, boot-persistent reminders, semantic vector search or background assistant has been added.

No Android APK build or physical phone validation should be claimed complete without confirming the relevant GitHub Actions log and on-device tests.
