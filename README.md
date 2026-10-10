# Bibo

Bibo is a local-first Android app for planning, tasks, focus sessions, daily logs, and screen-time history. Mentor is an optional cloud assistant. The app is intended for sideloading.

## Everyday workflows

- **Calendar:** local plans and device-calendar events have explicit destinations. View actual sessions, completion milestones, usage, and plans with separate source controls or an accessible agenda. Editors retain full start/end dates, all-day dates, time zones, and drafts. Recurring events and exceptions open the calendar app for changes.
- **Tasks and goals:** create goals and tasks locally, add steps, edit due dates/reminders/rewards, reorder, archive goals, and recover deleted tasks. Completing a parent explains remaining-step completion. Undo reverses only its own action. Untimed completion is a milestone; it does not invent work time.
- **Focus:** one active session, shared across task rows, the Focus screen, notifications, widget, tile, and Mentor. Stop saves work immediately; reflection attaches to that ended session. Pomodoro breaks are recorded separately. App blocking and Do Not Disturb show actual readiness and remain optional.
- **Daily:** review editable activity/meal drafts before saving. Nutrition supports explicit manual values and labelled local estimates. Habits distinguish done, skipped, and not logged. Choose applicable habits and optional personal targets. Date changes and log edits invalidate pending recaps.
- **Stats:** saved history remains readable without collection access. Headlines and charts use clipped interval unions, local-day boundaries, and coverage indicators. Website sampling is a separate opt-in from app blocking; it can be paused and its history deleted.
- **Mentor:** connect an OpenRouter key, select shared categories, and choose automatic check-ins/quiet hours. Current requests use GLM through OpenRouter. Requests can incur API charges. Durable turns show pending, failure, cancellation, and applied actions. Local changes use the same commands as the UI; retries retain identity. Memory has version history and user corrections.
- **Settings and recovery:** inspect capability status, manage optional features, and export or inspect/restore a verified local archive through Android's document picker. Exported files contain private history and are not encrypted by Bibo. API keys and permission grants are excluded. Backup maintenance ends active work and requires reopening the app.

## Data and permissions

Room database version 13 stores sessions, segments, task completion events, immutable reward entries, health logs, coverage, command receipts, chat, and memory revisions. Additive migrations preserve existing records. Legacy TODO durations remain ambiguous and are excluded from measured work. Old false habit values become unknown; reward history starts from a labelled legacy snapshot. Invalid legacy task relationships are repaired without deleting tasks, and original links are retained in an upgrade report.

Rewards use USD cents, a local Monday-to-Monday budget, no carryover, and one credit per task for its lifetime. Reopening, editing, or removing a task does not rewrite money already earned or spent.

| Access | Purpose |
|---|---|
| Calendar read/write | Read device events and save to a selected writable calendar |
| Usage Access | Collect app usage; saved history remains readable when disabled |
| Microphone | Voice input; typing/manual entry is always available |
| Notifications | Task/goal reminders and running-session controls |
| Accessibility | Optional supported-browser website sampling and focus app blocking |
| Overlay | Optional blocking overlay; blocking can fall back to Home |
| Do Not Disturb | Optional focus silencing with session ownership |
| Internet | Explicit Mentor requests, enabled automatic Mentor work, or an explicitly chosen speech provider |

Voice uses an available on-device recognizer by default. A network recognizer requires separate opt-in. Activity and food parsing use deterministic bounded local draft parsers; unsupported input requires correction or manual entry. User-triggered day recaps can use an already available Gemini Nano model; they never download a model and fall back to recorded facts.

Mentor keys are encrypted with Android Keystore-backed AES-GCM and excluded from data backup/transfer. Messages, selected records, and memory are sent to OpenRouter and its model provider when Mentor is used. Disconnect cancels outstanding transport and automatic work. Website recording starts disabled.

Android can restrict sensitive settings for sideloaded apps. If a required toggle is unavailable, check App info → Allow restricted settings on the device. Available recognizers, browsers, calendar accounts, and OEM background policies vary; Settings reports capability state.

## Build and verify

Use JDK 17 and Android SDK 36. Set `ANDROID_HOME` or supply your own untracked `local.properties`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The wrapper pins Gradle 8.14.3. CI builds, runs lint/unit tests, and runs Room/workflow tests on an API 36 emulator. Exported Room schemas are in `app/schemas`. Migration tests use the released generated v12 SQL schema and representative v2–v11 fixtures; historical user databases were not available.

The calendar component is the MIT-licensed [android-week-view](https://github.com/tobiasschuerg/android-week-view), vendored under `de/tobiasschuerg/weekview`. Its local modifications cover stable event identity, midnight and DST rendering, overlap, gestures, and scroll bounds. Do not add a second dependency containing the same classes.

See [the implementation report](docs/IMPLEMENTATION.md) for decisions, regression coverage, and device validation limits.
