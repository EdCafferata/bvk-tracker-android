# BVK Tracker (Android)

🔒 Laatste security check: 2026-08-04 16:34 CEST

Android port of [BVK GPX Tracker](https://github.com/EdCafferata/BVK-GpxTracker), a free GPX tracker originally built for the Blocq van Kuffeler (WSV Blocq Kuffeler).

Native Kotlin + Jetpack Compose. This is **Milestone 1** of the Android port — the core tracker. It mirrors the iOS app's map + GPS recording + GPX export, not (yet) the weather/water-level overlays, scale bar, full preferences screen, or Watch companion app. See the project's own scoping notes for the full roadmap.

## Status

**Working (Milestone 1):**
- Map view (osmdroid) with switchable base layers: OpenStreetMap, Carto DB, Carto DB Retina, OpenTopoMap, Carto DB Dark Matter — the free/open-source equivalent of the iOS app's `GPXTileServer` list (Apple Maps/Apple Satellite have no Android equivalent and are dropped; OSM is the default base layer instead)
- OpenSeaMap sea-marks overlay toggle
- Live GPS tracking via a foreground service (works in the background, matching the iOS app's `allowsBackgroundLocationUpdates`) — uses the plain platform `LocationManager`, not the Play Services fused location client, so it works on every Android device without a Google Play Services dependency
- Start/stop recording, live distance + speed readout, "follow my location" toggle
- Save the recorded track as a standard `.gpx` file, share via the system share sheet
- Saved-tracks list with delete
- Verified end-to-end on the emulator with simulated GPS movement: map loads real tiles at the Blocq van Kuffeler coordinates, tracking records points, saved GPX file is valid, share sheet and delete both work

**Not yet ported (see the project roadmap for the planned milestones):**
- Live weather (wind, Beaufort, temperature, waves) via Open-Meteo
- Water level (RWS DDAPI, Markermeer)
- OpenWeatherMap tile overlays + legend
- Custom scale bar
- Full preferences screen (imperial units, track interval, charger mode, activity type, ...)
- Speed-based GPS accuracy profiles / GPS watchdog
- Wear OS companion (the iOS app has a watchOS companion; no Android equivalent planned unless requested)

## Requirements

- JDK 17+ (project built and tested with Homebrew's `openjdk@21`)
- Android SDK, compileSdk 36, minSdk 26
- Gradle 9.6.1+ (via the included wrapper)

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools   # or your own SDK path
./gradlew assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Licence

GPL-3.0 — see [LICENSE](LICENSE), same as the iOS app.
