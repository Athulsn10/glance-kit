---
name: agent-workflow
description: Run a Glance Kit change with the plan → build → QA → review → commit loop. Opus (this session) plans, asks the owner, reviews and commits; gk-implementer and gk-qa (Sonnet) build and check; gk-finder (Haiku) searches. Use when the owner asks to build, fix or change something non-trivial in Glance Kit, or says /agent-workflow <task>.
---
# Agent workflow for Glance Kit

You are the **orchestrator** (Opus). You plan, ask the owner, delegate, review and commit.
Keep your own context small: you don't read whole diffs, run Gradle or emulators, or build the
feature yourself. Full description: `dev-docs/agent-workflow.md`.

The task is what the owner wrote after the command (or in their message). There are no task IDs.

## 1. Start clean
- `git status --porcelain` must be empty; if not, stop and tell the owner.
- `git switch main && git pull --ff-only`.

## 2. Plan
- Read AGENTS.md, ARCHITECTURE.md and the dev-docs file for the area (widgets, people, backend,
  adding-a-feature, release, testing-and-debugging).
- For "where / which files" questions, use `Agent` with `subagent_type: gk-finder` (Haiku).
  Don't read large files yourself.
- Write the plan to your scratchpad as `<short-slug>-PLAN.md`:
  goal; files to add / change and what each change is; what must stay the same; rules from
  AGENTS.md that apply; tests to add or update; checks to run; docs, CHANGELOG and privacy
  updates; what to verify on a phone; report shape.
- Show the owner the plan in at most ten lines and **wait for the go-ahead**.

## 3. Build
`Agent`, `subagent_type: gk-implementer`, with: the plan file path, the task in one sentence,
and "stage everything, commit nothing, report under 60 lines". Read only its report.

## 4. QA
`Agent`, `subagent_type: gk-qa`, with: the plan file path and the implementer's report. Read
only its report.

## 5. Review and fix
- Read the reports and only the files QA flagged.
- Small fixes: make them yourself and rerun the one check that covers each.
- Larger ones: `SendMessage` to the same implementer with the list, then QA again if needed.
- Make sure dev-docs, CHANGELOG and privacy policy are updated where the change needs it, and
  `git add` the intended files only. Never stage a `context/` folder.

## 6. Report to the owner
In this shape: what was built; QA findings and what was fixed; exact check results; known
limits; what to test on a phone. Then ask: **"Commit and push to main?"** and stop.

## 7. Commit and push (only when the owner says yes)
- One commit on `main` with a clear summary line (and short body if useful), then
  `git push origin main`. Do this inline; don't spawn an agent for git.
- Don't bump the version, release, or deploy the backend unless the owner asks. When they do,
  follow `dev-docs/release.md` (release and backend deploys run as GitHub workflows).

## When to skip the loop
For a one-file or one-line change, do it yourself and still run `./gradlew assembleDebug`;
spawning agents costs more than the change.
