---
description: Prepare a release (version bump, changelog, what's new) without publishing
argument-hint: <patch|minor|major>
---
Prepare a Glance Kit release of type: $ARGUMENTS (default patch).

1. Read dev-docs/release.md.
2. Bump `version.properties` accordingly and compute the new versionCode.
3. Summarise changes since the last tag (`git log $(git describe --tags --abbrev=0)..HEAD`)
   into a new `CHANGELOG.md` section in the existing style (Fixed / Added / Changed).
4. Update `distribution/whatsnew/whatsnew-en-US` (≤ 500 characters, user-facing, bullet points).
5. Run `./gradlew assembleDebug`.
Don't tag, push or run the release workflow; tell me what's ready to commit.
