# AFDI Cross-App Operator Architecture

**Status:** Approved direction; implementation is staged. This document does not claim that any interaction feature is already implemented.
**Target device:** Infinix HOT 9, Android 10 / API 29, 3 GB RAM.
**Priority:** Control first; ambient voice and always-on background operation later.

## Product acceptance rule

Do not ask the owner to download or install a candidate APK merely because it compiles. A release candidate must first pass the relevant automated tests and a capability checklist that demonstrates the requested user-visible workflows. Physical-device acceptance remains mandatory before claiming a workflow works on the target phone.

The initial user-facing acceptance workflows are:

1. **Spotify playback:** "Open Spotify and play Lucid Dreams by Juice WRLD." Amiki must resolve the intended app and track, request playback through a supported Spotify/Android integration, and verify an observable playback state. Opening Spotify or displaying search results is not success.
2. **WhatsApp message:** "Open WhatsApp and text Joshua: Are you gonna be home at ten?" Amiki must resolve the intended contact without guessing, show the recipient and exact message, require fresh owner approval before sending, then verify the strongest outcome the integration can actually observe. Opening a prefilled compose screen is not proof that a message was sent or delivered.

These are acceptance tests for the generic operator architecture, not the only apps or workflows Amiki should support.

## Target architecture

### 1. Intent and planning layer (Core)

- Convert natural language into a structured task with a declared goal, target app, typed steps, required scopes, risk class, preconditions, and success criteria.
- Never emit arbitrary shell commands, raw JavaScript, arbitrary component names, or unrestricted intent URIs as executable instructions.
- Ask a clarification when identity or target resolution is ambiguous (for example, multiple contacts named Joshua or multiple tracks with similar titles).
- Return a task plan that Android can validate independently. A model-generated plan is untrusted input.

### 2. Native Device Operator (Android)

One native execution boundary owns all phone-side actions. It contains:

- **Capability registry:** lists only implemented actions and their prerequisites; no advertised placeholders.
- **App adapters:** app-specific integrations (for example, Spotify and WhatsApp) behind stable typed interfaces.
- **UI operator:** optional AccessibilityService-based observation and interaction for workflows without a supported app API. It must be separately opt-in, clearly disclosed, user-revocable, and limited to explicit tasks.
- **Task controller:** state machine for queued, observing, awaiting-owner-approval, executing, verifying, completed, failed, cancelled, and needs-owner-input states.
- **Task journal:** bounded, privacy-conscious checkpoints so an interrupted multi-step task can resume only after revalidation.
- **Outcome verifier:** checks a fresh observable result; distinguishes "intent launched", "compose screen opened", "send action accepted", "message sent", and "delivery/read receipt" rather than conflating them.
- **STOP:** a persistent, immediate cancellation path. Cancellation prevents new steps; it cannot promise rollback of an external side effect already accepted by another app.

### 3. Observation and interaction safety

- Screen hierarchy, OCR text, notifications, web pages, and content from other apps are untrusted data. They may describe the task but cannot change Amiki's policy or authorize actions.
- Every UI action is bound to a fresh observation and a semantic target. Reject stale screen snapshots, missing targets, changed app identity, and ambiguous controls.
- Do not use blind coordinate tapping as the default interaction method. Do not silently bypass app security, biometric gates, CAPTCHAs, or Android permission prompts.
- Sensitive fields and screens (passwords, payment credentials, authentication codes, private health/financial content) are excluded from generic observation and automation unless a separate narrowly scoped design explicitly authorizes them.
- Before a consequential side effect, show a concise action preview and require owner approval. A WhatsApp send approval names the resolved recipient and displays the exact message body.
- A prior approval is not blanket permission for later tasks. Revalidate the target and approval at execution time.
- When outcome is uncertain, stop and report uncertainty; do not blindly retry a send, call, purchase, delete, or other non-idempotent action.

### 4. Authorization and security boundaries

