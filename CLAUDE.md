# CLAUDE.md

@AGENTS.md

## Notes for Claude Code
- Start by reading ARCHITECTURE.md and the dev-docs file for the area you're changing.
- After editing Kotlin, run `./gradlew assembleDebug`; after widget layout changes, also run the
  widget tests (`./gradlew connectedDebugAndroidTest`) or the "Widget test" workflow.
- After editing `firebase/functions`, run `node --check` on the changed files. Push message
  changes must go through `message.js`.
- For anything bigger than a couple of files, use `/agent-workflow <task>` (skill in
  `.claude/skills/agent-workflow/`): you plan and review on Opus, `gk-implementer` and `gk-qa`
  (Sonnet) build and check, `gk-finder` (Haiku) searches. See dev-docs/agent-workflow.md.
- Project slash commands live in `.claude/commands/` (`/add-widget`, `/release-prep`).
- Never read, print or ask for keys. Run GitHub workflows and push only through
  `~/.glancekit/bin/gk-*` (tools/agent-scripts/README.md); if a script says a key is missing,
  ask the owner to run `gk-setup` in Terminal. `.claude/settings.json` enforces this.
- A local `context/` folder may exist with private notes and credentials. It's git-ignored:
  never copy anything from it into tracked files, commits, PRs or logs.
