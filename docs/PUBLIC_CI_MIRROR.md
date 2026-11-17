# Public Android CI mirror

This repository is a public build-and-test mirror of the private Amiki Android repository.

## Source of truth

- Private source repository: `leoperish54-hiro/amiki-android`
- Public CI mirror: `leoperish54-hiro/amiki-android-ci`

The private repository remains authoritative. Changes made here must be reviewed and deliberately synchronized back to the private repository; do not treat this mirror as the canonical development branch.

## Security boundaries

- No production signing keystore, signing password, owner token, API credential, or private release credential belongs here.
- CI uses GitHub's read-only `GITHUB_TOKEN` permissions.
- CI builds a debug APK and retains it as a short-lived workflow artifact.
- CI does not publish GitHub Releases.
- Debug APK signing is ephemeral and is not suitable for in-place updates over a differently signed installation.

## CI

The workflow runs local JVM unit tests and builds the debug APK on pushes, pull requests, and manual dispatches.

The mirror is a build surface, not an authorization to publish user data, runtime state, local databases, or production secrets.

## Asset note

The large original anime portrait PNG is not included in this mirror because the GitHub connector could not transfer that binary blob. To keep the public CI build reproducible, the mirror currently maps the portrait resource name to the existing launcher-art WebP. This is a CI-only visual fallback and must not be copied back into the private source repository as a replacement for the original portrait asset.
