# Amiki Voice and AFDI Architecture

Status: implementation foundation in progress  
Target device: Infinix HOT 9, Android 10 (API 29), 3 GB RAM  
Canonical source: private `amiki-android` repository  
Build/CI mirror: public `amiki-android-ci` repository

## System boundary

```text
"Hey Amiki" local wake-word detector
             |
             v
Amiki Native Voice Engine
  - microphone session lifecycle
  - VAD / endpointing
  - provider selection
  - STT and TTS adapters
             |
             v
Amiki Agent Runtime
  - intent understanding and planning
  - task state, delegation and cancellation
  - context and verification
             |
             v
AFDI Capabilities Gateway
  - app discovery and capability inventory
  - screen observation
  - permission and authorization checks
  - tool/action routing
             |
             v
Android Device Execution
  - launch intents and Android APIs
  - Accessibility UI actions
  - foreground services and lifecycle
  - post-action observation and verification

Cipher + UOSA: authorization, identity, action receipts and trust
across consequential execution paths.
```

## Architecture rules

1. The voice engine owns capture, recognition-provider selection, speech output and the microphone lifecycle. The agent runtime does not access the microphone directly.
2. Recognition providers are adapters. A provider failure must be visible; an explicitly selected local provider must never silently fall back to a network-capable provider.
3. The agent runtime produces bounded plans and durable task state. It does not execute arbitrary shell commands.
4. AFDI discovers capabilities and coordinates tools. It does not bypass the Android execution or authorization boundary.
5. Android execution uses supported Android APIs and the opt-in Accessibility service. Consequential UI actions retain task-bound authorization, fresh target checks, the action journal and outcome verification.
6. The wake-word detector is a small, local, separately managed component. Recognition starts only after a local wake-word match.
7. Wake-word listening is opt-in, visibly indicated, stoppable by the owner, and never streams standby audio to a remote provider.

## Current implementation slice

- Replaces the external `RecognizerIntent` activity UI with an Amiki-owned capture dialog backed by the `SpeechRecognizer` callback API.
- Adds an explicit provider policy: AUTO, ON_DEVICE, and SYSTEM.
- Exposes AUTO versus SYSTEM selection in Amiki Settings; unavailable on-device selection never silently falls back.
- AUTO prefers Android's dedicated on-device recognizer when the platform reports it available; otherwise it uses the Android system recognition service.
- ON_DEVICE fails closed when the platform does not expose an on-device recognizer.
- The Android on-device `SpeechRecognizer` API begins at API 31. The current HOT 9 is API 29, so this compatibility slice uses the system recognizer there. The offline preference is only a hint and is not proof of local processing.
- Adds a guarded active-window fallback to the Accessibility observer when enumerated application windows do not produce a usable root.

This adaptation adds an opt-in native wake-word path and Jarvis-inspired voice lifecycle controls. It does **not** yet ship a bundled offline speech-to-text model for general commands; Android 10 still uses the selected Android system recognition provider for command transcription.

## Native model and wake-word direction

**Wake-word detection:** The current branch integrates the Android Porcupine SDK as an optional, local keyword detector. The owner supplies a Picovoice AccessKey and creates the Hey Amiki .ppn model from the visible Wake Word Settings screen. Phrase-model provisioning requires an internet connection and contacts Picovoice; subsequent keyword detection runs locally on the device. The AccessKey is stored in Android Keystore-backed AES-GCM storage and is not committed to GitHub.

**General command transcription:** Still uses the selectable Android SpeechRecognizer provider boundary. On Android 10/API 29, AUTO uses the installed system recognizer (which may be Google). The platform's dedicated on-device SpeechRecognizer is only available on API 31+, so this branch is not yet fully independent of external/system STT.

A future bundled offline ASR model remains a separate milestone. Candidates such as sherpa-onnx must pass device-specific gates before adoption: supported HOT 9 ABI, APK/model distribution size, peak resident memory, CPU load, recognition latency, battery drain, and stability under XOS background management. No model is approved merely because the library supports Android.

## Wake-word lifecycle

