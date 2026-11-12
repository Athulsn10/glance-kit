# Adding a feature

## A new single widget
1. Add a value to `SingleWidget.Kind` in `Singles.kt` (page, title, tab, provider class, icon)
   and a `class XWidget : SingleWidget()`.
2. Draw its content in the matching page builder (reuse `RingRenderer` for rings/charts).
3. Add `res/xml/w_x.xml` (sizes, `previewLayout`, description string) and
   `res/layout/preview_x.xml` using only RemoteViews-safe views.
4. Register the receiver in `AndroidManifest.xml` with its label.
5. Make `refreshAll()` cover it (single widgets are covered via `Kind`).
6. Add it to the widget list in `MainActivity` (Setup) and the README table.
7. Add a case to the widget tests in `app/src/androidTest/` if it has its own layout.

## A new widget with its own provider
Follow `ClockWidget.kt` or `PeopleWidget.kt`: provider + `XViews.update(context)` that loops
over `getAppWidgetIds`, builds a full `RemoteViews` per size, and catches errors with a fallback.
Call it from `refreshAll()` when instances exist, and from `stopIfNoWidgets` in `Singles.kt`.

## A new tab or card in the app
Tabs are listed in `MainActivity` (`tabs`, `TAB_*` constants) and built in code. Use `Ui.card`,
`Ui.text`, `Ui.pill`, `Ui.hstack`, `Ui.spacer`. Long work goes on a coroutine scope, never the
main thread.

## A new People action
1. Add a `PeopleAction(key, label, pattern, past)` to the circle in `PeopleModel.kt`.
2. Add the key and past text to `CIRCLES` in `functions/index.js` and `PAST` in
   `functions/message.js`.
3. Channels are created from the model at app start (`pa3_<circle>_<key>`), so new actions get
   their vibration automatically on fresh installs; existing installs create missing channels
   only if you bump the channel-set check in `PeopleMessaging.channels`.
4. Deploy the backend, then release the app.

## A new setting
Store it via `Store.prefs(context)` with a clear key prefix (e.g. `people_`), add UI in the
relevant tab, and redraw widgets with `refreshAll` if it affects them.

## Checklist for any change
- [ ] Builds: `./gradlew assembleDebug`
- [ ] Widget layouts only use RemoteViews-safe views; widget tests pass
- [ ] No personal data in logs; privacy policy updated if data use changed
- [ ] `CHANGELOG.md` entry; `version.properties` bumped when releasing
- [ ] Matching doc in `dev-docs/` updated
