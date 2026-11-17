# AFDI Background Assistant and UI Verification

## Scope

This change adds a system-assistant entry path for explicit, user-initiated requests while the main Amiki shell is closed. It is not an ambient wake-word implementation and does not keep the microphone open in standby.

The device must expose Android's assistant role and the owner must select Amiki as the default assistant. The exported `ACTION_ASSIST` activity rejects invocations unless Amiki currently holds that role. The intent action and role check are not a cryptographic caller-identity proof; they are the Android assistant-entry contract. They do not grant access to protected capabilities. Consequential UI actions still require the separate UOSA/Cipher authorization flow.

## Background assistant path

1. The owner invokes Amiki using the Android-configured assistant gesture.
2. Android starts `AssistantEntryActivity`; it opens the platform speech-recognition UI only after that explicit invocation.
3. The recognized command is bounded by `VoiceAssistantCommandValidator`.
4. A simple, exact installed-app launch request is resolved against MAIN/LAUNCHER activities. Partial labels are not accepted; duplicate exact labels require the owner to choose one.
5. Other requests are handed to `AssistantCommandService`, a bounded foreground service with a persistent notification and Stop action. It uses the existing Core API/UOSA session and speaks the response using Android TTS.
6. The main `MobileShellActivity` is not started merely to receive a voice command.

The service uses `START_NOT_STICKY`; it does not silently restart a request after process death. It does not store raw transcripts or answers in the notification. If Core is unavailable, it reports that no device action was executed.

## Current background limitations

- Android system-assistant invocation is supported only when Android exposes the assistant role. The HOT 9's OEM assistant gesture/role behavior still needs physical testing.
- The speech-recognition UI is provided by Android's configured recognizer; it is not an always-listening "Amiki" wake word.
- Simple exact-label app launch, Settings launch, and Chrome launch are handled from the assistant entry activity.
- Complex multi-step app workflows (for example, searching a song and verifying playback, navigating arbitrary screens, or sending a WhatsApp message) are **not** yet orchestrated from the background command service. A normal Core chat response is not proof that a device action occurred.
- Android can restrict accessibility trees, secure windows, app-owned login/CAPTCHA surfaces, and background activity launches. Unsupported screens must fail closed rather than claiming success.

## UI action authorization and verification

The UI operator supports typed tap, text entry, forward/back scroll and Back actions. Each action is bound to its task, package, window, fresh snapshot, unique target and a semantic postcondition.

- UI snapshots are bounded. If traversal omits part of the tree, a semantic action is refused.
- Node visibility is taken from Android's `isVisibleToUser()` and non-empty screen bounds.
- Non-text actions require an explicit expected label to become visible or cease being visible. The expected label is retained locally; Core and Cipher receive only its salted digest and postcondition type.
- Text entry is verified by exact field-value readback against its salted digest. Password fields are excluded.
- Each action uses a fresh one-use L2 `device.execute` grant and biometric signature, then appends authorization metadata to Cipher before Android interacts.
- A separate L1 `device.read` receipt records the result. Missing or mismatched Cipher evidence blocks success reporting.
- A verified step returns the task to observation; a single verified step does not complete a multi-step mission.

## Explicit current capabilities and limitations

The closed-shell entry path currently performs direct exact-label app launches and Android Settings/Chrome launch from the foreground assistant entry activity. It can answer ordinary conversational questions while Amiki's main shell is closed. It intentionally refuses unsupported imperative device commands.

The accessibility operator currently has a typed, one-action dispatcher with biometric UOSA approval, fresh-window/target validation, semantic postconditions, and append-only Cipher evidence. However, there is not yet a durable high-level task planner that turns arbitrary speech into resumable cross-app step sequences and drives these low-level APIs. Therefore the following are not yet end-to-end capabilities from the closed-shell voice path: Spotify song search/playback verification, arbitrary YouTube/Chrome/Instagram workflows, or WhatsApp message composition and sending. They must not be described as supported until that orchestrator exists and passes real-app acceptance.

## Required physical-device acceptance

Before this feature can be called release-ready on the Infinix HOT 9 (Android 10/API 29), run and record:

1. Configure Amiki as the default assistant in Settings.
2. Invoke Amiki from a different app and confirm the main Amiki shell does not open.
3. Speak an exact installed-app launch request and confirm the requested app becomes foreground.
4. Test duplicate app labels and uninstalled/hidden apps; no ambiguous or partial match may launch.
5. Ask a general question and confirm the foreground notification appears, TTS speaks the response, and the service stops after completion.
6. Use the notification Stop action during a response and confirm TTS and service stop.
7. Deny/disable the required accessibility service and confirm UI automation refuses to act.
8. Exercise tap, text, scroll and Back with fresh and stale windows, including off-screen labels and incomplete trees.
9. Verify the Spotify playback and WhatsApp send examples against their actual app state and distinguish compose, sent, delivered and read states.
10. Confirm fresh UOSA authorization and matching Cipher events for every executed UI action; verify no success is reported if audit recording fails.

Passing GitHub unit tests and APK assembly does not replace these device tests.
