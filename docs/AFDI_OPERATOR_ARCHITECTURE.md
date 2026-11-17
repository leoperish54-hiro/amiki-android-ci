# AFDI Cross-App Operator Architecture

**Status:** Approved direction; implementation is staged. This document does not claim that any interaction feature is already implemented.
**Target device:** Infinix HOT 9, Android 10 / API 29, 3 GB RAM.
**Priority:** Control first; ambient voice and always-on background operation later.

## Product acceptance rule

Do not ask the owner to download or install a candidate APK merely because it compiles. A release candidate must first pass automated tests and a capability checklist demonstrating the requested workflows. Physical-device acceptance is mandatory before claiming a workflow works on the target phone.

Initial acceptance workflows:
1. **Spotify:** "Open Spotify and play Lucid Dreams by Juice WRLD." Resolve the correct track, request playback through a supported integration, and verify playback. Opening Spotify or search results is not success.
2. **WhatsApp:** "Open WhatsApp and text Joshua: Are you gonna be home at ten?" Resolve the intended contact without guessing, show recipient and exact message, require fresh approval before sending, and verify the strongest observable outcome. A prefilled compose screen is not proof of sending or delivery.

## Target architecture

### Core planner
- Produce structured tasks: goal, target app, typed steps, scopes, risk class, preconditions, and success criteria.
- Never emit arbitrary shell commands, raw JavaScript, or unrestricted intent URIs as executable instructions.
- Ask when contact or track resolution is ambiguous.
- Treat model-generated plans as untrusted input; Android validates them independently.

### Native Device Operator
One native execution boundary owns phone-side actions:
- Capability registry lists implemented actions only; no placeholders.
- Typed app adapters for supported app integrations.
- Optional AccessibilityService operator only when separately opted-in, disclosed, revocable, and limited to explicit tasks.
- Task controller states: queued, observing, awaiting approval, executing, verifying, completed, failed, cancelled, needs owner input.
- Bounded privacy-conscious task journal; resume only after revalidation.
- Outcome verifier distinguishes app launch, compose opened, send accepted, sent, delivered, read, and playback.
- Immediate STOP prevents future steps; it cannot undo an external side effect already accepted.

### Observation and safety
- Screen hierarchy, OCR, notifications, pages, and other-app content are untrusted data, never instructions or authorization.
- Bind every UI action to a fresh observation and semantic target. Reject stale snapshots, missing targets, changed app identity, and ambiguous controls.
- No blind coordinate tapping by default; no bypassing app security, biometrics, CAPTCHAs, or Android permission prompts.
- Exclude passwords, payment credentials, authentication codes, and sensitive screens from generic automation unless separately authorized.
- Show consequential action previews and require owner approval. WhatsApp approval displays the resolved recipient and exact message.
- Prior approval is not blanket permission. Revalidate target and approval at execution.
- On uncertain outcomes, stop; do not blindly retry non-idempotent actions.

### Authorization
- UOSA grants narrow scopes; L1 is not blanket permission.
- L2 transaction-bound elevation applies to high-impact actions. Local approval and UOSA are complementary.
- Cipher remains the audit/core-protection boundary. Cipher X is separate future work.
- Explain each permission, data access, and transmission path.
- No root, hidden APIs, permission bypass, or silent privilege escalation.

## App integration strategy

Prefer stable supported interfaces; use generic UI interaction only where no suitable app API exists.

### Spotify
- Research currently supported Android integration options and constraints before selecting an adapter.
- Resolve artist + title and disambiguate versions.
- A spotify search/deep link may open Spotify but is not playback success.
- Verify through an authoritative playback state or report the weaker observable state honestly.
- Respect login, account, network, app-version, and regional restrictions.

### WhatsApp
- Resolve Joshua through an owner-visible contact picker or narrowly scoped contacts lookup; never guess by first name.
- Show contact identity/number and exact text before sending.
- Prefer supported intents/deep links to open the correct chat and prefill text.
- Compose intents do not prove send. Any future automated send requires explicit per-message approval immediately before activation.
- Report only verified state; do not claim delivery/read without evidence.

## System, background, and voice
- Add system capabilities individually through public Android APIs and supported roles/permissions. Document API/OEM support, permissions, state changes, approval, read-back, and rollback.
- If Android 10 or Infinix firmware does not expose a capability, say so.
- Defer background/voice until foreground operation is reliable: visible bounded foreground work, explicit start/stop, notification, battery budget, recovery, opt-in listening indicator. No always-on microphone by default.

## Build sequence and release gates
1. Typed action contract, capability registry, native dispatcher, task states, cancellation, receipts.
2. Fresh screen observations, semantic targets, stale-observation rejection, bounded journal, STOP, UI tests.
3. Spotify and WhatsApp adapters with workflow and failure tests.
4. Scoped/verifiable system capabilities.
5. Background and voice after foreground reliability.
6. UOSA/L2, Cipher audit, prompt-injection, privacy, and recovery validation.

Candidate APK gates: unit + instrumentation tests; CI build; signing continuity against installed Amiki (never uninstall as workaround); physical Android 10/API29 test plan; verified Spotify/WhatsApp workflow evidence; no unresolved critical security/data-loss issue.

## Honesty rules
- Source code is not feature completion.
- CI green is not physical-device proof.
- Opening an app is not completing a task.
- A compose screen is not a sent message.
- A playback deep link is not proof audio is playing.
- Never ask the owner to install an APK that fails the agreed gates.
