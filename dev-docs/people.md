# People (app side)

Pair with a partner, a friend or family using a 6-character code. The widget shows animated
avatars and how far apart you are; actions (hug, poke, "home safe"...) buzz the other phone in
their own vibration pattern.

## Model (`PeopleModel.kt`)
- `Circle`: `COUPLE` (2 people: hug, miss, kiss), `FRIENDS` (2: highfive, poke, hangout),
  `FAMILY` (up to 5: home, call, love). Each has accent colours, a symbol and `PeopleAction`s
  with a vibration `pattern` and `past` text ("sent a hug").
- `Person`, `Group`, `Scene` (WAITING, TOGETHER < 100 m, NEAR < 5 km, CITY < 50 km, FAR,
  RECEIVED for 10 min after an action, PAUSED or stale after 3 h).
- `People`: distance (haversine), formatting, scene and mood choice, widget text lines.
- Avatars (`AvatarArt.kt`): gender, skin, hair, style, outfit, colour, extras; 16 presets;
  12 moods; arm poses. Drawn on a Canvas.

## Repository (`PeopleRepo.kt`)
- Firebase is initialised from `BuildConfig` (`GlanceApp`). `configured` is false when the build
  has no Firebase settings; the People tab then shows "coming soon".
- Anonymous sign-in (`uid()`), own profile in prefs, `pushProfile()` writes it to each group.
- Callables: `createInvite`, `join`, `send` → `SendResult`, `testAlert`, `warm`, `leave`
  (optimistic, restored on failure).
- `refresh()` reads groups where `members` contains me, plus their members; cached as JSON for
  the widget (`cached()`, `widgetData()`).
- `live()` attaches Firestore listeners while the app is open.
- Location: `LocationManager.getCurrentLocation`, rounded to 4 decimals; home location stays on
  the phone and only `atHome` is shared.

## Widget and background (`PeopleWidget.kt`)
- `PeopleWidget` (provider), `PeopleViews` (builds RemoteViews, fallback on error).
- `PeopleSync`: periodic worker (15/30/60 min, user choice) — refresh, locate, push profile,
  redraw. `now()` for a one-off.
- `PeopleSendWork`: expedited worker for widget button sends.
- `PeopleMessaging`: FCM service. `type=action` → note it, show notification on channel
  `pa3_<circle>_<action>` (created at app start with the action's vibration pattern), redraw.
  `type=sync` → refetch (someone joined/left). When the app is closed, Android shows the
  cloud's notification itself on the same channel.

## UI (`PeopleUi.kt`)
People tab: your avatar and mood, permission cards (location, notifications, background
activity on strict OEMs), group cards with the live scene and action buttons, invite / join,
sharing settings, "Alerts on this phone" diagnostics with a test hug. `AvatarEditorActivity`
edits the avatar.

## Errors (`PeopleLog.kt`)
`PeopleLog.e(context, where, throwable)` writes a short report to Firestore `logs/` (no
personal data; same error at most once an hour; queued while signed out).
`PeopleLog.friendly(t)` turns exceptions into toast text.

## Permissions
`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (Play requires a declaration),
`POST_NOTIFICATIONS`, `VIBRATE`, `INTERNET`.
