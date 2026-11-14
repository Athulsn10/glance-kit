# Testing and debugging

## Tests
- `app/src/androidTest/.../WidgetInflateTest.kt`:
  - `WidgetInflateTest`: applies widget RemoteViews the way a launcher does.
  - `WidgetHostTest`: binds a real widget through `AppWidgetHost` at several sizes.
  - `PeopleWidgetMatrixTest`: every avatar × scene × size.
- Run locally with `./gradlew connectedDebugAndroidTest`, or the **Widget test** workflow
  (emulators on API 35 and 36).

## Logs
- App: `adb logcat -s GlanceKit`.
- People error reports and delivery: the **Backend logs** workflow prints recent reports, each
  group's members and push-token status, and Cloud Functions errors. Reports hold no names,
  places or codes.
- In the app: People → Sharing → *Alerts on this phone* shows notification and push status and
  can send a test hug to the same phone.

## Pitfalls already hit
| Symptom | Cause | Fix |
|---|---|---|
| "Can't load widget" | Plain `<View>` in a widget layout | Use `FrameLayout` |
| Every push rejected (`invalid-argument`) | Data key `from` is reserved by FCM; also invalid field `notificationPriority` | Use `sender`; `priority: "high"`; dry-run check before deploy |
| Pushes never arrive on vivo/Xiaomi with the app closed | OEM blocks background starts for data-only pushes | Send actions as notification messages; ask for background activity |
| Sender's phone vibrates | Local buzz on tap | Only the receiver vibrates |
| Widget send "check your internet" | 9 s broadcast limit vs. cold start | Send in `PeopleSendWork` |
| `TOO_MANY_REGISTRATIONS` | Emulator's Play services out of FCM registrations | Wipe the emulator or use a real phone |
| Action arrives but the phone doesn't buzz | Channel vibration depends on the phone's settings (notification vibration off, silent, Do Not Disturb, notification cooldown) | With the app open we also buzz directly (`PeopleSync.buzz`); with it closed only the channel can buzz, so check those settings and the `received` lines in Backend logs |
| `RESOURCE_EXHAUSTED` | Sends less than 1 s apart | Expected rate limit |
