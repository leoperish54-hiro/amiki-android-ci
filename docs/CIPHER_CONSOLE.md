# Amiki Android — Cipher Console

The Settings → Cipher Console screen is a read-only Android client for the Amiki-to-Cipher bridge.

## Current scope

- Calls the Amiki GET /api/v1/cipher/status endpoint to display sanitized Cipher health and audit-chain metadata.
- Allows bounded read-only inspection using an owner-registered root ID through POST /api/v1/cipher/inspect.
- Displays complete versus partial inspection status, scan counts, and at most 20 findings.
- Validates the read-only mode and requires action_execution_enabled=false and execution_performed=false.
- Sends root IDs only. The backend enforces its allowlist; Android never receives or stores the Cipher bearer token and never sends filesystem paths.
- Displays Watchtower, Active Defense, Emergency Isolation and Cipher X policy boundaries without implying unavailable capabilities are operational.

## Backend dependency

The Android client depends on the read-only bridge proposed in Amiki backend PR #100. Until that PR is merged and configured in the actual Termux API process, status and inspection requests report unavailable. This screen does not fall back to fabricated values or local guesses.

The bridge currently exposes health and inspection only. It does not provide a real telemetry feed, live threat detection, incident mutation, Governor authorization, credential management, containment, isolation or recovery execution.

## Security boundaries

- Android communicates only with the local Amiki API.
- CIPHER_API_TOKEN remains in the Amiki backend environment or private Termux token file.
- The root ID field accepts identifiers, not paths; the backend enforces the configured allowlist.
- Findings are metadata-only and bounded. The UI displays at most 20 findings and truncates file hashes for display.
- No control in this screen can approve, authorize or execute a defensive operation.
- A successful health response is not a security certification; Cipher remains a loopback read-only prototype.

## Device acceptance still required

After the Android PR and backend bridge are available, verify on the actual Termux device: configured and missing token cases, allowed and disallowed root IDs, complete and partial inspection, timeout, service restart, and that the API token never appears in Android traffic or app storage. Confirm rendering and touch behavior on Android 10 / the target handset.
