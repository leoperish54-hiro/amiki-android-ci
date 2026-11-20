# AFDI / OpenPhoneUse Architecture Adaptation Audit

**Audit date:** 2026-11-20  
**Amiki source branch:** `feat/afdi-uosa-action-dispatcher`  
**Amiki target:** Android 10 / API 29, Infinix HOT 9 (3 GB RAM)  
**Reference:** [OpenPhoneUse](https://github.com/joungminsung/OpenPhoneUse), MIT, audited upstream revision `3505c0f3a69ba8b1131d2cf09645cb9b86e07179`.

This is a focused source-level design and implementation review. It is not a security certification or physical-device acceptance report.

## Executive result

Amiki already has a more specialised approval and audit boundary than a direct transplant of OpenPhoneUse would provide. OpenPhoneUse's useful patterns—typed tools, confirmation policy, fresh accessibility observations, bounded execution and outcome checks—are already represented in Amiki's AFDI implementation, with additional UOSA biometric and Cipher transaction binding.

The correct adaptation is therefore selective hardening, not importing OpenPhoneUse's Android application runtime. Its current upstream README declares Android 11+; Amiki's first target remains Android 10/API 29. Raising the minimum SDK or porting API-30-dependent code into the Android 10 build would be a regression.

## Relevant Amiki implementation already present

| OpenPhoneUse concept | Amiki implementation |
|---|---|
| Typed tool registry / narrow action contracts | `UiActionDispatcher.ActionType`: TAP, TYPE_TEXT, SCROLL_FORWARD, SCROLL_BACKWARD, BACK |
| Confirm policy before consequential UI work | `UiActionApprovalActivity`, explicit approval, fresh BIOMETRIC_STRONG signature and one-use UOSA L2 `device.execute` grant |
| Bind approval to the exact action | Canonical request body and transaction hash include task/action identity, app package, window, snapshot fingerprint, target fingerprint, payload digest and postcondition |
| Reject stale screen targets | Fresh accessibility window and snapshot are captured again after approval; current target is resolved and fingerprint-checked before action |
| Verify the effect, not only tool acceptance | Semantic visible/absent postconditions, exact text readback, bounded rechecks, and a separate Cipher outcome receipt |
| Bounded agent loop and Stop | `DeviceTaskOrchestrator`, 15-action / two-minute limit, explicit STOP, journal recovery and no automatic replay after uncertain outcomes |
| Untrusted screen data | The model receives a bounded visible accessibility snapshot as reference data; the model cannot grant authority or bypass local validation |

The Android implementation uses the platform AccessibilityService and APIs already compatible with the project's Android 10 target. No upstream OpenPhoneUse application source or Android-11-only runtime was copied into Amiki.

## Defect found and corrected

### Semantic postcondition did not bind its package identity

Before this patch, `UiActionDispatcher.Request.matchesPostcondition()` accepted an expected visible/absent label based on the before/after trees, but did not itself require the after-snapshot package to equal the package bound into the request.

The current `AccessibilityOperatorService.verifyPending()` already checks the package before calling this method. That caller-side guard made the immediate execution path safer, but the dispatcher contract itself was weaker than its intended invariant and could be misused by another caller later.

**Correction:** `matchesPostcondition()` now also requires `request.packageName.equals(after.packageName)`. It still permits a same-app window transition, which can be legitimate when a dialog or activity changes.

**Regression test:** `semanticPostconditionRejectsDifferentForegroundApp` supplies the expected label from a different package and asserts that verification fails.

## Important remaining risk boundary

Amiki currently uses a strong, uniform owner approval for each typed UI action. The approval screen shows the app, exact target control or exact text payload, expected UI transition, window and snapshot age. It does not yet provide a structured, contextual confirmation summary for every high-impact semantic control.

For example, approving a generic tap on a button labelled “Send” is not, by itself, proof that the owner saw and approved the exact WhatsApp recipient and message body. The same concern applies to “Pay”, “Transfer”, “Delete”, “Purchase”, “Publish” and similar commit controls.

Therefore:
- Do not treat a generic tap approval as a completed WhatsApp-send, payment, transfer or destructive-action feature.
- Before enabling these workflows, introduce a local semantic risk policy and a dedicated contextual preview that binds the recipient/destination, exact content or amount, target app and final commit control to the one-use approval.
- If that context is missing, ambiguous, stale or unavailable from the accessibility tree, require owner input and do not execute the commit action.
- A compose screen is not proof of sending; an accepted tap is not proof of delivery or success.

A first fail-closed guard is now implemented in `UiActionRiskPolicy`: tap proposals whose visible labels begin with common commit/destructive verbs (for example Send, Submit, Pay, Transfer, Delete, Publish, Confirm and Share) pause for owner review before any tap is queued. Regression tests cover the known labels, ordinary navigation labels, case/whitespace normalization and missing-label fail-closed behaviour. This is a conservative label heuristic, not a complete semantic security classifier; it can produce false positives and cannot identify every unlabeled or localized commit control. It does not yet replace the dedicated contextual preview required to automate a send/payment/transfer safely.

## Data flow and privacy boundary

The AFDI design document records that visible labels and control metadata from the active app window may be sent to Amiki Core and its configured model provider during an explicitly started task. Editable/password values are redacted. Raw screen trees, raw typed payloads and message bodies are not persisted in the task journal or Cipher audit. The owner must opt in to AccessibilityService access and can revoke it in Android settings.

This data path must remain visible and explicit. Third-party screen text is untrusted reference data, never executable instruction or authority.

## Acceptance gate

- Keep Android 10 / API 29 compatibility. Do not raise `minSdk` to match OpenPhoneUse.
- Preserve exact one-action approvals, UOSA L2 transaction binding, biometric signature, Cipher authorization receipt and distinct outcome receipt.
- Every consequential step must use a fresh app/window/snapshot and exact target fingerprint.
- Verify outcomes from a fresh app snapshot; never infer success from Android accepting an accessibility action.
- Unknown/unverified outcomes must stop the task and require owner inspection; no automatic replay.
- Add high-impact semantic-risk and contextual-preview coverage before automating send, pay, transfer, delete, purchase or publish commits.
- Run CI only on `amiki-android-ci` and compare changed file blob hashes with the private canonical branch before accepting the run as source evidence.
- Physical XOS / Android 10 acceptance remains outstanding. A green emulator/unit build does not certify phone behaviour.
