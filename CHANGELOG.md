# Changelog

All notable changes to Glance Kit. Versions follow MAJOR.MINOR.PATCH
(see [version.properties](version.properties)).

## [1.20.2] - 2026-11-09

### Fixed
- Hugs, kisses and pokes now buzz only the person you send them to, not your own phone.
- If notifications are off, the receiving phone still vibrates.
- Leaving a group updates the other phone within seconds instead of at its next sync.
- Sending is faster: the cloud function starts quicker and is woken when People opens.
- Opening the app shows the latest from your group straight away, before your own location is found.

### Added
- While the app is open, joins, leaves, places and moods update live.
- A quiet notification when someone joins or leaves your group.
- A card on People asking for notifications, so hugs can be felt.

## [1.20.1] - 2026-11-09

### Fixed
- Fixed the People widget showing "Can't load widget".

## [1.20.0] - 2026-11-09

### Added
- **People widget and tab.** Pair with your partner, a friend or your family (up to 5) with a one-time code.
  - A little scene shows how far apart you are: together, nearby, same city or far apart, with their local time.
  - Cute avatars with 12 moods that react to what's happening, 16 starting looks, and hair, skin, outfit and extras to customise.
  - Partner (hearts), Friend (stars, paper plane) and Family (home) each have their own colours and actions:
    Hug / Miss you / Kiss, High five / Poke / Hang out?, Home safe / Call me / Love you. Each buzzes the other phone in its own pattern.
  - Distance updates every 15, 30 or 60 minutes, one location each time. Pause or leave at any time.
  - The widget's animation is a few frames flipped by the launcher, so it costs the app nothing.
- Backend for People: Firestore rules and Cloud Functions in `firebase/`, deployed by the **Deploy backend** workflow.

### Changed
- Privacy policy updated for People.

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
