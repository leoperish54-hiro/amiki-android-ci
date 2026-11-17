# Private Core and Cipher CI

This public repository contains only the workflow harness. It does not vendor or mirror Amiki Core or Cipher source.

## One-time repository setup

Create a GitHub fine-grained personal access token with:

- Resource owner: `leoperish54-hiro`
- Repository access: **Only select repositories**
- Selected repositories: `Amiki-` and `Cipher`
- Repository permissions: **Contents: Read-only**
- No Actions, Issues, Administration, or write permissions

Add that token as an Actions repository secret in this public repository:

- Settings → Secrets and variables → Actions → New repository secret
- Name: `AMIKI_PRIVATE_REPO_READ_TOKEN`
- Value: the fine-grained token

Do not put the token in source files, workflow inputs, issue comments, or logs. Do not use a classic token with broad account access.

## Running the tests

After this workflow is merged to `main` and the secret is configured:

1. Open Actions → **Private Core and Cipher contract tests**.
2. Choose **Run workflow**.
3. Run it from `main`.

The workflow checks out the exact reviewed private commits pinned in the workflow, with checkout credentials not persisted in either working tree. It runs:

- Core: `test_device_ui_actions.py` and the canonical UOSA transaction-binding test.
- Cipher: `test_audit.py` and `test_service.py`.

Only test output and the two tested commit IDs appear in the public Actions run. The private source is checked out only into the ephemeral runner workspace and is not uploaded as an artifact.

## Updating tested revisions

When either private PR receives a new commit, update the corresponding pinned SHA in `.github/workflows/private-core-cipher.yml` after reviewing that commit. This makes each run reproducible and prevents a moving branch from silently changing what was tested.

## Security boundary

This workflow is `workflow_dispatch` only. It does not run on pull requests or pushes, so untrusted pull-request code cannot execute with the private-repository credential. Keep the workflow on the protected/default `main` branch and keep the token read-only and limited to the two repositories.
