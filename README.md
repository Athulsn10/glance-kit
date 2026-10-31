# Glance Kit

A growing set of glass-style Android home screen widgets, built with Jetpack Glance.

| Widget | What it does |
|---|---|
| **Glass Ring** | Day ring, battery health, data used and a focus timer in one 4×2 widget |

## Glass Ring

One glass widget for your Pixel with four pages:

1. **Time:** day progress ring with sunrise and sunset markers. Tap the ring for week, month and year.
2. **Battery health:** charging speed in watts, time to 80%, cycle count, temperature, heat status.
3. **Data:** mobile and Wi-Fi used since midnight, plus an hour-by-hour chart.
4. **Focus:** 25-minute timer that ticks live, Pause, +5 min, today's total and streak.

Everything comes from the phone itself. No accounts, no internet.

## Get the APK without Android Studio

Every push to this repo builds the app on GitHub. Open the **Actions** tab, click the latest
green run, download **GlassRing-apk**, unzip it on your phone and tap `app-debug.apk`.

## Install it with Android Studio (about 10 minutes the first time)

1. Install **Android Studio** from developer.android.com/studio.
2. Unzip this folder, then in Android Studio choose **File > Open** and pick the `glance-kit` folder. Wait for "Gradle sync" to finish (first time downloads a few hundred MB).
3. On your Pixel: **Settings > About phone**, tap **Build number** 7 times. Then **Settings > System > Developer options**, turn on **USB debugging**.
4. Plug the phone in, allow the debugging prompt, pick your Pixel in the device menu at the top and press the green **Run** button.
5. The Glance Kit app opens on your phone. Tap **Allow data usage access**, turn it on for Glance Kit, go back, then tap **Add widget to home screen**.

### Want an APK file instead?

In Android Studio: **Build > Build App Bundle(s) / APK(s) > Build APK(s)**. The file appears at
`app/build/outputs/apk/debug/app-debug.apk`. Copy it to your phone and open it to install
(allow "Install unknown apps" for your file manager when asked).

## Using it

- **Next page:** tap the text side of the widget.
- **Jump to a page:** tap a dot at the bottom.
- **Ring tap:** time page → day / week / month / year. Focus page → start, pause, resume. Battery and data → refresh.
- The widget refreshes every 15 minutes on its own and whenever you tap it.

## Change things

Open `app/src/main/java/com/glassring/widget/Config.kt`:

- `LATITUDE`, `LONGITUDE`: for sunrise and sunset (set to Thrissur).
- `ACCENT`: the accent colour.
- `FOCUS_MINUTES`: focus session length.
- `CHARGE_LIMIT`: set to 100 if you don't use the 80% charging limit.

## Known limits

- **Glass look:** Android widgets can't blur what's behind them, so the glass is a translucent fill with a soft highlight. It looks best on dark wallpapers.
- **Cycle count:** shows "–" if your Pixel 8 doesn't report it to apps.
- **Slider:** Android widgets can't swipe sideways, so pages change by tap.
- **Timing:** Android limits background refresh to every 15 minutes. The focus countdown still ticks every second because the system draws it.