- UOSA authenticates the owner and grants narrow device scopes. L1 session presence is not blanket permission to operate every app.
- Require transaction-bound elevation (L2) for actions classified as high impact by the security policy. Local confirmation and UOSA authorization are complementary, not interchangeable.
- Cipher provides audit and core-protection boundaries. Cipher X remains a separate future defensive engine; it is not granted broad control over app interaction by this work.
- All permission requests must explain why the capability is needed, what data is accessed, and whether it is used locally or transmitted.
- No root requirement, hidden Android APIs, permission bypasses, or silent privilege escalation.

## Current implementation checkpoint (2026-11-17)

The current branch contains:
- A pure-Java task lifecycle controller with unit tests; it is not yet the full native operator runtime.
- Natural-language planner work in the Core repository for the two acceptance phrases.
- A Spotify search handoff (opens Spotify search only; no automated selection, playback, or playback verification).
- A WhatsApp contact-picker handoff and exact-message preview (opens a prefilled compose screen only; the owner must still press Send; no send/delivery verification).
- No AccessibilityService screen observation/interaction yet.

These are intermediate slices, not acceptance completion. The owner must not be asked to install this branch as a finished AFDI release.

## Universal app operation — the primary AFDI target

The primary interaction model is a **generic, reusable Android UI operator**, not a growing list of app-specific integrations. Spotify and WhatsApp are representative acceptance scenarios only. They do not define or limit the supported app universe.

The operator should work with any installed app whose visible interface and Android security model permit the required interaction. Example coverage includes YouTube search, Chrome navigation and research, Instagram navigation, Gallery selection, Maps searches, Spotify playback flows, and WhatsApp communication flows.

The generic operator consists of:

1. **App discovery and launch:** resolve an installed launchable app by package identity, disambiguate labels, and launch through public Android APIs.
2. **Screen observer:** an explicitly enabled and revocable AccessibilityService obtains a bounded semantic snapshot of the foreground UI (window/package identity, visible node text or descriptions where permitted, roles, bounds, clickability, scrollability, and input hints). Sensitive values are redacted or excluded.
3. **Target resolver:** select controls by semantic role, label, hierarchy and current screen context. Coordinates are a last-resort, uncertainty-gated fallback, never the default.
4. **Interaction engine:** perform typed actions (tap, text entry, scroll, swipe, back, focus) against freshly revalidated nodes. It must reject stale nodes, changed foreground packages, ambiguous targets, and unsupported actions.
5. **Task runtime:** drive the existing task state machine, enforce per-step policy and approval, checkpoint resumable progress, and expose an immediate STOP.
6. **Outcome verifier:** take a fresh observation and check task-specific success criteria. An attempted tap or opened screen is not task completion.
7. **Capability and policy gateway:** validate every action against declared capability, Android permission/role, UOSA scope, risk class, and owner approval before dispatch; record bounded receipts for Cipher audit.

Accessibility is not unrestricted access to another app's internals. It can only observe and operate UI that Android and the target app expose to the accessibility framework. Secure windows, authentication challenges, CAPTCHAs, protected content, app-specific restrictions, and OEM behavior may prevent automation. Amiki must stop and explain such limits rather than bypass them.

App-specific adapters remain optional optimizations when an official, stable API provides a more reliable capability. They must sit behind the same typed dispatcher, authorization policy, task runtime, and outcome-verification contract. They are not prerequisites for generic UI operation and must not become a hard-coded allowlist of supported apps.

### AccessibilityService privacy and safety requirements

- The owner explicitly enables the service in Android Settings after a clear in-app explanation; the service is revocable at any time.
- The service is task-bound: no ambient browsing, continuous content collection, or automatic actions outside an active owner-requested task.
- Observe only the foreground app/window needed for the active step; do not persist full screen trees or transmit raw screen content to Core by default.
- Exclude password fields, secure windows, authentication codes, payment credentials, and other sensitive surfaces from generic observation and action.
- UI text, webpages, notifications, OCR output and accessibility node content are untrusted data, never instructions or authorization.
- Require fresh observation immediately before each action; discard snapshots after package/window changes or material UI updates.
- Consequential actions (send, post, purchase, call, delete, share, account/security changes) require a specific preview and fresh owner approval immediately before execution.
- STOP prevents new actions; it cannot undo a side effect already accepted by another app.
- No root, hidden APIs, permission bypass, silent escalation, or attempts to defeat an app's security controls.

