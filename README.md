# Glance Kit

A collection of minimal, glass-style home screen widgets for Android, built with
[Jetpack Glance](https://developer.android.com/develop/ui/compose/glance).

Every widget reads its data directly from the device. No accounts, no sign-in, no internet access.

## Widgets

| Widget | Size | What it shows |
|---|---|---|
| **Glass Ring** | 4×2, resizable | Day progress, battery health, data used today and a focus timer, on four tap-through pages |

More widgets will be added over time.

### Glass Ring

| Page | Shows | Tap the ring to |
|---|---|---|
| Time | Progress through the day with sunrise and sunset markers | Switch between day, week, month and year |
| Battery | Charge level, charging speed, time to charge limit, cycle count, temperature, thermal status | Refresh |
| Data | Mobile and Wi-Fi data used since midnight, with an hourly chart | Refresh |
| Focus | Countdown timer, time focused today and daily streak | Start, pause or resume |

Tap the text side of the widget to go to the next page, or tap a dot to jump to a page.

## Requirements

- Android 12 (API 31) or later
- **Usage access** permission, for the data page only. The app asks for it on first launch.

## Install

### Download the APK

Each push to `main` builds the app and publishes it as the latest release:

**[Download GlanceKit.apk](https://github.com/Athulsn10/glance-kit/releases/latest/download/GlanceKit.apk)**

Open the file on your phone to install it. Android will ask you to allow installs from your browser or file manager.

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

1. Open **Glance Kit**.
2. Tap **Allow data usage access** and turn it on for Glance Kit.
3. Tap **Add widget to home screen**, or long-press your home screen and choose **Widgets > Glance Kit**.

## Configuration

Settings live in [`Config.kt`](app/src/main/java/com/glassring/widget/Config.kt):

| Setting | Purpose | Default |
|---|---|---|
| `LATITUDE`, `LONGITUDE` | Location used to calculate sunrise and sunset | Thrissur, India |
| `ACCENT` | Accent colour (ARGB) | `#FF6A3D` |
| `FOCUS_MINUTES` | Length of a focus session | 25 |
| `CHARGE_LIMIT` | Charge limit marked on the battery ring. Set to 100 if you don't limit charging. | 80 |

## Project structure

```
app/src/main/java/com/glassring/widget/
├── RingWidget.kt     Widget layout, pages and tap actions
├── Collect.kt        Reads time, battery and data usage from the device
├── RingRenderer.kt   Draws the rings and charts as bitmaps
├── Focus.kt          Focus timer and background refresh workers
├── Store.kt          Saved state (current page, timer, streak)
├── Config.kt         User settings
└── MainActivity.kt   Setup screen
```

## Known limitations

- **Glass effect:** Android widgets can't blur what's behind them. The glass look is a translucent fill with a soft highlight, and works best on dark wallpapers.
- **Battery cycle count:** only shown on devices that report it to apps (Android 14 and later, and not all phones). Otherwise it shows "–".
- **Page switching:** Android widgets don't support sideways swipes, so pages change on tap.
- **Refresh rate:** Android limits background updates to every 15 minutes. Tapping the widget refreshes it straight away, and the focus countdown ticks every second because the system draws it.

## Adding a widget

1. Create a `GlanceAppWidget` and a `GlanceAppWidgetReceiver` for it.
2. Add a provider file in `app/src/main/res/xml/` with its size and description.
3. Register the receiver in `AndroidManifest.xml` with its own label.
4. Add a row to the widget table at the top of this README.
