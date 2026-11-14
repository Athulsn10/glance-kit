# Versioning and releases

## Version
`version.properties` → `VERSION_NAME=MAJOR.MINOR.PATCH`. versionCode =
MAJOR × 10000 + MINOR × 100 + PATCH (MINOR and PATCH < 100). MAJOR for redesigns, MINOR for
features, PATCH for fixes. Add `## [x.y.z] - YYYY-MM-DD` to `CHANGELOG.md`; the release
workflow uses that section as GitHub release notes. Play "What's new":
`distribution/whatsnew/whatsnew-en-US` (≤ 500 characters).

## Workflows (`.github/workflows/`)
| Workflow | Trigger | Purpose |
|---|---|---|
| `build.yml` | push to main, PRs | Debug APK artifact; build errors as annotations |
| `release.yml` | manual (`track`, `status`, `play`) | Signed AAB + APK, Play upload, tag + GitHub release |
| `backend.yml` | manual (`only`) | Validate push messages, deploy Firestore rules / functions |
| `backend-logs.yml` | manual (`hours`) | Read People error reports and group status |
| `play-check.yml` | manual | List Play tracks and versions |
| `widget-test.yml` | manual | Emulator widget tests |

From a coding agent, run them with the scripts in `tools/agent-scripts/` (`gk-release`,
`gk-deploy`, `gk-run`, `gk-status`), which keep the GitHub token in the macOS Keychain.

Release and backend workflows need repository secrets (upload key, Play service account,
Firebase deploy service account). They're not available to forks; contributors only need
`build.yml`.

## Play tracks
`internal` (internal testing), `alpha` (closed testing), `beta` (open testing), `production`.
Google may review an update before testers see it. APKs attached to GitHub releases are signed
with the upload key, so they can't update a Play-installed copy (and vice versa).

## Signing
- Debug: `app/glancekit.keystore` (committed; not secret).
- Release: an upload key supplied by CI secrets or a local, git-ignored `keystore.properties`.
  Without it, release builds fall back to the debug key so they still install locally.