### Spotify

- Investigate the currently supported Spotify Android integration options and their SDK/API constraints before selecting an adapter.
- Resolve track identity using artist + title and disambiguate remixes/live versions.
- Use supported playback control where available. A spotify search/deep link may open Spotify but must not be reported as playback success.
- Verify playback using an authoritative supported playback state or an explicitly documented weaker observation. If verification is unavailable, report "opened" or "play request submitted", not "playing".
- Respect Spotify login, account, network, app-version, and regional restrictions.

### WhatsApp

- Resolve Joshua through an owner-visible contact picker or a narrowly scoped contacts lookup; do not guess by first name alone.
- Show the selected contact identity/number and exact message before sending.
- Prefer supported Android intents/deep links for opening the correct conversation and pre-filling the text.
- Sending through a compose intent alone is not supported proof of send. If automated send is later implemented through the UI operator, require explicit per-message approval immediately before the send control is activated.
- Report only the strongest verified state (compose opened, send tapped, sent, delivered, read). Do not claim delivery/read without evidence.

## System-level integration

Add system capabilities one at a time through public Android APIs and supported roles/permissions. Each action must specify:
- Android API/OEM support range;
- required permission or role and its revocation behavior;
- whether it changes device state;
- approval/elevation policy;
- read-back verification;
- failure and rollback behavior.

If Android 10 or the Infinix firmware does not expose a capability to an ordinary app, Amiki must say so. Do not promise universal control over protected settings or other apps.

## Background operation and voice

Background invocation is a **release requirement**, not an optional post-release feature. The owner must be able to invoke Amiki from outside the main Amiki screen, and an authorized task must remain bounded, visible, stoppable, and auditable when the main activity is closed.

### Implemented assistant-entry foundation (2026-11-17)

- The Android manifest exposes an explicit `ACTION_ASSIST` entry point and declares `MobileShellActivity` as single-top for assistant handoff.
- The Device screen offers the API-29+ `ROLE_ASSISTANT` request flow through Android's public `RoleManager`.
- After the owner invokes Amiki through the device's configured assistant gesture, Android speech recognition captures one command and passes it into the existing Core chat/device-planning route.
- The handoff does not create a parallel action executor. Existing Core planning and local approval remain in the path.
- The speech command is bounded and rejected when empty, oversized, or containing unsupported control characters.
- The assistant role request explains that this slice does **not** enable ambient listening or an always-listening “Amiki” wake word.
- Natural-language `open <installed app label>` commands now resolve against the current on-device MAIN/LAUNCHER inventory. Resolution requires an exact display-name match; duplicates require an explicit choice; no match is reported without launching anything.
- Before launch, Amiki shows the selected label and package and requires local owner confirmation. The package is re-checked as launchable immediately before the launch request. This opens the app only; it does not inspect or control its content.
- The current launcher-confirmation dialog is a narrow, local owner-approval gate for opening an app; it is **not yet** the central ACG dispatcher with per-action UOSA session validation and Cipher audit. The launcher path must be moved behind that central policy/audit boundary before owner release. It does not authorize accessibility actions or grant blanket control of the opened app.
- Compound requests such as `open Spotify and play Lucid Dreams` are deliberately not misclassified as an app name by the local parser; they remain for the Core planner. Playback and in-app navigation are not implemented by this slice.

This is a system-assistant **gesture invocation** foundation. It is not evidence that an always-on hotword works on the Infinix HOT 9.

### Remaining background-voice release work

