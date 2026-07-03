# Bibo — Improvement Research

Deep-dive into frameworks, libraries, and techniques that could make Bibo better,
scoped to *this* app: personal, sideloaded, single-user, Samsung Galaxy Z Fold
(Android 16, AICore/Gemini Nano present), privacy-first, low-maintenance.

Every Maven coordinate below was verified to exist on Maven Central or Google Maven
(July 2026); versions are the latest **stable** unless marked. Where a researcher's
version claim was stale, the verified number is used here.

---

## TL;DR — the 10 highest impact-for-effort moves

| # | Move | Effort | Why it matters for Bibo |
|---|------|--------|--------------------------|
| 1 | **Fix per-app usage visibility** ✅ *(done this session)* | trivial | `<queries>` manifest block — Facebook/Telegram/etc. were invisible pre-Android-11 package-visibility. Stats went 4m → 1h47m. |
| 2 | **Semantic haptics everywhere** | half day | `HapticFeedbackType.Confirm/Reject/ToggleOn/SegmentTick` — no dep, no permission. Biggest "feels native" win per hour on the Fold. |
| 3 | **Gemini Nano voice parsing** (`genai-prompt`) | moderate | Turns "played tennis 5 to 7" → structured JSON on-device, no cloud key. Your phone runs Nano v2. Keep regex as fallback. |
| 4 | **Homescreen widget** (Jetpack Glance) | 2–4 days | Today's agenda + one-tap timer start on the homescreen. Highest UX payoff for a calendar/timer app. |
| 5 | **Swipe actions on task rows** (Material3 `SwipeToDismissBox`) | drop-in | Swipe-to-complete / swipe-to-delete + undo snackbar. Already in your BOM. |
| 6 | **Vico charts on Stats** (`vico:compose-m3`) | moderate | Real bar/donut/trend charts themed from your MaterialTheme automatically. |
| 7 | **Correct the usage algorithm** (instanceId + screen-off capping) | moderate | Your current session logic drifts from Digital Wellbeing on a foldable. See Usage section. |
| 8 | **Drag reorder tasks** (`sh.calvin.reorderable`) | moderate | De-facto standard, haptics + auto-scroll built in. |
| 9 | **Per-website time tracking** (Accessibility URL-bar, curbox patterns) | moderate | The feature you originally asked about — vendor the technique, no library exists. |
| 10 | **Custom splash + predictive-back verify** (`core-splashscreen`) | half day | Kills the blank launch frame; confirm Android 16 back-gesture animations don't regress. |

**Guiding rule that came out of the research:** almost nothing here is a "library you
add." The calendar timeline, usage tracking, and voice parsing all have *no maintained
drop-in library* — the win is copying proven techniques (like you already did vendoring
the week-view). Libraries genuinely worth adding are few: kizitonwose (dates), Vico
(charts), reorderable (drag), the ML Kit GenAI trio (voice), Glance (widget).

---

## Calendar & timeline

The big finding: **as of mid-2026 no maintained Compose library does drag-to-create /
drag-to-move / resize on a minute-granular timeline.** Confirmed across every candidate.
Build it as a gesture layer on the vendored week-view, don't go hunting for a library.

