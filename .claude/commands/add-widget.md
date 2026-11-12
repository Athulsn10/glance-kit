---
description: Add a new home-screen widget following Glance Kit's patterns
argument-hint: <what the widget shows>
---
Add a new home-screen widget to Glance Kit that shows: $ARGUMENTS

1. Read AGENTS.md, ARCHITECTURE.md, dev-docs/widgets.md and dev-docs/adding-a-feature.md.
2. Decide: a `SingleWidget.Kind` (simple ring/stat) or its own provider (custom layout).
3. Implement it using only RemoteViews-safe views and bitmaps drawn at the widget's size.
4. Add the widget info XML, preview layout, manifest receiver, Setup list entry and README row.
5. Add a case to the widget tests in app/src/androidTest.
6. Build with `./gradlew assembleDebug` and fix any errors.
7. Add a CHANGELOG entry and update dev-docs/widgets.md.
Summarise what you added and anything I need to check on a device.
