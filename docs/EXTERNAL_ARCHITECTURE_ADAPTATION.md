# External Architecture Adaptation Record

**Audit date:** 2026-11-20  
**Amiki target:** Android 10 / API 29, Infinix HOT 9, 3 GB RAM  
**Purpose:** selectively adapt tested engineering patterns without importing incompatible runtimes, unsafe defaults, or code whose licence does not permit reuse.

This is a source-level engineering audit, not a security certification or proof that any upstream project works on Amiki's target handset. Revisions below are pinned so later comparisons are reproducible.

## Reference projects and reuse boundaries

| Project | Audited revision | Licence at audit | Decision |
|---|---|---|---|
| [OpenPhoneUse](https://github.com/joungminsung/OpenPhoneUse) | `3505c0f3a69ba8b1131d2cf09645cb9b86e07179` | MIT | Selectively reusable with the MIT notice retained. Do not transplant the app/runtime wholesale: its current README declares Android 11+ while Amiki's target is Android 10/API 29. |
| [NIDO](https://github.com/Nido007-cmyk/Nido) | `f50d475e92ce1dc758c6e263083c34d3e5fc02fd` | MIT | Selectively reusable with the MIT notice retained. Its model-installation pipeline is a strong reference for staged downloads, classified failures, integrity verification, atomic promotion, and honest resume semantics. Its Expo/TypeScript app code is not directly compatible with Amiki's native Java layer. |
| [Jenny](https://github.com/flagdizero/jenny-android-ai-agent) | `905f149db5c2e6f84766bd44f95b20da62d7bdba` | AGPL-3.0 | Architecture study only for now. Directly incorporating or adapting its code may impose AGPL source-availability obligations on the covered work, including network use. Do not mix it into Amiki's private Cloud/backend without an explicit licence-compatibility decision. |
| [Sanna](https://github.com/sannabotdev/sannabotapp) | `7d84aa708fe3c56aa7202635abdaa442cc4781f3` | No licence file / GitHub licence metadata absent | No source-code copying. Study the agent-loop and accessibility sub-agent ideas; implement clean-room equivalents in Amiki. |
| [Naomi / Personal-AI-Assistant](https://github.com/mukeshchandu/Personal-AI-Assistant) | `5e5660016916b07e20b8d978dc04305653ad664c` | No licence file / GitHub licence metadata absent | No source-code copying. Study the offline voice and command-routing ideas; implement clean-room equivalents in Amiki. |

A public repository is not automatically open-source. Where no licence is supplied, the default is that copyright remains with the authors; architectural learning is not permission to copy source code.

## What Amiki should adopt

### 1. Android device operation — OpenPhoneUse

Useful patterns identified in the current source:
- A typed tool registry and a single execution boundary, rather than letting the model call arbitrary Android APIs.
- A separate confirmation/risk gate. A model's risk label can increase scrutiny but must never be able to lower the platform's mandatory confirmation requirements.
- Treat UI text, notifications, web content, and other-app data as untrusted input. Once a task has consumed external content, sending or storing derived content must be more restrictive.
- Resolve a target against a fresh accessibility observation before acting. Re-read the screen after actions and verify the requested end state.
- Keep an immediate Stop path and report partial completion honestly.

Amiki already has an opt-in AccessibilityOperatorService, a DeviceTaskController, and an AFDI architecture document. The next adaptation should strengthen typed action contracts, stale-observation rejection, per-action authorization, and outcome verification rather than replacing those foundations.

**Compatibility warning:** upstream OpenPhoneUse currently declares Android 11+; Amiki's first target is API 29. Any port must be checked against API 29 and must not silently raise Amiki's minimum SDK.

### 2. Model and asset installation — NIDO

Useful patterns identified in the current source:
- Classify failures as transient, permanent, or local-resource failures; do not label every failure as “network or storage”.
- Report actual received bytes and the HTTP status, not only a generic failure.
- Keep downloads in staging and promote only after exact-size and SHA-256 verification.
- Preserve a previously verified installation if a replacement download fails.
- Claim “resume” only when a validated partial file and valid resume state are actually retained.

**First Amiki adaptation in this branch:** the small Hey Amiki model downloader now retries bounded transient failures, records the final redirect host and byte count in diagnostics, shows actionable failure details in the settings screen, and continues to require pinned sizes and SHA-256 before promotion. It deliberately restarts a failed small asset from zero; it does not claim byte-range resume. Unit tests cover transient HTTP classification, non-retryable client errors, storage failures, bounded backoff, and safe diagnostics.

### 3. Learning and persistent memory — Jenny

Useful architecture ideas to study without importing AGPL code:
- Keep the conversation runner separate from memory storage and scheduled maintenance.
- Preserve raw experiences and provenance; distinguish observed facts, user-approved facts, and model-generated candidates.
- Use bounded consolidation cycles with checkpoints, budgets, and owner review.
- Avoid automatically turning third-party content or model guesses into durable owner facts.

For Amiki, this must connect to ALE's evidence, claims, and review queue. Existing owner-review controls remain the authority; autonomous consolidation must not bypass them.

### 4. Accessibility sub-agents and task orchestration — Sanna

Clean-room design ideas:
- Give a sub-agent a narrow task contract and limited tool scope.
- Refresh the accessibility tree after an action; do not continue using stale node references.
- Use explicit completion/failure states rather than relying on an assistant-generated narrative to end a task.
- Store app-specific hints as suggestions with provenance, not as unquestioned truth.

Sanna's current repository has no licence metadata, so no source files or copied code are brought into Amiki.

### 5. Voice and local commands — Naomi

Clean-room design ideas:
- Separate wake detection, speech-to-text, command routing, and speech output.
- Keep wake detection local and explicit opt-in; coordinate microphone ownership between wake detection and assistant STT.
- Route simple deterministic device commands locally before considering a model call.

Amiki already owns its wake-word direction using sherpa-onnx. Naomi's Vosk implementation is a study reference only; no code is copied. Google remains optional, not a mandatory voice dependency.

## Current adaptation sequence

1. Finish the Hey Amiki installer diagnostics/retry patch and run all tests/builds on the public Android CI mirror.
2. Adapt OpenPhoneUse's risk-gate and fresh-observation concepts into Amiki's existing AFDI action dispatcher, while retaining API 29 compatibility.
3. Audit ALE against Jenny's memory provenance and consolidation patterns without importing AGPL code.
4. Audit Amiki's voice lifecycle against Naomi's local-first orchestration ideas without copying unlicensed source.
5. Revisit NIDO's full install journal/resume design only if measured download failures justify its extra state complexity.

## Non-negotiable acceptance rules

- A compiled APK is not proof that a phone workflow works.
- Builds and CI runs occur only in the public mirror; the private repository remains canonical source.
- Compare Git blob SHAs for every mirrored source file before treating a public CI result as evidence for the private source.
- Keep model files pinned by exact size and SHA-256. Never load a partially downloaded or unverified model.
- No background microphone use without owner opt-in, visible disclosure, and an immediate stop control.
- No sending, deleting, paying, security changes, or other high-impact action without Amiki's own policy gate and required fresh owner approval.
- Do not claim a workflow works on Android 10 until it has passed automated checks and the target device test.