- Implement and validate a genuine opt-in local wake-word path (for example, an on-device keyword detector). Do not send ambient standby audio to Core or a cloud model.
- Determine whether the target Infinix firmware exposes usable third-party hotword support through the selected VoiceInteractionService / assistant role. A system assistant gesture and an “Amiki” spoken hotword are separate invocation mechanisms.
- Test false accepts/rejects, screen-off behavior, battery impact, microphone privacy indicators, user revocation, force-stop, reboot, and OEM battery management on Android 10/API 29.
- Use a user-visible foreground service only for a justified task runtime; provide a persistent notification and immediate STOP. Do not launch microphone capture silently.
- On newer Android versions, enforce the relevant foreground-service and microphone permission/type restrictions for the app's target SDK.
- Background tasks must use the same typed native dispatcher, UOSA authorization, Cipher audit, task journal, and outcome verifier as foreground tasks.

No always-on microphone is enabled by this assistant-entry slice. Ambient voice remains opt-in and must visibly indicate listening.

## Build sequence and release gates

1. **Unify action contract and dispatcher:** typed action definitions, validated arguments, capability registry, one native executor, task states, cancellation, and structured receipts. Unit tests prove that unsupported actions cannot execute.
2. **Build the universal UI operator:** explicit AccessibilityService enablement, bounded foreground observation, semantic target resolution, typed interactions, stale-node rejection, and privacy protections.
3. **Connect task runtime and verification:** wire the task controller to the actual Android service/dispatcher; add bounded checkpoints, STOP, safe recovery, and observable outcome receipts. Add instrumentation tests with controlled test screens.
4. **Cross-app acceptance matrix:** validate generic workflows in YouTube, Chrome, Instagram, Gallery, Maps, Spotify, WhatsApp, and additional installed apps. Spotify and WhatsApp are examples, not exclusive targets.
5. **Optional app adapters and system capabilities:** add official app APIs or public Android system APIs only where they materially improve reliability; keep them behind the same dispatcher and policy gateway.
6. **Security and production validation:** UOSA scope enforcement, L2 for high impact, Cipher audit integration, prompt-injection tests, privacy review, resource/lifecycle tests, and failure recovery.
7. **Background and voice:** only after foreground cross-app operation is reliable and resource/lifecycle tests pass.

A release candidate intended for the owner must include:
- Android unit tests and instrumentation/UI tests;
- CI debug APK build;
- APK signing continuity check against the installed Amiki identity (never ask the owner to uninstall the existing app as a signing workaround);
- physical-device test plan and a capability matrix for Android 10/API 29;
- verified Spotify and WhatsApp workflow evidence, not merely app launch;
- no unresolved critical security or data-loss issue.

## Explicit honesty rules

- Source code present is not feature completion.
- CI green proves build/test jobs passed; it does not prove the feature works on the phone.
- Opening an app is not completing a task inside that app.
- A compose screen is not a sent message.
- A playback deep link is not proof that audio is playing.
- Never ask the owner to install an APK that does not meet the agreed release gates.


## Implementation checkpoint — 2026-11-17

The first native observation slice is now present on the AFDI development branch:

- AccessibilityOperatorService is registered with the Android accessibility framework and is inert until explicitly armed from the Amiki Device screen.
- Owner-facing setup disclosure opens Android Accessibility settings; the owner can revoke the service there.
- Each observation session receives a fresh DeviceTaskController and begins in OBSERVING. STOP clears the in-memory snapshot and cancels the task.
- The service captures a bounded foreground accessibility tree (maximum 80 nodes / depth 12), stores the latest snapshot only in process memory, and performs no tap/type/scroll action.
- Password and editable-field text/descriptions are redacted. Other visible UI text can still include sensitive information; therefore snapshots are task-scoped, ephemeral, never uploaded or persisted by this slice, and the owner must stop observation before entering authentication or payment flows.
- UiTargetResolver accepts only a unique exact label match on a clickable, non-editable, non-password node. It is not yet connected to an action executor.

This checkpoint is an observation prototype, not a finished universal operator. Native action dispatch, fresh-node revalidation, UOSA/Cipher integration, outcome verification, instrumentation coverage, and physical-device acceptance remain required.
