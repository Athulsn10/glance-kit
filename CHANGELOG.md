# Changelog

All notable changes to Glance Kit. Versions follow MAJOR.MINOR.PATCH
(see [version.properties](version.properties)).

## [1.19.0] - 2026-11-08

### Changed
- Version numbers are now standardised. The version lives in `version.properties`, and the Play Store
  versionCode is worked out from it (1.19.0 is 11900).
- Releases are made on demand from the **Release** workflow and uploaded to Google Play. Pushes to `main`
  only build and check the app.

### Added (since the first closed-testing build, 1.18.31)
- Targets Android 16 (API 36).
- Sunrise, sunset, weather and air quality use your approximate location when allowed.
- Privacy policy at https://athulsn10.github.io/glance-kit/privacy.html

## [1.18.31] - 2026-10-31

First build in Google Play closed testing.