- Default state: disabled.
- Enable only from the visible Wake Word Settings activity after an owner supplies an AccessKey, creates/validates the model, and grants microphone/notification permissions.
- The Android VoiceInteractionService remains system-managed. On onReady(), it can resume the wake-word foreground service only when the owner previously enabled it and permissions/model are still available.
- Porcupine performs local Hey Amiki keyword detection. Standby PCM is not sent to a remote STT provider.
- A persistent notification identifies listening state and exposes Stop listening.
- A detection pauses/releases the wake-word microphone engine before Android displays Amiki's voice-interaction session. The normal assistant entrypoint then captures the actual command.
- The wake-word service does not start from BOOT_COMPLETED; restoration is attempted by the selected system voice service lifecycle and can be rejected by platform background-start restrictions.
- Explicit assistant invocation pauses wake-word capture to avoid competing microphone owners. The wake service resumes after the assistant turn if it remains enabled.
- Physical screen-off, XOS process recovery, false activation, microphone arbitration, and battery consumption remain device acceptance gates.

## Android constraints

Android's dedicated on-device `SpeechRecognizer` factory is available from API 31. Android 10 therefore needs a bundled local model runtime for genuinely provider-independent offline STT. Android microphone foreground-service requirements also vary by OS level; newer Android releases require the microphone service type and permission and restrict starting a microphone foreground service from the background. The implementation must request activation while Amiki is visible and must not promise unrestricted always-on operation.

## Acceptance gates

- No Google-branded recognition activity is launched by the assistant entry point.
- The active recognition provider is explicit and diagnosable.
- Explicit ON_DEVICE mode never silently routes to SYSTEM.
- Assistant command validation, stop handling, direct app launch and bounded UI task dispatch remain unchanged.
- Screen observation can recover from a missing/unavailable enumerated root using a valid active application root, while refusing Amiki's own window.
- Wake-word listening remains disabled until a local detector, disclosure, notification, stop path and device benchmarks are complete.

## Jarvis-to-Amiki adaptation (2026-11-18)

Source reference: adewaskar/jarvis at commit 9ae5ec846b6b20a17b2d264e699d6234c4284b23. Jarvis is a browser + Node bridge architecture; this work ports its control-flow ideas into Android-native components rather than copying its browser runtime.

| Jarvis concept | Amiki Android adaptation |
|---|---|
| Voice modes: wake / command / guard / deaf | AmikiVoiceSession generation-aware mode controller |
| Adaptive energy VAD, onset, hysteresis and guard threshold | PcmVoiceActivityDetector plus PcmVoiceBargeInMonitor |
| Pause-aware transcript assembler | VoiceTranscriptAssembler integrated with Android recognition result segmentation |
| Barge-in and monotonic turn IDs | AssistantCommandService turn generations, tracked HTTP connection, cancellable work and turn-scoped TTS callbacks |
| Optional Porcupine wake engine | Native PorcupineManager integration with custom Hey Amiki model |
| Browser/Node voice bridge | Existing Android AssistantEntryActivity, AssistantCommandService, and Amiki Core API |
| Browser tools / MCP policy | Existing AFDI typed actions, owner approvals, UOSA/Cipher authorization and task journal |

### Current implementation details

- AmikiVoiceInteractionService is the Android system-managed assistant host; AmikiVoiceInteractionSessionService and AmikiVoiceInteractionSession display the Amiki assistant session.
- WakeWordSettingsActivity provisions the local phrase model and stores the Picovoice AccessKey through AmikiSecretStore.
- AmikiWakeWordService is opt-in, has a persistent notification and stop action, and releases its microphone engine before the assistant command recognizer opens.
- AssistantEntryActivity joins speech-recognition result fragments when a pause appears before a conjunction/preposition or other unfinished phrase.
- AssistantCommandService uses turn generations so a cancelled or superseded Core request/TTS result cannot mutate the current turn.
- While Amiki is speaking, PcmVoiceBargeInMonitor uses local PCM VAD and platform echo cancellation/noise suppression (where available) to detect a new speech onset and interrupt TTS. It does not transcribe or store that monitoring audio.
- All UI side effects still go through the existing bounded AFDI operator, owner approval, fresh-target checks, task journal and outcome verification. No second unrestricted Android action executor was introduced.

### Explicit limitations

- The general command STT path is not yet fully independent of Google/system recognition on API 29. The Android 10 HOT 9 uses the installed system SpeechRecognizer provider unless a future bundled ASR adapter is integrated.
- Porcupine requires an owner-supplied AccessKey and online phrase-model provisioning. The access key is not embedded in source or APK build secrets.
- No ambient listening is started by boot receiver. The owner must explicitly enable it; the system-managed VoiceInteractionService can attempt to restore a previously enabled listener.
- Barge-in detection is implemented but not physically calibrated on the HOT 9. Microphone arbitration and speaker echo need device tests.
- This GitHub CI task validates compilation and JVM tests only. It cannot prove the real wake phrase works, XOS keeps the microphone service alive, or battery consumption is acceptable.