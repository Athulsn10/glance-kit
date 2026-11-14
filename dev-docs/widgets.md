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
The launcher runs widget animation itself, so it costs the app nothing. `people_widget.xml`
stacks two layers in `people_scene`:
- `people_frames`: a `ViewFlipper` (`autoStart`, `flipInterval` 400 ms) filled with up to 6
  `ImageView` frames via `removeAllViews` + `addView`. Its `inAnimation` / `outAnimation`
  cross-fade the frames, so a few frames read as smooth bobbing. Frames are RGB_565, scaled
  ≤ 1.5x, and fewer are drawn for big widgets (`PeopleArt.frames`, 3 MB budget).
- `people_fly_go` / `people_fly_sway`: a `ViewFlipper` holding two copies of one small ARGB
  picture (the heart or plane). Its `inAnimation` (`anim/people_fly_go.xml`, `people_fly_sway.xml`)
  is a view animation, so the symbol glides at the screen's refresh rate. The flipper is made
  exactly as wide as the trip with `setViewLayoutWidth` and nudged into place with
  `setViewLayoutMargin` (`PeopleArt.fly` gives the numbers in dp); `100%p` in the animation is
  then the whole trip. The animation lasts exactly one flip interval and uses `fillAfter`.

Don't draw anything that should move far into the frames: a handful of frames can only step.
An animation can't be changed from code (only picked by layout), which is why there are two
flippers and why the trip length is set through the flipper's width.

## Memory
Android 17 rejects widget updates whose bitmaps exceed ~6 × screen pixels in bytes for apps
targeting API 37. We target 36, but stay well under it anyway.
