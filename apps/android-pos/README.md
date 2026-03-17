# AIROS Android POS Foundation

Native Android POS foundation for AIROS using Kotlin and Jetpack Compose.

## Scope

- Android-only POS client foundation
- Compose-only UI
- Offline-first repository shape with queued writes
- Room database skeleton
- DataStore preferences wrapper
- Retrofit and OkHttp network foundation
- Device abstractions for printer, scanner, camera preview, cash drawer, and platform info
- Sunmi placeholder adapters kept behind interfaces
- Fake local data so the app can launch without a backend

## Project Structure

- `app`: application shell, navigation, container wiring, fake repositories
- `core/common`: shared utilities and result/dispatcher helpers
- `core/ui`: design system baseline and reusable POS composables
- `core/network`: Retrofit and OkHttp foundation, TODO-CONTRACT API surface
- `core/database`: Room entities, DAOs, database bootstrap
- `core/datastore`: DataStore wrapper for terminal preferences
- `core/model`: shared domain models
- `domain`: repository contracts
- `feature/*`: screen state, ViewModels, and Compose screens
- `device/*`: device service interfaces and Sunmi placeholder adapters
- `sync`: queued write primitives and sync coordinator placeholder

## Setup

1. Open `apps/android-pos` in Android Studio Hedgehog or newer.
2. Ensure JDK 17 is configured for Gradle.
3. Install Android SDK Platform 35 and Android 13 emulator images if needed.
4. Sync the Gradle project.
5. Run the `app` module on an Android 13 tablet or emulator.

## Notes

- `TODO-CONTRACT` marks backend contract gaps that should be filled once Android-specific sync/API details are finalized.
- `TODO-DEVICE` marks vendor SDK integration points for Sunmi or future device vendors.
- The current foundation uses fake repositories for runtime behavior while preserving repository and device abstractions for later production implementations.
