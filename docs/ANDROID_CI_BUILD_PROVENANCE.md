# Android CI build provenance

This commit intentionally triggers the public Android CI workflow on `main`.

## Canonical source reference
- Canonical repository: `leoperish54-hiro/amiki-android`
- Canonical main commit: `19a2af748b18bed36a839e215507583110741851`
- Integrated feature branch commit: `b3917cf0840056e540c814eabd2986fd278fb924`

## Public CI source
- CI repository: `leoperish54-hiro/amiki-android-ci`
- CI source baseline before this trigger: `b6956a3afddc0a854067583c9633cb5d91ed5c74`

## Reconciliation note
The public CI mirror is a curated build mirror, not a byte-for-byte repository clone. A recursive Git tree comparison found:
- 133 shared file blobs identical.
- One canonical-only image asset.
- Six mirror-only files (including mirror-specific CI workflows and documentation).
- Eight shared paths with differing blobs: the Android CI workflow, wake-word download policy, API endpoint registry, Learning Review and UOSA approval screens, and three corresponding test files.

The mirror-specific workflow and additional mirror-side safeguards are intentionally retained. This build therefore tests the current curated public CI mirror `main`, not a claim of byte-identical source to canonical `main`.

No Cipher source is included in this build.
