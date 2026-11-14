# People (app side)

Pair with a partner, a friend or family using a 6-character code. The widget shows animated
avatars and how far apart you are; actions (hug, poke, "home safe"...) buzz the other phone in
their own vibration pattern.

## Model (`PeopleModel.kt`)
- `Circle`: `COUPLE` (2 people: hug, miss, kiss, plus every friends and family action), `FRIENDS` (2: highfive, poke, hangout),
  `FAMILY` (up to 5: home, call, love). Each has accent colours, a symbol and `PeopleAction`s
  with a vibration `pattern` and `past` text ("sent a hug").
- `Person`, `Group`, `Scene` (WAITING, TOGETHER < 100 m, NEAR < 5 km, CITY < 50 km, FAR,
  RECEIVED for 10 min after an action, PAUSED or stale after 3 h).
- `People`: distance (haversine), formatting, scene and mood choice, widget text lines.
- Avatars (`AvatarArt.kt`): gender, skin, hair, style, outfit, colour, extras; 16 presets;
  12 moods; arm poses. Drawn on a Canvas. Extras are one per spot (`EXTRA_SLOTS`: eyes, head,
  clip, ears); `Avatar.sane()` trims anything else, so old or remote avatars can't stack hats.
  Add outfits, colours and extras at the END of their lists: values an older app doesn't know
  fall back (plain tee, last colour, extra dropped). Don't add avatar fields without changing
  `firestore.rules` (`avatar.size() <= 8`).
- Each `PeopleAction` has a `color`, used for its button and its full-screen moment.

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
- The widget shows one group. Tapping its title (`ACTION_NEXT`) steps through
  `PeopleRepo.cached()`, whose order is set with "Move up" in the app (`PeopleRepo.moveUp`,
  kept in prefs). `PeopleViews.update` redraws pictures only when they changed (see
  dev-docs/widgets.md, Animation).
- `PeopleWidget` (provider), `PeopleViews` (builds RemoteViews, fallback on error).
- `PeopleSync`: periodic worker (15/30/60 min, user choice) — refresh, locate, push profile,
  redraw. `now()` for a one-off.
- `PeopleSendWork`: expedited worker for widget button sends.
- `PeopleMessaging`: FCM service. `type=action` → note it, buzz the phone directly (unless silent / Do Not Disturb), show notification on channel
  `pa3_<circle>_<action>` (created at app start with the action's vibration pattern), redraw.
  `type=sync` → refetch (someone joined/left). When the app is closed, Android shows the
  cloud's notification itself on the same channel.

## UI (`PeopleUi.kt`)
People tab: your avatar and mood, permission cards (location, notifications, background
activity on strict OEMs), group cards with the live scene and action buttons, invite / join,
sharing settings, "Alerts on this phone" diagnostics with a test hug. `AvatarEditorActivity`
edits the avatar.

## Action moments (`PeopleFx.kt`)
- `PeopleFx.draw(canvas, w, h, density, p, spec)` draws the full-screen moment for an action
  from `p` (0..1): who moves where, moods, arms, particles, caption. One `when` branch per action
  key, plus a default (a beating heart) so a new action still plays something. `impacts` lists
  the collision times that flash and shake the screen. It only needs a Canvas, so it can be
  rendered off-device to check a change.
- `PeopleFxOverlay` plays it over the activity's decor view, shakes the content underneath,
  and removes itself. Tap skips. Nothing plays when the phone has animations turned off.
- Sent: the action buttons in `PeopleUi.groupCard` play it at once (the send carries on and
  toasts as before). Received: `PeopleSceneView` plays the group's latest action the first time
  the scene is really on screen, if it came from someone else in the last 10 minutes and hasn't
  been played on this phone (`PeopleHistory.seen`).
- `PeopleHistory` keeps the last two actions per group in prefs, on the phone only. "Returned"
  means the same action came back from the other person within 30 minutes. If this phone never
  saw the first one (app closed, no sync in between), it can't know, and plays the normal one.
- No haptics on the sender's side: only the receiver's phone buzzes.

When adding an action, also add its branch in `PeopleFx.draw`, its `caption` and, if it has its
own symbol, `PeopleArt.actionSymbol` / `symbol`.

## Errors (`PeopleLog.kt`)
`PeopleLog.e(context, where, throwable)` writes a short report to Firestore `logs/` (no
personal data; same error at most once an hour; queued while signed out).
`PeopleLog.friendly(t)` turns exceptions into toast text.

## Permissions
`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (Play requires a declaration),
`POST_NOTIFICATIONS`, `VIBRATE`, `INTERNET`.
