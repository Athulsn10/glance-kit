# Agent workflow: plan → build → QA → review → commit

How to run a Glance Kit change with Claude Code so the strongest model only plans, decides and
reviews, and cheaper models do the reading, building and checking.

Start a task in a new Claude Code session on **Opus** with:

```
/agent-workflow <what you want built or fixed>
```

## Roles

| Role | Runs as | Model | Does | Never does |
|---|---|---|---|---|
| Orchestrator | the session | Opus | Reads the docs, writes the plan file, asks you, reads the two reports, fixes small things, commits and pushes when told | Reads whole diffs, runs Gradle or emulators, builds features |
| Finder | `gk-finder` agent | Haiku | "Where is X / which files touch Y": paths and one-line answers | Edits anything |
| Implementer | `gk-implementer` agent | Sonnet, high effort | Builds the plan, runs build and checks, stages files, reports (≤ 60 lines) | Commits, pushes, releases, deploys |
| QA | `gk-qa` agent | Sonnet, high effort | Checks staged work against the plan and AGENTS.md, runs checks and device tests, reports findings (≤ 80 lines) | Fixes code |

Agent definitions: `.claude/agents/`. Orchestrator steps: `.claude/skills/agent-workflow/SKILL.md`.

## The loop
1. **Start clean** on `main`, pulled.
2. **Plan**: the orchestrator reads AGENTS.md, ARCHITECTURE.md and the relevant dev-doc, asks the
   finder anything wider, writes `<slug>-PLAN.md` in its scratchpad, shows you ≤ 10 lines and
   waits for your go-ahead.
3. **Build**: one call to `gk-implementer` with the plan file. The orchestrator reads only the
   report.
4. **QA**: one call to `gk-qa` with the plan and the implementer's report. Reads only the report.
5. **Review**: the orchestrator reads the flagged files, fixes small things itself or sends the
   implementer back (same agent, so it keeps its context), and reruns the check per fix.
6. **Report** to you: what was built, QA findings and fixes, exact check results, limits, what
   to test on a phone. Then asks "Commit and push to main?"
7. **Commit and push** to `main` only when you say yes. Releases and backend deploys stay
   separate and only on request (see release.md).

The plan file and the two reports are the hand-off between steps, not the chat.

## Checks used
| Change touches | Check |
|---|---|
| Anything | `./gradlew assembleDebug` |
| Widget layouts | No unsupported views; widget tests (`connectedDebugAndroidTest`) on a device/emulator, or the Widget test workflow after pushing |
| Screens | QA installs with `adb`, opens the tab, screenshots, reads logcat |
| Backend | `node --check`; push messages through `message.js` (dry-run on deploy) |
| Data use | Privacy policy and no personal data in logs |

## When it's worth it
Use the loop for anything bigger than a couple of files. The saving comes from keeping file
reading, build output and test logs out of the Opus session: it reads two short reports.
For a one-file change, let the Opus session do it directly; each agent spawn re-reads the docs.
Keep reports short, since every line is read by Opus.
