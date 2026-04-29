# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build (debug)
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device or emulator)
./gradlew connectedAndroidTest

# Run a single unit test class
./gradlew test --tests "com.example.birdstrace.ExampleUnitTest"

# Lint
./gradlew lint

# Full clean build
./gradlew clean assembleDebug
```

## Project Overview

BioScope is a single-module Android app (`com.example.birdstrace`).

- **Min SDK:** 24 (Android 7.0), **Target/Compile SDK:** 36
- **Language:** Kotlin, **Java compatibility:** 11
- **Theme:** Material Components DayNight

## Architecture

The project is in its initial state — no Activities, Fragments, or business logic have been added yet. The manifest declares the application but no components.

**Test locations:**
- Unit tests: `app/src/test/java/com/example/birdstrace/`
- Instrumented (Espresso) tests: `app/src/androidTest/java/com/example/birdstrace/`

**Dependency management:** Centralized via `gradle/libs.versions.toml` (version catalog). Add new dependencies there first, then reference via `libs.<alias>` in `app/build.gradle.kts`.

**Core dependencies:** `androidx.core-ktx`, `androidx.appcompat`, `com.google.android.material`.