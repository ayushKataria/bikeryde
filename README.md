# BikeRyde

A personal motorbike ride tracker: single & multi-day ride tracking, Strava-style graphics,
animated multi-day trip videos, fuel logging, and AI-assisted ride planning.

Local-first, no backend, no hosting cost. Native Android app built in Kotlin.

See [`BikeRyde_Design_Spec.md`](./BikeRyde_Design_Spec.md) for the full design and spec doc.

## Status

Early development. Build order follows the phases in the spec doc:

1. Core tracking + static graphics
2. Multi-day tracking + video export
3. Photo backgrounds + animation polish
4. AI ride planning
5. Navigation handoff
6. Google Drive sync

## Tech stack

- Kotlin + Jetpack Compose
- MVVM (ViewModel + StateFlow), Hilt for DI
- Room (SQLite) for structured data, app-scoped internal storage for photos/video
- `FusedLocationProviderClient` + `ForegroundService` for background GPS tracking
- `MediaCodec` / Media3 Transformer for hardware-accelerated video rendering
- MediaPipe LLM Inference API (Gemma) for on-device AI planning, with a cloud AI toggle
- Google Maps SDK for Android (route preview), Intent handoff to the Google Maps app for navigation
- Open-Meteo and OpenStreetMap Nominatim/Overpass for weather and places (free, no key required)
- Google Drive REST API for optional multi-device sync

## Requirements

- Android Studio (latest stable)
- JDK 17+
- Android SDK, target/compile SDK as set in `build.gradle.kts`
- A physical device or emulator running Android 10 (API 29) or later recommended, given the
  background location and foreground service work involved

## Getting started

```bash
# clone and open in Android Studio, or build from the CLI:

# build a debug APK
./gradlew assembleDebug

# install on a connected device/emulator
./gradlew installDebug

# run unit tests
./gradlew test

# run instrumented tests on a connected device
./gradlew connectedAndroidTest
```

## API keys

This app does not ship with any default or shared API keys. On first launch you'll be asked to
import existing data from Google Drive or start fresh and set up your own keys for the features
you want (weather, places, maps, cloud AI). Each field in Settings links to instructions for
getting that provider's key — see the FAQ section in the design spec. Keys are stored locally via
`EncryptedSharedPreferences` and are never logged or included in crash reports.

## Project structure

```
app/
  src/main/java/com/bikeryde/
    data/
      local/          # Room database, DAOs, entities
      remote/          # Retrofit clients (weather, places, cloud AI, Drive)
      repository/       # Repositories bridging local/remote data
    domain/
      model/            # Domain models
      usecase/           # Business logic (tracking, rendering, planning)
    service/
      tracking/          # ForegroundService + FusedLocationProviderClient logic
      render/             # MediaCodec/Media3 video + image rendering
      ai/                 # MediaPipe local inference + cloud AI client
    ui/
      tracking/           # Ride tracking screens
      summary/             # Ride summary + export screens
      fuel/                # Fuel log screens
      planner/             # AI ride planning screens
      settings/            # Settings, API key management, onboarding
      theme/               # Compose theme, colors, typography
    di/                  # Hilt modules
  src/main/res/          # Resources (icons, strings, etc.)
  src/test/               # Unit tests
  src/androidTest/         # Instrumented tests
```

## License

Personal project — license TBD.