# Amiki Full Device Integration — Android Architecture

**Status:** Initial implementation slice: launcher-app discovery and confirmed app launch.
**Target device:** Infinix HOT 9, Android 10 / API 29, 3 GB RAM.
**Execution rule:** Inspect → Build → Test → Fix → Verify green.

## 1. Design references and adaptation

This work is inspired by, not copied from, the JARVIS Android operator architecture:

- JARVIS architecture: https://github.com/AnaaySampat/jarvis/blob/main/docs/ARCHITECTURE.md
  - One tool-advertisement point and one dispatch/execution point.
  - A native Android phone-operator boundary.
  - Typed action inputs, visible task/action states, and a task journal for resumable work.
- JARVIS security review: https://github.com/AnaaySampat/jarvis/blob/main/docs/SECURITY.md
  - Observed screen/page content is untrusted data.
  - Consequential actions need a human approval boundary.
  - Stale observations must not authorize later actions.
  - Uncertain side effects must not be blindly retried.

The useful architectural lesson is not to give the model unrestricted Android access. It is to make the path from intent to side effect explicit, typed, observable, interruptible, and independently enforced by native code.

## 2. Amiki ownership boundaries

- Core: interprets requests, selects a declared device capability, produces a typed action plan, and remains authoritative for server-side policy.
- Android Device Capability Gateway: reads capabilities available from this physical Android installation, discovers launchable apps locally, checks the current Android state immediately before a launch, and invokes public Android APIs.
- UOSA: authenticates protected Core API requests and enforces device scopes. The L1 profile already requests device.read, device.plan, and device.execute; higher-risk operations must use their own transaction-bound L2 authorization flow.
- Android owner: gives the final local approval for each app launch in this initial slice.
- Cipher: retains the security/audit boundary. This slice does not add a parallel Cipher execution path or grant Cipher X authority.

## 3. Adapted JARVIS patterns

| JARVIS pattern | Amiki adaptation |
|---|---|
| One tool advertisement point and one dispatcher | The AFDI target is one typed device-action registry and one native dispatcher. Both the installed-app picker and the backend-planned open_app path now converge on DeviceCapabilityGateway.launchConfirmedApp after their respective local approval step. |
| Native Android operator boundary | Android owns package discovery and app launching; Core does not pretend it can inspect the phone's installed-app inventory from the server. |
| Structured action inputs | App launch is selected by a package identity obtained from Android, not by executing arbitrary shell text or a model-generated intent URI. |
| Human-in-the-loop approval | A local confirmation names the app and package before the launch call. |
| Task/action evidence | The UI reports that Android accepted the launch request; it does not claim that the launched app completed an internal task. |
| Stop and recovery semantics | Multi-step accessibility control is deferred until an interruptible task controller, action journal, and outcome-verification path exist. |

## 4. Initial slice: local app discovery and launch

- Query only ACTION_MAIN + CATEGORY_LAUNCHER activities.
- Use a matching manifest queries declaration for Android 11+ package visibility. Do not request QUERY_ALL_PACKAGES.
- Keep the app inventory in memory on the device. Do not persist it or upload it to Core.
- De-duplicate by package name and sort by app label.
- Resolve labels exactly. Partial matches are rejected; duplicate labels remain ambiguous and must be disambiguated by the owner.
- Re-check that the selected package is still launcher-visible immediately before launch.
- Show the app label and package in a local confirmation dialog. Cancel means no launch.
- Use PackageManager.getLaunchIntentForPackage and Context.startActivity; never construct shell commands or accept arbitrary URI schemes.
- A successful startActivity call is an accepted launch request, not proof that an app completed an action.

The gateway's capability snapshot reports Android hardware-feature declarations and existing runtime-permission grants. It does not request permissions and does not claim that a feature's service or sensor is currently operational.

## 5. Later AFDI stages

1. Unified native device-action contract: consolidate current chat-local actions and backend-planned actions behind one action specification and native dispatcher. Registry and executor must be tested together so no declared action is a placeholder.
2. Broader Android controls: introduce one capability at a time, with precise permission requests, explicit authorization scopes, local confirmation where the action changes state, and a read-back or bounded outcome receipt.
3. Accessibility-based app interaction: only after a separately reviewed design. Screen text and other app content are untrusted data, never agent instructions. Bind actions to a fresh observation, reject stale snapshots, protect sensitive apps/fields, and provide a visible STOP control and durable task checkpoints before multi-step execution.
4. System-level integrations: use public Android APIs, intents, and supported role/permission flows first. Report OEM/API restrictions rather than claiming privileged control.
5. Background/voice operation: defer until foreground control is reliable, interruptible, and benchmarked on the target device. No always-on microphone or broad background automation is introduced by this slice.

## 6. Explicit non-goals for this change

- No AccessibilityService, screen scraping, coordinate tapping, screen recording, or overlay permission.
- No background listening or wake-word service.
- No arbitrary shell, root, hidden API, or device-admin control.
- No QUERY_ALL_PACKAGES; no upload of the installed-app list.
- No automatic messaging, calling, purchasing, deleting, or settings changes.
- No claim of physical-device verification until the APK is installed and exercised on the Infinix HOT 9.

## 7. Acceptance criteria

- Unit tests prove exact label matching, case/whitespace normalization, rejection of partial matches, and preservation of ambiguous matches.
- CI runs Android unit tests and builds the debug APK.
- On the physical device, the Installed Apps screen returns real launcher apps, refreshes after an app is installed/removed, and keeps inventory local.
- Opening an app always shows the confirmation dialog. Cancel does nothing; confirm launches only the selected, still-visible package.
- Android 10 / API 29 and Android 11+ package-visibility behavior are verified separately.
- UI reports launch request accepted rather than claiming an app's internal operation succeeded.
