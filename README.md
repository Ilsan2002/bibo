# Bibo

A personal productivity app for Android — calendar, screen-time stats, activity timer,
voice logging, and a to-do list, all backed by local storage. Built for one user, sideloaded
(no Play Store).

## Features

- **Calendar** — Google-Calendar-style day / 3-day timeline. Shows:
  - Real events from the phone's Google Calendar (read + write via `CalendarContract`).
  - Colored app-usage blocks (which app, when, how long), rendered translucent.
  - Manually added events (＋ button) and voice logs.
  - Tap any block → detail sheet with **Edit time** (Material date/time pickers, updates
    Google Calendar or the local block), Open, and Delete.
  - Hold-to-talk mic: speak "played tennis from 5 to 7" → parsed into a time block by
    **Gemini Nano** on-device (falls back to ML Kit Entity Extraction, then regex).
  - Swipe between days, tap a date chip to jump, pinch-zoom the timeline.
- **Stats** — per-app screen time for any day + a **7-day trend chart** and an
  **hour-by-day usage heatmap**. Optional **per-website time** (via an accessibility
  service that reads the browser address bar; toggle it on from the Websites card).
- **Timer** — name an activity, start/stop; runs as a **foreground service** with an
  ongoing notification (live elapsed time + Stop). Recent-activity chips, today's history.
- **Tasks** — subtasks, per-task timer, **swipe to complete / delete with undo**,
  **drag-to-reorder**, done-today + collapsible completed archive. Completed/timed tasks
  land on the calendar.
- **Focus Mode** (Timer tab) — set an intention, link a goal, pick apps to **block**
  (accessibility overlay bounces you back), optional **Pomodoro** cycles and **auto-DND**;
  ends with a reflection note saved to the calendar block.
- **Daily** — voice-log what you ate (Gemini Nano estimates calories / sugar / caffeine),
  tick daily habits (shower, clean clothes, workout, prayer), weekly dot strip, and an
  AI **day review** card.
- **Goals** — long-term goals as colored folders of tasks; progress summary cards,
  colored dots on the calendar's date strip, and a daily goal-spotlight notification.
- **Mentor** — a Claude-powered coach (Anthropic API, key stays on-device) that sees your
  goals, focus sessions, habits, intake, calendar, and screen time. Continuous across days
  via layered memory: raw chat (2 days) + model-written per-day digests + an evolving
  memory-notes document rewritten at each day rollover. **It can act**, not just talk —
  create tasks (auto-broken into the smallest first steps and filed under the right goal),
  create goals, add calendar events, and set goal-framed reminders — via Claude tool use.
  It also **checks in on its own** each evening (9:30pm) grounded in the day's data.
- **Goal profiles** — tap a goal to see its "why," a progress bar, target countdown, next
  step, and every task/subtask with tap-to-complete.
- **Two-pane on the Fold** — unfolded, the Calendar and Tasks tabs show both side by side.
- **Homescreen widget** (Jetpack Glance) — today's agenda + one-tap timer start.
- **Quick Settings tile** — toggle the activity timer from the shade.
- **Polish** — semantic haptics throughout, themed splash screen, adaptive navigation
  (bottom bar folded → nav rail unfolded).

## Tech

- Kotlin + Jetpack Compose + Material 3
- Room (local database) — all data stays on the device
- Calendar timeline: [tobiasschuerg/android-week-view](https://github.com/tobiasschuerg/android-week-view)
  (MIT), **vendored** into `app/src/main/java/de/tobiasschuerg/weekview/` and patched:
  compact hour labels, auto-scroll to now, adaptive event-block text, hairline grid,
  optional day header, bottom content padding. Don't re-add the JitPack dependency —
  it would clash with the vendored classes.
- On-device `SpeechRecognizer` for speech-to-text; **ML Kit GenAI (Gemini Nano)** +
  **ML Kit Entity Extraction** for parsing phrases into events (all on-device, no keys)
- Charts hand-rolled in Compose (Vico is also a declared dependency for future use)
- **Jetpack Glance** widget, **Quick Settings** `TileService`, **AccessibilityService**
  for per-website time, foreground `TimerService`, `sh.calvin.reorderable` for task drag
- **Anthropic Java SDK** (`com.anthropic:anthropic-java`) for the Mentor chat —
  `claude-opus-4-8`, adaptive thinking; needs `packaging { resources { excludes } }`
  for its Apache-jar `META-INF` files on Android
- Data layer: Room `bibo.db` (v11) — activity_blocks, todo_tasks, usage_sessions,
  website_sessions, habit_days, food_entries, goals, chat_messages, chat_days;
  migrations preserve data across upgrades

## UX notes

- Calendar: swipe between days (HorizontalPager), tap a date chip to jump, tap any block
  for details/delete, pinch to zoom the timeline. Usage blocks render translucent to
  stand apart from planned events.
- Known quirk: the Desktop folder is iCloud-synced, which occasionally drops
  " 2"/" 3"-suffixed duplicate files into `app/build/intermediates` and breaks resource
  parsing. Fix: `rm -rf app/build/intermediates/packaged_res` and rebuild.

## Permissions

| Permission | Why | How it's granted |
|-----------|-----|------------------|
| Calendar (read/write) | show + save Google Calendar events | normal runtime prompt |
| Usage Access | per-app screen time | manual toggle in Settings (app opens the screen for you) |
| Microphone | voice logging | normal runtime prompt |
| Notifications | running-timer notification | normal runtime prompt (Start timer) |
| Accessibility | per-website time + focus-mode app blocking | manual toggle in Settings (Stats → Websites → Enable) |
| Display over other apps | focus-mode blocking overlay | prompt from Focus setup |
| Do Not Disturb access | auto-DND during focus sessions | prompt from Focus setup |
| Internet | Mentor chat (Claude API) — the only network feature | install-time, automatic |

On Android 13+, sideloaded apps hit **"Restricted settings"** when granting Usage Access
or Accessibility — if the toggle is greyed out, open *App info → ⋮ → Allow restricted
settings* first. Samsung **Auto Blocker** (Settings → Security and privacy) must be off to
sideload/USB-debug.

## Build & install

Requires Android Studio's SDK (already at `~/Library/Android/sdk`) and JDK 17.

```sh
# build a debug APK
./gradlew assembleDebug

# install on a USB-connected phone (USB debugging on)
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper is pinned to 8.14.3 (Homebrew's Gradle 9.x is too new for the Android plugin).

## Ideas / next steps

- **Timeline drag-to-move / create / resize** — deferred. Edit-time via the detail sheet
  covers move/resize reliably; a drag gesture on the vendored timeline needs hands-on
  testing (it writes to the real Google Calendar) and is the natural next feature.
- Room DB export/import (JSON via kotlinx.serialization + Storage Access Framework) +
  WAL-safe backup (checkpoint before copying the `.db`).
- Editing **recurring** Google events: currently refused (a naive DTSTART+DTEND write
  would break their sync) — needs the `CONTENT_EXCEPTION_URI` single-instance path.
- Event colors on write via `EVENT_COLOR_KEY` (from the account's `Colors` palette).
- App-category grouping in Stats; weekly/monthly aggregates.
- M3 Expressive theming once material3 1.5.0 ships stable.
- Fold two-pane (ListDetailPaneScaffold) for Tasks on the unfolded screen.

See [docs/RESEARCH.md](docs/RESEARCH.md) for the full framework study behind these.
