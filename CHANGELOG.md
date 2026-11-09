# Changelog

All notable changes to Glance Kit. Versions follow MAJOR.MINOR.PATCH
(see [version.properties](version.properties)).

## [1.20.5] - 2026-11-09

### Fixed
- Sending from the widget no longer gives up after 9 seconds (the cloud can take longer to wake);
  it now runs as a short background job and reports how it went.
- Error messages say what actually went wrong instead of always "check your internet".

### Added
- People error reports, from the app and from Cloud Functions, saved in Firebase for 14 days
  (no names, places or codes), readable with Actions > Backend logs.
- Every Cloud Functions call is logged with its duration and result.

## [1.20.4] - 2026-11-09

### Fixed
- The People widget falls back to a plain version (with the reason) if its picture can't be drawn,
  instead of being left broken.

### Added
- Emulator tests (Actions > Widget test) that put every widget layout, and the People widget in
  every scene and size, through a real widget host.

## [1.20.3] - 2026-11-09

### Fixed
- Hugs, kisses and pokes now vibrate the receiving phone directly in their pattern, instead of
  relying on notification vibration (which system settings and Android's notification cooldown can mute).

### Added
- People > Sharing > Alerts on this phone: shows whether notifications are on, whether the phone is
  registered for alerts, and when the last one arrived, with a button to send a test hug to yourself.
- After sending, a message says if the other phone couldn't be reached and why.

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
