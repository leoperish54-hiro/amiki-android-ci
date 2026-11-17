# AFDI Background Assistant and Universal UI Task Operator

## Purpose and status

This workstream connects explicit Android system-assistant invocation to a bounded, observation-driven UI operator while the main Amiki app shell is closed. It is intended to provide generic interaction with compatible installed apps using public Android APIs. It does not claim unrestricted control of every app or screen.

The private implementation branch now contains:
- Android ACTION_ASSIST voice entry and a bounded foreground command service.
- A one-step-at-a-time Core UI planner that receives a bounded accessibility snapshot and returns a typed proposal.
- Android-side validation of every proposal against the exact current screen.
- A metadata-only persistent task journal and a bounded task orchestrator.
- The existing per-action preview, fresh biometric UOSA L2 grant, typed dispatcher, Cipher authorization event and separately audited outcome receipt.
- Recovery that never replays an action whose outcome is unknown.
- User STOP handling and an explicit owner-review gate after an uncertain external action.

**This is not yet certified for release.** GitHub CI and the physical Infinix HOT 9 acceptance matrix must pass before an APK is offered for installation. The service and role behavior have not yet been verified on the physical Android 10 device.

## Assistant invocation and background runtime

1. The owner selects Amiki as the Android default assistant, if the device exposes the role.
2. The owner invokes the system assistant using the device's configured assistant gesture or entry point.
3. Android starts AssistantEntryActivity through ACTION_ASSIST; speech recognition begins only after this explicit invocation.
4. Simple exact app launch requests are resolved against installed launchable apps. Duplicate exact labels require an owner choice; partial labels do not silently select an app.
5. Device-control commands are routed to AssistantCommandService, a foreground service with a visible ongoing notification and Stop action. The main Amiki shell is not opened just to receive the command.
6. The foreground service uses the same task runner and the same UOSA/Cipher action path as in-app control. There is no second privileged execution route.
7. A bounded task is limited to 15 UI actions and two minutes of active runtime. It stops on ambiguity, incomplete UI trees, unsupported surfaces, authorization failures, unaudited outcomes or unverified state transitions.

This implements explicit system-assistant invocation. It is **not** an always-listening “Hey Amiki” wake word or an ambient microphone monitor. Wake-word support requires a real Android voice-interaction / hotword implementation and OEM-supported keyphrase enrollment. It is not claimed for this build.

## One-step observation and planning loop

For each task:

1. Android captures a bounded accessibility tree from the foreground application window, excluding the IME/input-method window.
2. Core receives the explicit task intent plus only a bounded set of visible node labels, view IDs, class names and control flags. Editable and password field values are redacted.
3. Core's model proposes exactly one typed step: tap, type_text, scroll_forward, scroll_backward, back, completed, or needs_owner_input.
4. Core validates the proposal against the supplied snapshot. It rejects unknown action types, ambiguous targets, hidden nodes, missing postconditions, sensitive fields, invalid completion evidence and malformed output.
5. Android validates the response schema and resolves the proposed target locally. If planning took longer than the snapshot freshness window, Android re-observes the foreground app and requires the structural snapshot fingerprint to match before it can display an approval.
6. A consequential interaction is shown to the owner with the exact target or text and expected result. It requires a separate fresh biometric signature and a one-use, transaction-bound UOSA L2 device.execute grant.
7. Core appends authorization metadata to Cipher before Android acts. Cipher stores metadata and digests, not raw screen text or typed payloads.
8. Android performs only the approved typed action, re-observes the relevant app window and checks its semantic postcondition.
9. Android submits a separate L1 device.read outcome request. Core/Cipher must confirm the matching event before the task can advance.
10. Only a verified action returns the task to observation for the next single step. Only exact visible evidence in a fresh UI tree can complete a task.

The model is a planner, not an authority. It cannot issue UOSA grants, call Android APIs, execute shell commands, approve itself or change security policy. Screen and webpage labels are untrusted reference data, never instructions.

## Task state, recovery and STOP

The Android task journal persists only:
- Task UUID and SHA-256 digest of the original intent.
- Current step number and task state.
- Observed package name.
- Last action UUID and coarse result code.
- In-flight flag and update time.

It does not persist the raw user intent, UI tree, screen labels, typed content, contact details or message bodies.

On process recovery:
- A task interrupted between actions may resume from a fresh observation.
- An action that was in flight is recorded as outcome_unknown; it is never automatically replayed.
- A new task is blocked while an unresolved result requires owner review.
- The owner must inspect the target app and explicitly acknowledge the unknown outcome before a different task can start. Acknowledgement does not mark the prior action successful.
- STOP cancels immediately. If an action was already in flight, its state remains unresolved and requires owner review rather than being treated as an ordinary cancellation.

