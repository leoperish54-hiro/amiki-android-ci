# Amiki V2 Android Architecture

**Last verified:** 2026-11-12  
**Repository:** leoperish54-hiro/amiki-android  
**Audited main:** 39c0d4ce8330c7321d839aed7b6a43043e2f8a63  
**Scope:** Source and test-structure audit. No physical-device runtime test is claimed.

## 1. Architectural role

The Android application is the user-facing companion and device integration layer. Core remains authoritative for conversation metadata/transcripts, model execution, capabilities, memory policy, and server-side tool decisions. Android owns navigation, local UI state, a bounded conversation cache, permission prompts, Android intents, and the local Termux launch path.

The app is not yet a cleanly layered client. Most active UI, chat, networking, and conversation persistence are concentrated in large Activity classes. Smaller controller/helper classes exist, but network and theme responsibilities are still repeated across screens.

## 2. Build and platform baseline

- Language: Java.
- Android Gradle Plugin: 9.2.0.
- Gradle CI setup: 9.7.1.
- compileSdk: 36; targetSdk: 36; minSdk: 23.
- Application ID and namespace: com.amiki.app.
- Dependencies declared in app/build.gradle.kts: CommonMark 0.24.0 at runtime, JUnit 4.13.2 and org.json for local tests.
- The current build does not declare Retrofit, OkHttp, Room, WorkManager, Jetpack Compose, or an AndroidX Biometric dependency.
- The user's target device is Android 10/API 29 with constrained memory. A successful API 36 compile does not prove runtime behavior on that device.

## 3. Application startup and navigation

1. BootstrapActivity is the exported launcher Activity.
2. BootstrapActivity asks TermuxBackendLauncher to start the local Core API command, starts SentinelService, launches MobileShellActivity, and finishes. It does not wait for a successful Core health response before showing the shell.
3. TermuxBackendLauncher invokes the Termux Run Command service using a fixed absolute script path and working directory under the user's Termux home. A successful startService result means the command was accepted by Termux; it is not proof that Core is healthy.
4. MobileShellActivity is the active main shell. It builds its views programmatically and owns the main navigation, Chat, conversation history, dashboard, memory, settings, and several other sections.
5. MainActivity is marked deprecated and its own class comment says the launcher now opens MobileShellActivity. It remains in the manifest and retains substantial older UI/network code; it should not receive new features.
6. WorkspaceActivity is a separate large Activity for project/file workspace functions. BrowserActivity, CipherConsoleActivity, and LearningReviewActivity are also separate Activities. DiagnosticsScreen is used by the active shell. SettingsScreen and WorkspaceScreens are retained helpers for the deprecated MainActivity prototype.

## 4. Major source modules

| Module | Responsibility and current boundary |
|---|---|
| MobileShellActivity.java | Active shell, Chat, dashboard, settings navigation, voice interaction, conversation index/cache, local device-action dispatch, and many Core API calls. Approximately 5,244 lines. |
| WorkspaceActivity.java | Project selection, workspace file browsing/editing, search, Git inspection/diffs, proposals, command/script workflow, and direct-agent interaction. Approximately 2,478 lines. |
| ApiEndpoints.java | Central constants and validators for the local Core API routes; conversation and Cloud goal identifiers are validated before URL construction. |
| TermuxBackendLauncher.java | Explicit Termux Run Command service contract and local backend script path. |
| DeviceActionExecutor.java | Local Android intents/actions such as Settings, browser, battery status, alarms, and contact calling; permission-aware for contacts. |
| DeviceBridge.java | Requests a backend device plan, checks the planned status, and requires explicit user approval by default before executing supported actions. |
| LocationContextProvider.java | One-shot, permission-aware location context; attaches it only for current-location or safety-sensitive requests. |
| SentinelService.java / SentinelStateMachine.java / SentinelJournal.java | Independent backend-health observer, bounded recovery attempts, and durable local event journal. |
| OwnerTokenVault.java | Encrypts the narrow ALE owner-review bearer token using Android Keystore AES-GCM. |
| CipherConsoleActivity.java | Read-only Cipher status and registered-root inspection UI; it does not store a Cipher token. |
| LearningReviewActivity.java | Owner-reviewed ALE learning queue and token setup/removal UI. |
| DiagnosticsScreen.java | Read-only runtime/observability diagnostics, report export, and Inspection interaction. |
| AmikiTheme.java | Shared dark/light/system palette resolver used by the active shell. |

## 5. Core API and network boundary

