# Backend (Firebase)

Lives in `firebase/`. Project and region are set in `firebase/.firebaserc` and
`functions/index.js` (`asia-south1`).

## Firestore data
| Path | Fields | Written by |
|---|---|---|
| `groups/{gid}` | circle, members[], createdBy, createdAt, lastFrom, lastAction, lastAt | Functions |
| `groups/{gid}/members/{uid}` | name, avatar, lat, lon, at, paused, tz, atHome, mood, moodUntil | That member |
| `tokens/{uid}` | token, updatedAt | That user (write-only) |
| `invites/{code}` | groupId, createdBy, expiresAt (10 min) | Functions |
| `rate/{uid}` | at (1 s between sends) | Functions |
| `logs/{id}` | error reports (no personal data) | App (create-only) and Functions |

Rules (`firestore.rules`): members can read their group and its members; each person writes
only their own member doc with validated fields; everything else is denied.

## Cloud Functions (`functions/index.js`, Node 22)
All are callable (`onCall`) and wrapped in `logged()`, which logs each call and records
failures in `logs/`.
| Function | Does |
|---|---|
| `createInvite({circle, groupId?})` | New group (or invite into a family group) + 6-char code |
| `joinWithCode({code, name})` | Joins in a transaction; tells existing members (sync push) |
| `sendAction({groupId, action})` | Rate limit, updates lastAction, pushes to the others. `{warm:true}` just wakes the function; `{test:true}` pushes to yourself |
| `leaveGroup({groupId})` | Removes you; deletes the group when empty; tells the others |

## Push messages (`functions/message.js`)
- **Action**: notification message (title, body, `channelId: pa3_<circle>_<action>`, tag per
  group, high priority, click action `com.athulsn10.glancekit.OPEN_PEOPLE`) plus data:
  `type, groupId, circle, action, sender, fromName, tab`.
- **Sync** (joined/left): data-only `type, groupId, event, fromName`.
- Data values must be strings. Reserved keys (`from`, `notification`, `message_type`,
  `google.*`, `gcm.*`) are rejected by FCM.
- `firebase/tools/check-push.js` dry-runs these against a real token; the deploy workflow runs
  it before deploying and stops on failure.

## Changing the backend
1. Edit rules / functions; `node --check` the files.
2. Keep app and functions compatible both ways (older apps stay installed for a while):
   add fields, don't rename them without reading both names on the app side.
3. Deploy with the **Deploy backend** workflow (needs the repo's deploy secret).