The foreground notification includes a Stop action. A spoken “stop” or “cancel task” command also routes to the STOP path. Android may not be able to undo an external side effect already accepted by another app.

## Data and privacy

Accessibility is a powerful Android permission and must remain owner-enabled and revocable. The service observes the app window only while an explicit Amiki device task is active; it does not run as a continuous screen monitor.

Visible accessibility labels and control metadata needed for planning are sent to Amiki Core and then to the configured AI provider for that task. This can include content visible in an app, such as a song title, search result or message preview. Editable and password field values are redacted; raw trees and typed text are not written to the journal or Cipher audit. The owner must understand and opt into this data flow by enabling the AccessibilityService. Disable it in Android Accessibility settings to revoke this capability.

Current limitations:
- Android apps may expose incomplete or misleading accessibility trees, hide secure surfaces, block accessibility actions or change their UI between observation and approval. These cases must pause safely.
- Login, password, PIN, OTP, payment, CAPTCHA and secure/authentication surfaces are not automated.
- The operator does not guess ambiguous contacts, accounts, destinations, amounts or message recipients.
- A model proposal is never treated as proof that a tap or text entry worked.
- Text entry readback proves the field received the text, not that an external message was sent.
- A visible message composer is not proof of sending; sent, delivered and read are distinct app states.
- A visible Spotify search result is not proof of playback. Playback must be verified from actual app state and tested on the phone.
- Some apps expose no useful view IDs or semantic labels. The operator may refuse rather than fall back to arbitrary coordinates.

## Current feature limits

Implemented in source, subject to CI and physical-device verification:
- Explicit system-assistant entry without opening the main Amiki shell.
- Exact app discovery/launch, with owner disambiguation for duplicate labels.
- One-step generic tap/type/scroll/back planning and execution through the existing approval boundary.
- Bounded multi-step task execution that re-observes after each verified action.
- Persistent task metadata, safe interruption handling and STOP.
- Semantic outcome verification and matching Cipher audit receipts.

Not yet certified:
- Reliable operation across all compatible apps and all XOS battery/background states.
- Spotify track playback verification on the HOT 9.
- YouTube search, arbitrary Chrome navigation and Instagram workflows on the HOT 9.
- WhatsApp recipient disambiguation and sending workflow on the HOT 9.
- System controls beyond currently exposed public APIs.
- Ambient “Hey Amiki” wake-word operation.

## Required CI and physical-device acceptance

### CI

- Core planner unit/API tests, UOSA scope tests, Core UI action contract tests and Cipher append-only audit tests must pass against pinned current commits.
- Android JVM unit tests and assembleDebug must pass against byte-for-byte mirrored source files.
- No private Core/Cipher source is copied into the public Android CI mirror.
- Green CI does not certify actual OEM accessibility behavior.

### Infinix HOT 9 / Android 10

1. Confirm Amiki can be selected as default assistant and invoked from another app without opening the main shell.
2. Confirm voice recognition is explicit and visible; verify no ambient microphone listening.
3. Enable the opt-in Amiki UI Operator accessibility service and inspect its Android disclosure.
4. Create a fresh UOSA session and verify expiry behavior.
5. Test app discovery, exact launch and duplicate-label owner selection.
6. Test tap, text entry, scroll and Back against fresh and stale snapshots, hidden nodes, incomplete trees, nested scroll containers and the soft keyboard.
7. Run YouTube search and confirm the actual result page is visible.
8. Run Chrome navigation and confirm the destination actually loaded.
9. Run Spotify search and confirm the requested track is actually playing, not merely listed.
10. Run WhatsApp with a deliberately ambiguous contact; require owner clarification. Preview the exact recipient and message, approve the send control separately, and report only the strongest state actually verified.
11. Confirm every executed action creates a matching Cipher authorization and outcome event; any audit failure must block task advancement.
12. Kill/restart the process during a pending action and verify no automatic replay. Confirm the owner-review gate is preserved.
13. Press notification Stop during planning, approval and an in-flight action; verify cancellation and the unknown-outcome gate as appropriate.
14. Test XOS background limits, battery saver and foreground-notification persistence.
15. Confirm the existing installed app's signing continuity before any upgrade. Do not uninstall the existing app to install a differently signed debug artifact.

**No APK should be installed as a release test until CI is green and this physical-device acceptance is recorded.**
