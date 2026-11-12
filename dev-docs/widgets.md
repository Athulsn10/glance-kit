# Widgets

## Providers
| Provider | Layout | Info |
|---|---|---|
| `RingWidgetReceiver` (all-in-one, pages) | `widget_root.xml`, `page_*.xml` | `ring_widget_info.xml` |
| `TodayWidget`, `StepsWidget`, `BatteryWidget`, `DataWidget`, `FocusWidget`, `PressureWidget`, `CompassWidget` (`Singles.kt`) | `single_small.xml`, `single_wide.xml`, `compass_widget.xml` | `w_*.xml` |
| `ClockWidget` | `clock_widget.xml` | `w_clock.xml` |
| `PeopleWidget` | `people_widget.xml`, `people_frame.xml` | `w_people.xml` |

Widget pickers use `preview_*.xml` layouts (Android 12+ `previewLayout`) and `ic_w_*` icons.

## Allowed in RemoteViews
Layouts: `FrameLayout`, `LinearLayout`, `RelativeLayout`, `GridLayout`. Views: `TextView`,
`ImageView`, `Button`, `ImageButton`, `ProgressBar`, `Chronometer`, `AnalogClock`,
`ViewFlipper`, `AdapterViewFlipper`, `ListView`, `GridView`, `StackView`, `CheckBox`, `Switch`,
`RadioButton`. **Not** plain `View`, custom view classes, ConstraintLayout or
subclasses. Methods called through `setInt`/`setFloat` etc. must be `@RemotableViewMethod`.

## Patterns
- Size-aware: read `OPTION_APPWIDGET_MIN_WIDTH` / `MAX_HEIGHT` from `getAppWidgetOptions` and
  draw bitmaps for that size; handle `onAppWidgetOptionsChanged`.
- Taps: `Views.openApp(context, tab, requestCode)` opens a tab; actions use broadcast
  `PendingIntent`s back to the provider, which hands real work to WorkManager.
- Never let an exception leave the widget broken: catch around `updateAppWidget` and show a
  plain fallback (see `PeopleViews.fallback`).
- Colours: dark card `widget_bg.xml`, accents per feature; pills `pill_*.xml`.

## Animation
`people_widget.xml` has a `ViewFlipper` (`autoStart`, `flipInterval`) filled with 2–3 frame
`ImageView`s via `removeAllViews` + `addView`. Frames are RGB_565 and scaled ≤ 1.5x.

## Memory
Android 17 rejects widget updates whose bitmaps exceed ~6 × screen pixels in bytes for apps
targeting API 37. We target 36, but stay well under it anyway.