| Library | Verdict | Coordinate / version | Notes |
|---------|---------|----------------------|-------|
| [kizitonwose/Calendar](https://github.com/kizitonwose/Calendar) | **adopt** | `com.kizitonwose.calendar:compose:2.10.1` | Best-in-class. `WeekCalendar` can replace Bibo's hand-rolled date strip (stays scroll-synced with the pager); `HorizontalCalendar` month grid → a month+agenda page. No timeline — it complements the week-view. |
| [tobiasschuerg/android-week-view](https://github.com/tobiasschuerg/android-week-view) | **adopt (backport)** | upstream of your vendored code | Backport 2025 fixes into your copy: **4.1.1** pinch-zoom consumes multi-touch only (fixes zoom-vs-swipe); **4.1.2** locale date-pattern crash; **4.1.0** configurable event spacing. |
| [XCalendar](https://github.com/Debanshu777/XCalendar) | **consider (reference)** | — (no artifact) | MIT Google-Calendar clone, active May 2026, day/3-day/week/month/agenda + M3 Expressive. Best whole-app reference to mine for view-switching + schedule view. |
| [compose-dnd](https://github.com/MohamedRejeb/compose-dnd) | consider | `com.mohamedrejeb.dnd:compose-dnd` | Only for discrete drags (drag a task chip onto a day). Wrong tool for continuous timeline move/resize. |
| ComposeCalendar, Kalendar, WojciechOsak, compose-calendar-event, halilozercan/schedule | skip | — | Redundant with kizitonwose, or dormant/single-maintainer/no-releases. |

**How to build drag-to-move/create/resize** (the thing no library gives you):
- **Move:** `Modifier.pointerInput` + `detectDragGesturesAfterLongPress` per block; `minutes = dy / hourHeightPx * 60` with 15-min snap; render a translucent "ghost" + live time label; haptic `SegmentTick` on each snap; write to `CalendarContract` only on drag-end. Keep the tap handler separate so tap-to-detail still works.
- **Create:** long-press-drag on empty grid → ghost from press point snapped to :00/:15, height follows drag.
- **Resize:** 16–24dp invisible hit-zones at top/bottom edges with their own drag detectors (only for tall-enough blocks).
- Canonical reference for a from-scratch timeline: **Daniel Rampelt's 2-part series** (`danielrampelt.com/blog/jetpack-compose-custom-schedule-layout-part-1`).

---

## Usage tracking & Stats

**No library exists — every option is copy-the-technique.** Two things here: making your
existing per-app numbers *correct*, and adding *per-website* tracking.

### Make per-app usage match Digital Wellbeing

Your current code pairs RESUMED/PAUSED, which drifts on a foldable. The verified-correct algorithm:

1. **Always `queryEvents`, never `queryUsageStats`** — interval buckets don't align to local midnight and double-count on range sums. (You already do this. ✅)
2. **Key sessions by `(packageName, event.getInstanceId())`** — instanceId (API 29+) is *required* on the Z Fold, where split-screen/multi-instance spawns several activities per app.
3. **End a session on the first of `ACTIVITY_PAUSED / STOPPED / DESTROYED`** for that instanceId; tolerate unmatched/out-of-order ends.
4. **Hard-cap open sessions at `SCREEN_NON_INTERACTIVE` / `KEYGUARD_SHOWN` / shutdown** timestamps — otherwise screen-off creates multi-hour ghost sessions. Add a ~1h max-duration sanity cap.
5. **Multi-resume on foldables:** in split-screen both apps hold RESUMED at once, so per-app sums legitimately exceed wall-clock. Pick a policy (DW counts visible time per app) — this is the biggest Z-Fold-specific correctness issue.
6. **Persist into Room with a watermark** and re-query an overlapping 24–48h window each ingest (system prunes detailed events after a few days); dedupe by `(package, instanceId, timestamp, type)`. Filter noise: `STANDBY_BUCKET_CHANGED`, `CONFIGURATION_CHANGE`.
7. **Can't be closed:** work-profile / Samsung Secure Folder usage is invisible to `queryEvents` (single profile only), so Bibo will always read slightly under DW.
8. **Sideload gotcha to document:** Android 13+ "Restricted settings" blocks granting Usage Access / Accessibility to sideloaded apps until *App info → ⋮ → Allow restricted settings*.

### Per-website time (the feature you asked about)

**Recommendation: AccessibilityService URL-bar reading, not VPN.**

| Source | Verdict | What to take |
|--------|---------|--------------|
| [curbox](https://github.com/curbox-app/curbox-android) (GPL-3.0, active Jul 2026) | **adopt (patterns)** | The single best codebase to copy. `WebsiteUsageTracker.kt` (per-domain sessions + 15s heartbeat flush because Firefox/GeckoView doesn't fire URL-change events), `BrowserUrlBarIds.kt` (browser→URL-bar view-ID map), plus per-app tracking without UsageStatsManager. Vendor the technique. |
| [Accessibility-Browser-URL-Filter](https://github.com/Amir-yazdanmanesh/Accessibility-Service-Browser-URL-Filter) | consider | URL-bar IDs for browsers curbox misses — **Samsung Internet** (`com.sec.android.app.sbrowser:id/location_bar_edit_text`, important on your phone), Edge, DuckDuckGo. |
| [PCAPdroid](https://github.com/emanuele-f/PCAPdroid) | consider (external) | If you ever want *which domains were contacted* (not time-on-site): don't embed a VPN stack — install PCAPdroid and consume its UDP export API. |
| ActivityWatch aw-android, NetGuard, Rethink, Google's AppUsageStatistics sample | skip | Weaker watchers or heavyweight VPN stacks; Google's sample uses the wrong `queryUsageStats` method. |

**Why not VPN for time-on-site:** one VPN slot per device (conflicts with real VPNs/Private DNS), needs a userspace TCP/IP stack (huge native code), DoH hides DNS, TLS ECH is hiding SNI, and packet *timing* measures traffic not attention. VPN only wins for "which domains were contacted by which app."

**Best accuracy trick:** use your existing UsageStats browser foreground time as ground truth, and *distribute* it across the accessibility-observed domain timeline (last-seen-domain attribution) — self-corrects heartbeat gaps and fullscreen-video periods.

### Charts for the Stats page

| Library | Verdict | Coordinate / version | Notes |
|---------|---------|----------------------|-------|
| [Vico](https://github.com/patrykandpatrick/vico) | **adopt** | `com.patrykandpatrick.vico:compose-m3:3.2.3` | Only candidate that's both a safe long-term bet (Jun 2026 release, sponsored, real docs) and M3-native — its `compose-m3` module themes from your existing MaterialTheme with zero code. Covers stacked bars, weekly trend lines, and v3 donut. Pie API is experimental — fine for a personal app. |
| [ComposeCharts](https://github.com/ehsannarmani/ComposeCharts) | consider | `io.github.ehsannarmani:compose-charts:0.2.5` | Least code per chart; good lightweight fallback. Still 0.x. |
| KoalaPlot | consider | `io.github.koalaplot:koalaplot-core` | Only one with a built-in **heatmap**, but pre-1.0 with rename churn every release. |
| YCharts | skip | — | Archived May 2026. |

**Don't add a second chart lib for the heatmap.** An hour×weekday usage heatmap is ~40
lines of plain Compose: a grid of rounded `Box`es, `color = lerp(surfaceVariant, primary,
usage/max)`. Full control over tap-tooltips, no dependency.

---

## Voice — "played tennis from 5 to 7" → event

**Recommended pipeline:** hold-to-talk → transcript → **ML Kit GenAI Prompt API (Gemini
Nano)** → lenient JSON parse → sanity-check times → fall back to **ML Kit Entity
Extraction** → fall back to your existing regex. Wrap each layer behind an interface so
regex stays the permanent safety net.

| Library | Verdict | Coordinate / version | Notes |
|---------|---------|----------------------|-------|
| [ML Kit GenAI Prompt](https://developers.google.com/ml-kit/genai/prompt/android) | **adopt** | `com.google.mlkit:genai-prompt:1.0.0-beta2` | On-device Nano: transcript in, `{title,start,end}` JSON out, no cloud key. **Confirmed supported on Galaxy Z Fold7 (Nano v2).** No JSON-schema decoding — few-shot prompt + `temperature 0` + fixed `seed` + `maxOutputTokens 128`, strip ``` fences. Only runs while app is foreground (fine for hold-to-talk). Beta, no SLA. |
| [ML Kit Entity Extraction](https://developers.google.com/ml-kit/language/entity-extraction/android) | **adopt (fallback)** | `com.google.mlkit:entity-extraction:16.0.0-beta6` | Deterministic on-device DateTime extraction; `setReferenceTime()` anchors "yesterday at 5". Finds the 5 and 7; your code pairs them into a range. ~5.6MB model. |
| [ML Kit GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android) | consider | `com.google.mlkit:genai-speech-recognition:1.0.0-alpha1` | New on-device ASR (Jan 2026) — could replace stock SpeechRecognizer with one dep, Flow of partial→final. Basic mode API 31+. alpha1, Samsung quality unverified — flag it. |
| [Natty](https://github.com/natty-parser/natty) | consider | `io.github.natty-parser:natty:1.1.3` | Pure-JVM NL date parser, trivially unit-testable, zero model download. Overlaps ML Kit EE — pick one fallback, not both. |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | consider | — (AAR from releases) | Best "better STT" if stock recognizer bothers you. Prebuilt Android AAR, streaming Zipformer/Whisper. You manage model file + mic loop. |
| Microsoft Recognizers-Text, whisper.cpp, Vosk | skip | — | Best range-modeling parser but unpublished to Maven; whisper needs NDK vendoring; Vosk artifact frozen since 2023. |

**Free stock-recognizer upgrades:** `createOnDeviceSpeechRecognizer()` (API 31+) forces
offline; `EXTRA_ENABLE_FORMATTING` (API 33+) improves number/punctuation formatting
("5" vs "five" matters downstream). On Samsung, the plain factory may bind Samsung's
engine — the on-device factory picks the platform default.

---

## Tasks page

| Library | Verdict | Coordinate / version | Notes |
|---------|---------|----------------------|-------|
| Material3 `SwipeToDismissBox` | **adopt** | `androidx.compose.material3` *(in your BOM)* | Swipe-to-complete / delete. In 1.4.0 use the new `onDismiss(value)` param (`confirmValueChange` deprecated). |
| [Reorderable](https://github.com/Calvin-LL/Reorderable) | **adopt** | `sh.calvin.reorderable:3.1.0` | Drag-reorder for LazyColumn, auto edge-scroll, `animateItem`, drag-handle + haptic hooks. The standard. |
| [saket/swipe](https://github.com/saket/swipe) | consider | `me.saket.swipe:swipe:1.3.0` | Multiple swipe actions per side *without* dismissing the row (partial=snooze, full=complete). |
| [Tasks.org](https://github.com/tasks/tasks) | consider (reference) | — (GPL) | Best todo UX to crib: drag-to-indent nesting, overdue-chip-turns-red, collapsible Completed section, long-press multi-select. |
| ComposeReorderable, Bonsai | skip | — | Superseded / dormant / overkill for 1–2 level subtasks. |

**Key patterns:**
- **Don't nest lazy lists for subtasks** — flatten to one `LazyColumn` of `Row(task, depth)`, indent by `depth*16.dp`, omit collapsed children. Stable keys + `Modifier.animateItem()` animate expand/collapse for free.
- **Undo snackbar, don't hard-delete on swipe** — mark pending-delete, `showSnackbar("Undo")`, commit on dismiss. **Gotcha:** `SwipeToDismissBox` state is `rememberSaveable`, so after undo a re-inserted row can instantly re-delete — create the swipe state inside `key(task.id) {}`.
- **Reminders (sideloaded = easy mode):** two options — (a) write the due date to Google Calendar via `CalendarContract` + a `Reminders` row and let Google fire it (zero background code, syncs everywhere); or (b) `USE_EXACT_ALARM` (auto-granted at install, skips the Android 14+ runtime-grant dance) → `AlarmManager.setExactAndAllowWhileIdle` → notification with Complete/Snooze actions + a boot receiver to reschedule. **Never WorkManager for exact reminders** — it batches with 15-min flex.

---

## Polish (mostly zero new dependencies — already in your BOM)

| Item | Verdict | Coordinate / version | Notes |
|------|---------|----------------------|-------|
| Semantic haptics | **adopt** | `androidx.compose.ui` *(1.11.4 in BOM)* | ~12 semantic types since ui 1.8: `Confirm/Reject/ToggleOn/Off/GestureStart/SegmentTick`. No permission. **Half-day, highest polish payoff.** |
| Shared-element transitions | **adopt** | `androidx.compose.animation` *(in BOM, now stable)* | Morph event block→detail, chip→running timer. **Gotcha:** can't cross windows, so a block→ModalBottomSheet morph won't work — use an in-pane `AnimatedContent` overlay. |
| [Jetpack Glance widget](https://developer.android.com/jetpack/androidx/releases/glance) | **adopt** | `androidx.glance:glance-appwidget:1.1.1` | Homescreen agenda + one-tap timer start. `GlanceTheme.colors` = Material You match. Compiles to RemoteViews (no Canvas). **Biggest UX payoff on the list.** |
| Quick Settings tile | **adopt** | — (framework) | Toggle the timer from the shade. `requestAddTileService()` (API 33+) installs it from in-app. |
| Predictive back | **adopt (verify)** | `androidx.activity:activity-compose` *(in set)* | On by default at targetSdk 36 — mainly verify nothing regresses. Very visible on the Fold's big screen. |
| [SplashScreen](https://developer.android.com/jetpack/androidx/releases/core) | **adopt** | `androidx.core:core-splashscreen:1.2.0` | `setKeepOnScreenCondition` until Room + first calendar query resolve — kills the blank frame. |
| Material 3 Expressive | consider | `androidx.compose.material3:1.5.0-alpha23` | `MaterialExpressiveTheme` + springy `MotionScheme`, `FloatingToolbar`, `FAB Menu`, `ButtonGroup` (day/3-day switcher). **Not in 1.4.0 stable** — only the 1.5.0-alpha line. Most APIs graduated non-experimental by alpha23, so 1.5.0 stable is close. Wait one cycle or pin the alpha. |
| M3 Adaptive (Fold layouts) | consider | `androidx.compose.material3.adaptive:adaptive:1.2.0` | Arguably highest UX-per-effort on *this device*: `ListDetailPaneScaffold` → Tasks list+detail side-by-side when unfolded; `NavigationSuiteScaffold` → nav rail when expanded; default 3-day calendar when unfolded. |
| Navigation 3 | consider | `androidx.navigation3:navigation3-ui` | Only if hand-rolled tab switching causes pain — otherwise a rewrite with modest payoff. |
| Per-app language | skip | — | Plumbing with no payoff for a single-user app. |

**Bonus cheap delight:** `androidx.graphics:graphics-shapes` morphs polygonal shapes — a
play↔stop morphing timer button is ~50 lines. Everything here is on-device/offline.

---

## Suggested next-week plan

Ordered by payoff-per-hour, front-loading the cheap wins:

1. **Half day —** semantic haptics across hold-to-talk, timer toggle, task complete, calendar snap. Instant "feels native."
2. **Half day —** custom splash (`core-splashscreen`) + verify predictive-back doesn't regress.
3. **1 day —** swipe-to-complete/delete on task rows + undo snackbar (Material3, already in BOM).
4. **1 day —** correct the usage algorithm (instanceId keying + screen-off capping) so Stats matches Digital Wellbeing, and cache daily aggregates in Room.
5. **1–2 days —** Vico on the Stats page (bars + weekly trend) + a hand-rolled usage heatmap.
6. **2–3 days —** Gemini Nano voice parsing behind a flag, regex as fallback.
7. **2–4 days —** Glance homescreen widget (agenda + start-timer).
8. **Later —** per-website tracking (curbox Accessibility patterns), drag-reorder tasks, timeline drag-to-move, M3 Expressive when 1.5.0 ships stable, adaptive Fold two-pane layouts.

**What NOT to do:** don't hunt for a timeline drag library (none exists), don't add a VPN
for website *time* tracking, don't run two chart libraries, don't use WorkManager for
exact reminders, don't add a second calendar library beyond kizitonwose.

---

## Integrations & robustness

### CalendarContract write-back

- **Safe-to-update columns** on a synced event: `TITLE`, `EVENT_LOCATION`, `DESCRIPTION`,
  `AVAILABILITY`, guest flags. **Never change `CALENDAR_ID`** after insert (breaks sync).
- **Times:** non-recurring → set `DTSTART` + `DTEND` (no `DURATION`). **Recurring → set
  `DTSTART` + `DURATION` (e.g. `"P3600S"`) + `RRULE`, and `DTEND` must be null.** Getting
  this wrong is the #1 "event won't sync" cause. Always set `EVENT_TIMEZONE` on insert.
  - ⚠️ **Applies to Bibo now:** the Edit-time feature's `updateEvent()` writes
    `DTSTART`+`DTEND`, which is correct for normal events but **would corrupt a recurring
    event** if edited. Guard: read `RRULE`; if non-empty, edit the single instance via
    `CONTENT_EXCEPTION_URI` (below) instead of the parent row.
- **Colors:** `EVENT_COLOR_KEY` **is** app-writable — set it to a key from the `Colors`
  table for that account (`COLOR_TYPE_EVENT`); the provider fills `EVENT_COLOR`. Google
  only accepts its own palette, so query `Colors` and reuse a key. `DISPLAY_COLOR` is
  read-only — never write it.
- **Writable calendar:** filter `Calendars` on `CALENDAR_ACCESS_LEVEL >= 500`
  (CAL_ACCESS_CONTRIBUTOR), `ACCOUNT_TYPE = "com.google"`, `IS_PRIMARY = 1`.
- **Delete:** a normal `delete()` sets `_deleted=1` and lets the sync adapter propagate to
  Google (correct for user deletes). Only `CALLER_IS_SYNCADAPTER=true` hard-removes — don't.
- **Recurring single instance:** don't touch the parent. Insert into
  `Events.CONTENT_EXCEPTION_URI` (parent id appended) with `ORIGINAL_INSTANCE_TIME` =
  the occurrence start; `STATUS_CANCELED` deletes just that instance, or new values modify
  it. Map "the tapped occurrence" back via the `Instances` table (`EVENT_ID` + `BEGIN`).
- **Reminders:** insert into `Reminders` (`EVENT_ID`, `MINUTES` before start,
  `METHOD_ALERT`). Model a task due-time as the event's `DTSTART`.

### Room backup / export

- **WAL gotcha:** Room defaults to WAL → the live DB is 3 files (`.db`, `-wal`, `-shm`).
  Copying only `.db` yields a **corrupt** backup. Fix: `PRAGMA wal_checkpoint(TRUNCATE)`
  before copying, or `db.close()` first (checkpoints + removes side files), or build with
  `setJournalMode(JournalMode.TRUNCATE)` (single file; tiny write cost, fine for one user).
- **JSON export/import:** use **kotlinx.serialization** (`@Serializable` DTOs) — Kotlin-first,
  compile-time, no ProGuard/Gson null pitfalls. Write via the Storage Access Framework
  (`ACTION_CREATE_DOCUMENT`, no storage permission). This doubles as a migration-proof backup.
- `android:allowBackup` already covers the DB, but a stale backup restored into a newer
  schema crashes — version the backup rules or exclude the DB and rely on manual export.

### Foreground-service timer — **the `specialUse` choice is validated ✓**

- There's no "stopwatch" FGS type; an open-ended user timer correctly uses **`specialUse`**,
  not `shortService` (which has a ~3-min hard cap → `onTimeout()`/ANR, can't be sticky).
- Needs `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`,
  `foregroundServiceType="specialUse"`, and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property
  — **all already in Bibo's manifest.** Play Console review of the subtype is Play-only;
  irrelevant sideloaded.
- `POST_NOTIFICATIONS` still required (Android 13+); if denied the service runs but the
  notification is hidden. `setUsesChronometer(true)` for the live timer — already done.
- No 2024–2026 change removes `specialUse`; Android 15's FGS timeouts don't touch it.

### Open-source prior art

No single OSS app combines Google-Calendar read/write **and** screen-time — that's Bibo's
niche. Mine separately: **[Etar](https://github.com/Etar-Group/Etar-Calendar)** (Kotlin,
AOSP-derived) is the reference for real-world `CalendarContract` read/write, colors, and
recurring-event exceptions. **Screen Time** (Markus Fisch) and **lubenard/Digital_wellbeing**
are clean `UsageStatsManager` references.

---

*Research method: 7 parallel research agents (calendar, usage, voice, tasks, charts,
polish, integrations) sweeping the 2025–2026 Android/Compose ecosystem via web search +
fetching repos/docs to confirm maintenance dates, then a Maven-coordinate verification
pass. The initial run was cut short by a session limit; the 46 findings were recovered
from the agent transcripts with coordinates re-verified by hand, and the integrations
section was completed in a follow-up pass. Every section reflects the shipped
implementation as of 2026-07-02.*