- ApiEndpoints.BASE_URL is http://127.0.0.1:8000; API routes are rooted under /api/v1.
- The app reaches Core over loopback. Cloud UI endpoints are exposed through the Core /api/v1/cloud route family; this client does not configure a separate direct Cloud host.
- The endpoint registry includes chat, conversations, multipart attachments, voice, direct agents, health, memory, learning review, Cipher status/inspection, diagnostics/observability, academic/workspace, and device capability/state/plan/execute routes.
- Screens use HttpURLConnection directly. Timeouts, response parsing, error handling, and threading are implemented in individual Activities/controllers rather than one shared API client.
- network_security_config disables cleartext traffic by default and permits it only for 127.0.0.1. The manifest also sets usesCleartextTraffic=false.
- The client sends the conversation UUID with JSON and multipart Main Chat requests. The API endpoint helper validates canonical UUID v4 identifiers.
- Network calls commonly use manually created threads and post results back to the UI thread. Lifecycle cancellation and consistent retry policy are not centralized.

## 6. Conversation state and local persistence

Main Chat now supports independent UUID v4 conversations.

- SharedPreferences file: amiki_conversation_history.
- Stored keys include the bounded conversation index, active conversation ID, per-conversation transcript cache entries, and direct_agent_history.
- Local cache bounds in MobileShellActivity: 20 indexed conversations; 80 messages per cached transcript; 8,000 characters per cached conversation message.
- Chat rendering is further bounded to 30 messages, with an 8,000-character render limit per message.
- Direct-agent history is separately bounded to 80 messages and 24,000 characters per saved message.
- The complete transcript remains in Core. Android hydrates remote metadata/transcripts and keeps only a local working cache.
- The previous single-session transcript is deliberately left untouched; no automatic import into a new conversation occurs.
- persistConversationHistory() uses SharedPreferences.Editor.apply(). onDestroy() also calls persistence, but process-death/crash recovery has not been demonstrated on a physical device.

### Conversation recovery gap found in source review

When a remote-only conversation has no writable local transcript cache, showChat() starts one remote hydration attempt and marks it attempted. If that request fails, the exception is swallowed, loading is cleared, and the attempted flag remains set for the current Activity. The Send control becomes available again even though the older transcript was not hydrated. Since the local transcript cache is not writable yet, new local turns are not persisted to that cache. A later Activity recreation can retry because restore derives the attempted state from the presence of a local transcript key, but the current screen has no explicit retry state or clear recovery message.

This does not delete the authoritative Core transcript, but it can leave the current UI incomplete and make the user believe earlier history is missing. Add an explicit hydration state machine, visible retry, and a deliberate send policy before declaring lifecycle recovery complete.

### Other conversation limitation

DeviceActionExecutor can satisfy certain requests locally before the normal Core chat path. Those local results are kept in the Android chat history but are not mirrored into the Core conversation transcript. This is a known boundary, not evidence of full cross-device transcript synchronization.

## 7. Device permissions and platform integrations

The manifest currently declares INTERNET, coarse/fine location, RECORD_AUDIO, READ_CONTACTS, SET_ALARM, Termux RUN_COMMAND, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, and RECEIVE_BOOT_COMPLETED.

- DeviceActionExecutor uses Android intents/APIs for a bounded set of local actions. Contact lookup/calling requires runtime Contacts permission; denial reports that no call was placed.
- DeviceBridge asks Core to plan device actions and requires user approval by default. Keep this backend-planned path distinct from direct local intents until the duplicate-action/security audit establishes one canonical route per action.
- LocationContextProvider requires coarse or fine location permission, rejects stale locations older than five minutes, and waits up to three seconds for a fresh update. It does not request location for every chat message.
- Main Chat supports one-shot speech recognition and Android TextToSpeech, plus an API-backed voice playback path. This is not a continuous listening engine.
- BrowserActivity uses Android WebView and has Safe Browsing/error handling.
- No biometric prompt or biometric authorization path was found in the inspected source or declared dependencies. Owner-token encryption is not the same thing as biometric-gated authorization.

## 8. Sentinel and backend recovery

- BootstrapActivity starts SentinelService during app launch; SentinelBootReceiver also requests Sentinel startup after BOOT_COMPLETED.
- SentinelService is a non-AI foreground observer with START_STICKY behavior and a single scheduled executor.
- It polls Core health every 15 seconds, applies SentinelStateMachine transitions, and delegates bounded Termux restart attempts to TermuxBackendLauncher.
- When Core is healthy, Sentinel synchronizes batches of up to 50 unsynced local events.
- SentinelJournal uses SharedPreferences with synchronous commit for journal writes, a 200-record default cap, and seven-day retention. It preserves unsynced events rather than evicting them first.
- The manifest declares a specialUse foreground-service type and subtype. Compatibility, foreground-service policy, notification behavior, and recovery reliability must be tested on Android 10/API 29 and current Android releases; source code alone cannot establish OEM background behavior.
- Sentinel observes and attempts bounded recovery. It does not prove that a backend process started successfully until a subsequent health check succeeds.

## 9. Theme and UI architecture

AmikiTheme defines dark, light, and system modes and a shared palette. On Android 12+, it can use available system accent colors when system mode is selected. MobileShellActivity resolves this palette before building its views.

