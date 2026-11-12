# Architecture

A bird's-eye view of Glance Kit. Details per area are in [dev-docs/](dev-docs/).

## Layers
```
Home screen (launcher)
   │  RemoteViews: layouts + bitmaps + PendingIntents
   ▼
Widget providers ──► Renderers (bitmaps) ──► Data sources ──► Store (SharedPreferences)
   ▲                                              │
   │ WorkManager / alarms / broadcasts            ├─ device: battery, steps, sensors, usage stats
   │                                              ├─ web: MET Norway (weather), Open-Meteo (air quality)
App (MainActivity tabs, activities)               └─ Firebase (People only)
```

## Code map (`app/src/main/java/com/athulsn10/glancekit/`)
| Area | Files |
|---|---|
| App shell | `MainActivity.kt` (tabs: Today, Steps, Battery, Data, Focus, Instruments, Phone, People, Setup), `Ui.kt` (view helpers), `Config.kt`, `Store.kt` |
| All-in-one widget | `Widget.kt` (`RingWidgetReceiver`, pages, `refreshAll`, `Views`) |
| Single widgets | `Singles.kt` (`SingleWidget` + Today, Steps, Battery, Data, Focus, Pressure, Compass) |
| Clock + weather | `ClockWidget.kt`, `Weather.kt`, `WeatherArt.kt`, `SkyArt.kt`, `Place.kt` |
| Drawing | `RingRenderer.kt`, `PeopleArt.kt`, `AvatarArt.kt`, `RamView.kt`, `FocusView.kt` |
| Data | `Collect.kt`, `Sampler.kt`, `StepCounter.kt`, `Barometer.kt`, `SensorReader.kt`, `Device.kt` |
| Focus timer | `Focus.kt`, `FocusActivity.kt`, `FocusView.kt`, `Ambient.kt` |
| Instruments | `InstrumentsActivity.kt`, `CompassService.kt` (foreground service for the live compass), `Haptics.kt` |
| People | `PeopleModel.kt`, `PeopleRepo.kt`, `PeopleWidget.kt`, `PeopleUi.kt`, `PeopleArt.kt`, `AvatarArt.kt`, `PeopleLog.kt` |

Resources: widget layouts in `res/layout/` (`widget_root`, `single_*`, `clock_widget`,
`people_widget`, `preview_*`), widget info in `res/xml/w_*.xml`.

## Key flows
- **Widget refresh:** `refreshAll(context)` rebuilds every placed widget. Triggered by
  `onUpdate`, app resume, charging/time broadcasts, and workers. Each widget builds a fresh
  `RemoteViews` (full update, never partial).
- **History:** `Sampler` records battery/data/pressure samples into `Store` for charts.
- **Weather:** `Place` gets a coarse location, `Weather` fetches and caches forecasts and AQI.
- **People:** the app writes its own profile + location to Firestore; Cloud Functions handle
  pairing and send pushes; phones receive pushes via FCM. See dev-docs/people.md and
  dev-docs/backend.md.

## Backend (`firebase/`)
Firestore (`firestore.rules`) + Cloud Functions v2 (`functions/index.js`, `functions/message.js`,
Node 22, region `asia-south1`) + Cloud Messaging. Tools for CI in `firebase/tools/`.

## Build and CI
`version.properties` → versionName/versionCode. Firebase app settings are read from
`app/google-services.json` into `BuildConfig` at build time (no google-services plugin).
Workflows in `.github/workflows/`: build, release (Play + GitHub), backend deploy, backend logs,
Play check, widget tests. See dev-docs/release.md.

## Decisions
- **Views, not Compose:** widgets need RemoteViews anyway; one UI toolkit keeps it simple.
- **Bitmaps for visuals:** RemoteViews can't host custom views, so visuals are pre-drawn.
- **ViewFlipper for animation:** the launcher flips frames itself, so animation costs nothing
  in the app.
- **Anonymous Firebase auth:** People needs no account, email or phone number.
- **Notification pushes for actions:** delivered even when the app is closed on OEMs that block
  background starts.
