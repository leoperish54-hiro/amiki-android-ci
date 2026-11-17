# ALE Android Learning Review

The Android client's **Settings → Learning Review** screen connects to the
Amiki backend's owner-authenticated ALE review queue.

## Capabilities

- Lists pending knowledge candidates and correction experiences.
- Shows candidate content, source, creation time, confidence metadata, and retained
  evidence/provenance. HTTPS URLs in evidence are tappable.
- Filters the queue by all items, knowledge, or correction experiences.
- Allows explicit approval of knowledge candidates only.
- Allows explicit rejection of either pending knowledge or correction experiences.
- Requires a confirmation dialog before every decision.
- Does not promote correction experiences to knowledge and does not update model
  weights.

The screen uses the backend contract documented in
`Amiki-/docs/ALE_LEARNING_REVIEW_API.md`:

- `GET /api/v1/learning/review-queue`
- `POST /api/v1/learning/review-queue/{record_id}/approve`
- `POST /api/v1/learning/review-queue/{record_id}/reject`

## Owner token handling

The owner token is not hardcoded and is not stored as plaintext in Android
preferences. `OwnerTokenVault` encrypts it with AES-GCM using an Android Keystore
key; only the ciphertext and IV are stored in app-private preferences. App backup
is disabled in the manifest.

Generate the token on the device that runs the backend, and configure
`AMIKI_OWNER_REVIEW_TOKEN` in the **environment of the actual API process**.
The backend requires at least 32 characters and fails closed when the setting is
missing or too weak. The token protects only the ALE review endpoints; it does not
authenticate the rest of the existing API.

Enter the same token in **Settings → Learning Review → Set / Change Token**.
The app never displays it after saving. Removing the token from the app does not
rotate or remove the backend token.

Do not commit the token, add it to application source/configuration, or paste it
into chat. If the API is reachable beyond the trusted local device, configure
HTTPS before using the review endpoints.

## Local transport

The Android client currently talks to the local Termux backend at
`http://127.0.0.1:8000`. The existing Android network security configuration
allows cleartext only to that loopback address and denies it elsewhere.