The theme is not yet universal:
- WorkspaceActivity, CipherConsoleActivity, LearningReviewActivity, and DiagnosticsScreen still define their own color constants. SettingsScreen also has a separate palette, but it belongs to the deprecated MainActivity prototype.
- Some screens use different background/panel values and do not consume the shared palette.
- MainActivity retains a separate legacy UI implementation.
- Primary screens are built programmatically in Java rather than from a consistent component/layout system.

A centralized palette exists, but the V2 unified design system and consistent light/dark application across every screen are not complete.

## 10. Security and data handling observations

- android:allowBackup is false.
- Cleartext networking is restricted to loopback 127.0.0.1.
- Non-launcher Activities and SentinelService are declared non-exported. BootstrapActivity is exported as the launcher; SentinelBootReceiver is exported for the boot-completed broadcast.
- OwnerTokenVault uses an Android Keystore AES key with AES/GCM/NoPadding and a 128-bit GCM tag. The plaintext token is not stored in SharedPreferences. The current implementation does not require biometric authentication for token use.
- CipherConsoleActivity describes itself as read-only and routes inspection through Amiki; no Cipher credential is stored in that screen.
- Location and contact access are permission-gated. Continue to keep device-derived context minimal and scoped to the request that needs it.
- WorkspaceActivity contains explicit confirmation/review UI for command execution and change proposals. Core-side authorization remains authoritative; a client confirmation dialog must never replace backend policy.

## 11. Tests and verification

The repository has 13 local JUnit test classes under app/src/test covering theme/orb state, API endpoint validation, Cipher presentation contracts, Cloud goal idempotency, device command parsing, learning review models, location policy, Markdown rendering, security report formatting, and Sentinel journal/state-machine behavior.

- No app/src/androidTest instrumentation test tree was present in the audited main tree.
- GitHub Actions run #43071842576 passed on Android main 39c0d4c. The workflow ran unit tests and the private debug APK publication job; the build job and unit tests completed successfully.
- This verifies the CI build/test path only. It does not verify installation, navigation, backend connectivity, permission prompts, Sentinel boot behavior, process death, or performance on the target phone.
- No physical-device test is claimed in this audit.

## 12. Main risks and V2 implementation dependencies

1. **Large Activity concentration:** MobileShellActivity and WorkspaceActivity own too many UI, network, persistence, and orchestration responsibilities. Refactor only after the current contracts are tested; do not perform a wholesale rewrite.
2. **Legacy/duplicate surface:** MainActivity remains deprecated but contains older chat and navigation logic; WorkspaceScreens and WorkspaceActivity represent overlapping generations of workspace UI. Audit entry points and user-visible actions before removal.
3. **Theme fragmentation:** AmikiTheme is not consumed by every screen. Establish shared tokens/components before adding more screens.
4. **Conversation recovery:** Add visible hydration failure/retry, a safe send policy, durable draft behavior, and A → B → A/process-death tests.
5. **Transcript completeness:** Decide how local-only device actions should be represented in the Core transcript without pretending they were executed by Core.
6. **Network duplication:** Introduce a small shared API client only where it reduces duplicated timeout/error/lifecycle handling; preserve existing endpoint contracts.
7. **Sentinel platform behavior:** Verify special-use foreground service, boot startup, Termux permission, and bounded recovery on API 29 and supported newer Android versions.
8. **Biometric authorization:** Not implemented. Add only for concrete protected operations, using Android biometric APIs and enforcing authorization at the operation boundary.
9. **Permission-aware capabilities:** Expose actual supported device operations and permission/platform restrictions; do not advertise an action merely because a manifest permission exists.

## 13. Recommended Android V2 order

Follow the canonical order in docs/AMIKI_V2_ROADMAP.md:

1. Unified V2 UI/UX and centralized light/dark theme system.
2. Duplicate-action and legacy-screen audit; consolidate safely.
3. Session/backend recovery, hydration retry, interrupted-task handling, and process-death persistence.
4. Harden and verify Android Sentinel.
5. Direct agent panels with text/voice input and TTS, with explicit state/cancellation.
6. Biometric access for operations that require it.
7. Permission-aware device capability discovery and truthful platform reporting.

Do not broaden Cloud, Cipher, or ALE from the Android client as part of this work. Cipher X remains deferred.

## 14. Audit conclusion

The Android client already has meaningful foundations: a working launcher/bootstrap path, a centralized API endpoint registry, independent conversation IDs and bounded local history, a theme resolver, device permission handling, encrypted ALE owner-token storage, a read-only Cipher console, and a non-AI Sentinel observer.

The V2 work should preserve these foundations and close the documented lifecycle, architecture, and consistency gaps in small tested slices. The highest-priority Android work after this audit is conversation hydration/recovery and process-death acceptance, not adding more screens.
