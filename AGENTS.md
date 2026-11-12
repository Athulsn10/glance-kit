# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Cursor, Copilot and others) and for people
working on Glance Kit. Read this first, then the doc for the area you're changing.

## What this is
Glance Kit is an Android app of home-screen widgets (day progress ring, steps, battery, data,
focus timer, barometer, compass, level, clock with weather, an all-in-one widget and **People**,
which pairs you with a partner, friends or family). Native Kotlin with classic Android Views;
widgets are `RemoteViews`. People has a small Firebase backend.

- Package / namespace: `com.athulsn10.glancekit`
- Min SDK 31, target/compile SDK 36, Java 17, Kotlin 2.1, AGP 8.10
- No Compose, no DI framework, no Room: SharedPreferences (`Store.kt`) and plain classes.
- UI is built in code (`Ui.kt` helpers), not XML layouts, except for widgets.

## Map of the docs
| Read | When you're working on |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Anything: the big picture and where code lives |
| [dev-docs/widgets.md](dev-docs/widgets.md) | Any home-screen widget or `RemoteViews` |
| [dev-docs/people.md](dev-docs/people.md) | The People feature (app side) |
| [dev-docs/backend.md](dev-docs/backend.md) | Firestore rules, Cloud Functions, push messages |
| [dev-docs/adding-a-feature.md](dev-docs/adding-a-feature.md) | Adding a widget, tab, setting or action |
| [dev-docs/testing-and-debugging.md](dev-docs/testing-and-debugging.md) | Tests, logs, known pitfalls |
| [dev-docs/release.md](dev-docs/release.md) | Versioning, CI workflows, releasing |
| [dev-docs/agent-workflow.md](dev-docs/agent-workflow.md) | Running a change with planner / implementer / QA agents |

## Commands
```bash
./gradlew assembleDebug                 # build debug APK -> app/build/outputs/apk/debug/
./gradlew installDebug                  # install on a connected device
./gradlew connectedDebugAndroidTest     # widget tests (needs a device or emulator)
npm ci --prefix firebase/functions      # backend dependencies
node --check firebase/functions/index.js
```
CI: `build.yml` builds every push and PR. Backend deploys and releases are manual workflows
(see dev-docs/release.md). There is no local Firebase deploy step in normal work.

## Rules that matter
1. **Widgets only allow certain view classes.** No plain `<View>`, no custom views, no
   ConstraintLayout in widget layouts. A single unsupported view makes the whole widget show
   "Can't load widget". Use `FrameLayout`, `LinearLayout`, `ImageView`, `TextView`,
   `ViewFlipper` etc. Run the widget tests after touching widget layouts.
2. **Draw, don't nest.** Rings, charts, avatars and scenes are drawn to bitmaps
   (`RingRenderer`, `PeopleArt`, `SkyArt`...) and set with `setImageViewBitmap`. Keep bitmaps
   small (RGB_565 where opaque, scale ≤ 1.5x) and never call `partiallyUpdateAppWidget` to swap
   bitmaps.
3. **Never block a broadcast.** `AppWidgetProvider.onReceive` has seconds to run. Network work
   goes to WorkManager (see `PeopleSendWork`).
4. **Background limits are real.** Periodic work is 15 min minimum. Assume the app may be
   killed; some OEMs (vivo, Xiaomi, Oppo) never wake a closed app for data-only pushes.
5. **Firebase push data keys:** never use `from`, `notification`, `message_type` or keys
   starting with `google.` / `gcm.`; FCM rejects the message. Build pushes in
   `firebase/functions/message.js` so the deploy-time dry run validates them.
6. **Privacy:** People shares data only with group members. Don't log names, coordinates,
   invite codes or tokens (see `PeopleLog`). Home location never leaves the phone. Update
   `docs/privacy.html` when you change what's collected or shared.
7. **Versioning:** don't hand-edit `versionCode`. Bump `version.properties` and add a
   `CHANGELOG.md` entry; releases are cut by the release workflow.
8. **Secrets:** never commit keystores (other than the debug one), service-account JSON,
   tokens or passwords. `app/google-services.json` is public config and is meant to be committed.

## Style
- Kotlin, 4-space indent, short functions, comments that explain *why*.
- User-facing text: plain, friendly, sentence case, no jargon. Errors say what happened and
  what to do (`PeopleLog.friendly`).
- Keep features self-contained in their own files; reuse `Ui.kt`, `Store.kt`, `Views.openApp`.
- Prefer small, reviewable changes; update the matching doc in `dev-docs/` in the same change.
