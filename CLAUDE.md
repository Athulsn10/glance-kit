# CLAUDE.md

@AGENTS.md

## Notes for Claude Code
- Start by reading ARCHITECTURE.md and the dev-docs file for the area you're changing.
- After editing Kotlin, run `./gradlew assembleDebug`; after widget layout changes, also run the
  widget tests (`./gradlew connectedDebugAndroidTest`) or the "Widget test" workflow.
- After editing `firebase/functions`, run `node --check` on the changed files. Push message
  changes must go through `message.js`.
- Project slash commands live in `.claude/commands/` (`/add-widget`, `/release-prep`).
- A local `context/` folder may exist with private notes and credentials. It's git-ignored:
  never copy anything from it into tracked files, commits, PRs or logs.
