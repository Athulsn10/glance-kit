# Glance Kit

A collection of minimal home screen widgets for Android, built on Android's native widget views.

Every widget reads its data directly from the device. No accounts, no sign-in, no internet access.

## Widgets

| Widget | Size | What it shows |
|---|---|---|
| **Glass Ring** | 4×2, resizable | Day progress, steps, battery health, data used, a focus timer and air pressure, with charts, on six pages |

More widgets will be added over time.

### Glass Ring

| Page | Shows | Tap the ring to |
|---|---|---|
| Time | Progress through the day, with a daylight chart and sunrise and sunset times | Switch between day, week, month and year |
| Steps | Steps today, distance, steps left to goal, streak, hourly chart | Switch between today and the last 7 days (with goal line) |
| Battery | Level, charging speed, time to charge limit, estimated health, cycle count, temperature, 24-hour level chart | Refresh |
| Data | Mobile and Wi-Fi data used, with an hourly chart | Switch between today and the last 7 days |
| Focus | Countdown timer, time focused today, streak, 7-day chart | Start, pause or resume |
| Instruments | Air pressure, trend and weather hint, altitude, 3-hour change, pressure chart | Open the live compass, level and barometer |

Use the arrow buttons in the widget's header to change page, or tap a dot to jump to one. Pages switch
instantly with a short fade. While the phone is charging, the widget updates every minute.

### Live instruments

Tap the Instruments page, or long-press the Glance Kit icon, to open a full-screen **compass**,
**spirit level** and **barometer**. They update in real time. The sensors run only while that
screen is open, so they use no battery otherwise. The level works flat on a surface (bullseye) or
on its edge (tube), and the phone gives a short buzz when it's level.

## Requirements

- Android 12 (API 31) or later
- **Physical activity** permission for the Steps page. The app asks once on first launch. Steps come from the phone's built-in step counter, so no Google Fit, Health Connect or account is needed.
- **Usage access** is optional: with it, the data page counts the whole day instead of from install time.

## Install

### Download the APK

Each push to `main` builds the app and publishes it as the latest release:

**[Download GlanceKit.apk](https://github.com/Athulsn10/glance-kit/releases/latest/download/GlanceKit.apk)**

Open the file on your phone to install it. Android will ask you to allow installs from your browser or file manager.
New builds install over the previous one.

### Build from source

Requires JDK 17 and the Android SDK (Android Studio installs both).

```bash
git clone https://github.com/Athulsn10/glance-kit.git
cd glance-kit
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected device, run
`./gradlew installDebug` with USB debugging turned on.

## Setup after installing

1. Open **Glance Kit**, allow **physical activity** when asked, then tap **Add widget to home screen** (or long-press your home screen and choose **Widgets > Glance Kit**).
2. Optional: tap **Usage access** and turn it on for Glance Kit.

If Android says **"App was denied access"**, it's blocking a restricted setting because the app wasn't
installed from the Play Store. Open **Settings > Apps > Glance Kit**, tap **⋮** in the top corner,
choose **Allow restricted settings**, then turn on Usage access again.

## Configuration

Settings live in [`Config.kt`](app/src/main/java/com/glassring/widget/Config.kt):

| Setting | Purpose | Default |
|---|---|---|
| `LATITUDE`, `LONGITUDE` | Location used to calculate sunrise and sunset | Thrissur, India |
| `ACCENT` | Accent colour (ARGB) | `#FF6A3D` |
| `FOCUS_MINUTES` | Length of a focus session | 25 |
| `CHARGE_LIMIT` | Charge limit marked on the battery ring. Set to 100 if you don't limit charging. | 80 |
| `STEP_GOAL` | Daily step goal | 8000 |
| `STRIDE_METRES` | Average stride, used for distance | 0.75 |
| `DESIGN_CAPACITY_MAH` | Battery capacity when new, used for the health estimate if the phone doesn't report it | 4575 (Pixel 8) |

## Project structure

```
app/src/main/java/com/glassring/widget/
├── Widget.kt         Widget provider, page layouts and tap actions
├── Collect.kt        Reads time, battery and data usage from the device
├── Sampler.kt        Records history for the charts and the health estimate
├── StepCounter.kt    Daily steps from the phone's step counter
├── Barometer.kt      Air pressure sampling, trend and history
├── SensorReader.kt   One-shot sensor readings for the widget
├── InstrumentsActivity.kt  Live compass, spirit level and barometer
├── RingRenderer.kt   Draws the rings and charts as bitmaps
├── Focus.kt          Focus timer, background refresh and live charging updates
├── Store.kt          Saved state (current page, timer, streak)
├── Config.kt         User settings
└── MainActivity.kt   Setup screen
```

## Known limitations

- **Battery health** is an estimate: the current full charge compared with the battery's capacity when new. Apps can't read the exact figure that Settings shows.
- **Battery cycle count:** only shown on devices that report it to apps (Android 14 and later, and not all phones). Otherwise it shows "–".
- **Steps** are counted from install, and only while you carry the phone.
- **Data without usage access** is counted from when the app was installed, so the first day starts part-way through.
- **Page switching:** Android widgets don't support sideways swipes, so pages change with the arrow buttons.
- **Refresh rate:** on battery, Android limits background updates to every 15 minutes. While charging, the widget updates every minute. Tapping it always refreshes straight away.

## Adding a widget

1. Create an `AppWidgetProvider` and its layouts in `app/src/main/res/layout/`.
2. Add a provider file in `app/src/main/res/xml/` with its size and description.
3. Register the receiver in `AndroidManifest.xml` with its own label.
4. Add a row to the widget table at the top of this README.
