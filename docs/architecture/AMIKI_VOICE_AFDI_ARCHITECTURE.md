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
- AUTO prefers Android's dedicated on-device recognizer when the platform reports it available; otherwise it uses the Android system recognition service.
- ON_DEVICE fails closed when the platform does not expose an on-device recognizer.
- The Android on-device `SpeechRecognizer` API begins at API 31. The current HOT 9 is API 29, so this compatibility slice uses the system recognizer there. The offline preference is only a hint and is not proof of local processing.
- Adds a guarded active-window fallback to the Accessibility observer when enumerated application windows do not produce a usable root.

This slice does **not** ship a bundled local speech model or activate an ambient wake-word service.

## Native model and wake-word direction

Evaluate a bundled, offline-capable Android runtime such as sherpa-onnx for:
- keyword spotting for the phrase `Hey Amiki`;
- streaming or chunked speech recognition;
- voice activity detection and endpointing;
- optional local text-to-speech.

The candidate must pass device-specific gates before adoption: supported HOT 9 ABI, APK/model distribution size, peak resident memory, CPU load, wake latency, recognition latency, battery drain, false-accept and false-reject rates, and stability under XOS background management. No model is approved merely because the library supports Android.

## Wake-word lifecycle

- Default state: disabled.
- Enable only from a visible Amiki activity after microphone permission and an explicit owner action.
- Use an on-device keyword spotter; do not invoke the general STT provider while waiting for the wake word.
- Show a persistent, truthful listening notification while microphone capture is active, with a stop action.
- Stop listening on explicit owner stop, permission revocation, microphone contention, service failure, and unsupported lifecycle conditions.
- Do not auto-start microphone capture at boot. Background start and screen-off reliability must be separately validated against the OS version and vendor power manager.
- After a wake-word match, hand off to the Amiki voice engine for command capture. Preserve a clear listening-state transition.

## Android constraints

Android's dedicated on-device `SpeechRecognizer` factory is available from API 31. Android 10 therefore needs a bundled local model runtime for genuinely provider-independent offline STT. Android microphone foreground-service requirements also vary by OS level; newer Android releases require the microphone service type and permission and restrict starting a microphone foreground service from the background. The implementation must request activation while Amiki is visible and must not promise unrestricted always-on operation.

## Acceptance gates

- No Google-branded recognition activity is launched by the assistant entry point.
- The active recognition provider is explicit and diagnosable.
- Explicit ON_DEVICE mode never silently routes to SYSTEM.
- Assistant command validation, stop handling, direct app launch and bounded UI task dispatch remain unchanged.
- Screen observation can recover from a missing/unavailable enumerated root using a valid active application root, while refusing Amiki's own window.
- Wake-word listening remains disabled until a local detector, disclosure, notification, stop path and device benchmarks are complete.
