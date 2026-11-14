# Agent scripts: keys without exposure

Small scripts that let a coding agent (Claude Code or similar) run Glance Kit's GitHub workflows
and push commits **without ever seeing a key**. The scripts read keys from the macOS Keychain,
do one job and print only the result, with anything key-like hidden.

These files hold no secrets. They're installed outside the project, where the agent can run them
but (with the rules in `.claude/settings.json`) can't read or change them.

## Install (once, on your Mac)
```bash
bash tools/agent-scripts/install.sh          # copies to ~/.glancekit/bin, read-only
~/.glancekit/bin/gk-setup github-token       # paste a GitHub token; it isn't shown
~/.glancekit/bin/gk-status latest build.yml  # check it works
```
Use a **fine-grained GitHub token** for this one repo only, with an expiry, and these
permissions: Contents (read and write), Actions (read and write), Metadata (read).

Re-run `install.sh` after the scripts change in the repo.

## Commands
| Command | Does | Claude Code |
|---|---|---|
| `gk-run build\|widget-test\|play-check\|backend-logs [hours]` | Starts a read-only workflow, prints the run id | Allowed |
| `gk-status RUN_ID [--wait [secs]]`, `gk-status latest WORKFLOW.yml` | Run result with errors and notices | Allowed |
| `gk-logs [hours]` | Runs Backend logs and prints the reports | Allowed |
| `gk-release internal\|alpha\|beta\|production [--no-play] [--draft]` | Release workflow (Play + GitHub release) | Asks you first |
| `gk-deploy [all\|rules\|functions]` | Backend deploy (validates the push message first) | Asks you first |
| `gk-push [branch]` | Pushes the current branch with the Keychain token | Asks you first |
| `gk-setup [name]` | Stores a key in the Keychain; you run it in Terminal | Blocked |
| `gk-askpass` | Used by `gk-push` internally | Blocked |

## How keys stay hidden
- Keys live in the Keychain as `glancekit.<name>`, not in any file.
- The token is passed to `curl` on stdin and to `git` through `GIT_ASKPASS`, so it never
  appears on a command line or in the process list.
- Output goes through a filter that replaces anything token-like or a private key with `[hidden]`.
- `.claude/settings.json` denies reading or editing `~/.glancekit`, calling `security` to read
  the Keychain, and reading key files in the repo. Releases, deploys and pushes ask first.
- Play and Firebase keys never reach these scripts at all: they're GitHub secrets used only
  inside the workflows.

## Limits
Permission rules are a strong guardrail, not a sandbox: the agent runs as you, and a determined
workaround (for example a custom script) could still reach anything your account can. Keep
tokens narrow and short-lived, rotate them if one ever appears in a chat, and review what an
agent proposes to run.
