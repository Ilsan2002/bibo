> Historical audit of commit `8e63d9d`, before the repairs. See [the implementation report](../IMPLEMENTATION.md) for current behavior and validation.

# Calendar, editor, and temporal-model audit

Scope: `CalendarScreen.kt`, `DeviceCalendarRepo.kt`, `EventEditorSheet.kt`, `WeekDataBuilder.kt`, all vendored `de/tobiasschuerg/weekview` files, the activity-block DAO/schema, and relevant usage/voice integration. Read-only audit: no application source or tracked files changed. No `AGENTS.md` was found in the checked ancestor paths or repository. Emulator testing is owned by the main reviewer; device-specific statements below explicitly identify the remaining verification.

## What the feature is trying to do

The calendar is both an agenda and an activity diary. It merges externally synchronized device-calendar appointments, manually planned events, voice activity logs, timer/focus sessions, completed-task markers, and app-usage observations into one day/3-day timeline. The same detail/editor UI acts on objects with different persistence and semantics. That is useful for a single-user product, but a planner, a factual history, and an imported calendar need different mutation rules. Much of the fragility comes from flattening those rules into one display model and one save callback.

## Ranked findings

### CAL-01 · P1 · Deleting a recurring occurrence deletes the series

**Certainty:** code-confirmed operation; verify provider synchronization on a device with a disposable recurring series.

**Trigger:** create a daily recurring event in the phone calendar, tap one displayed occurrence in Bibo, choose Delete, and accept the generic confirmation.

**Evidence:** `DeviceCalendarRepo.kt:35–49` reads `Instances.EVENT_ID`, so every occurrence carries the parent event ID. `CalendarScreen.kt:375–379` passes that ID to `deleteEvent`. `DeviceCalendarRepo.kt:111–117` deletes `Events/{eventId}`; it does not create a cancelled exception for the selected instance. `CalendarScreen.kt:683–697` only says this deletes “the event,” with no occurrence/series choice. Recurring edits are explicitly guarded at `DeviceCalendarRepo.kt:82–83`, but deletion has no corresponding guard.

**Impact:** a user intending to remove today's meeting can remove the entire repeating meeting from their synchronized calendar. The write crosses the app boundary and cannot be restored with an app Undo.

**Fix:** carry recurrence and instance identity in the domain object; offer explicit “This occurrence” / “Entire series” actions, or send recurring mutations to the calendar application until exception semantics are implemented. For occurrence cancellation use the provider's supported exception contract and original-instance time. Inspect read/write access before exposing the action.

**Regression scenarios:** single event; daily/weekly recurring event; selected exception; first and later occurrences; declined/failed provider write; verify other occurrences remain after “This occurrence.”

### CAL-02 · P1 · A title-only edit truncates existing multi-day intervals

**Certainty:** source and Java-time probe confirmed.

**Trigger:** open an event or logged block starting Saturday 09:00 and ending Monday 10:00, change only its title, save.

**Evidence:** `CalendarScreen.kt:560–575` converts the original start/end into only one date and two `LocalTime` values. `EventEditorSheet.kt:49–56` cannot represent an end date. `CalendarScreen.kt:315–318` reconstructs the end as the start date when end time is later, otherwise the next day. The original 49-hour event becomes a one-hour event. A 48-hour interval becomes 24 hours.

**Impact:** apparently harmless edits destroy duration/history and synchronized appointment dates. Ordinary overnight intervals under 24 hours survive; intervals spanning more than one midnight do not.

**Fix:** retain start and end date/time or instants throughout editing, show the end date and duration, and preserve all unedited fields. Use separate timed-event and all-day-event editors. Refuse a save that unintentionally changes a date span.

**Regression scenarios:** 49-hour title-only edit; 48-hour interval; 23:00–01:00 interval; event starting before the visible window; editing a continuation segment must modify the full original interval without shortening it.

### CAL-03 · P1 · Event tap handlers can retain old item snapshots after edits

**Certainty:** confirmed stale-capture pattern in Compose; visible reproduction requires UI validation.

**Trigger:** edit a local block's title/time, tap its updated rendering, or insert/delete a provider event so instance indices change.

**Evidence:** `EventCompose.kt:84–88`, `AllDayEventsRow.kt:53–56`, and `MultiDayEventsRow.kt:68–71` use `pointerInput(event.id)` while the long-running gesture callback captures `event` and `onEventClick`. None uses `rememberUpdatedState`, nor a key that changes with the callback/item snapshot. `CalendarScreen.kt:527–551` recreates the callback with a newly built `items` map. `WeekDataBuilder.kt:68–69` and `103–104` derive device/usage IDs from list indices rather than stable provider/session identity.

**Impact:** updated text/geometry can coexist with old detail/edit data. After index reassignment, a displayed item may open an earlier item and expose mutation of the wrong calendar object. For local blocks, a second edit can start from stale title/time and overwrite a preceding change.

**Fix:** use `clickable`/`combinedClickable` with current callbacks and accessible semantics, or `rememberUpdatedState` inside gesture detectors. Use stable keys derived from source ID and occurrence start; pass only that identity into the action and resolve the current domain object before mutation. Key event loops by stable item identity rather than composition position.

**Regression scenarios:** edit then tap without changing tabs; edit twice; provider insert/delete before an existing event; same ID with changed title/date/time; all-day event rename; switch date while a sheet is open.

### CAL-04 · P1/P2 · All-day spans vanish after their first day; timed editing can violate all-day invariants

**Certainty:** display loss code-confirmed; malformed provider-write consequences require a provider test.

**Trigger:** a three-day all-day holiday; view day 2 alone or in the 3-day view. Separately, open an all-day event and adjust its clock time.

**Evidence:** `WeekDataBuilder.kt:70–85` reads the start's UTC date, emits exactly one `Event.AllDay`, and ignores the event's exclusive end. It drops the whole event when that first date lies outside the displayed range. A capable `Event.MultiDay` model/renderer exists (`Event.kt:37–49`, `MultiDayEventsRow.kt:53–57`) but the app builder never emits it. `CalendarScreen.kt:570–575` and `651–657` offer the same timed editor for all-day events; `DeviceCalendarRepo.kt:84–88` changes timestamps without checking/maintaining `ALL_DAY` and UTC-midnight/date-span constraints. Details at `CalendarScreen.kt:608–641` convert all-day UTC midnight to local time, so in a western timezone the shown start date may be the preceding day.

**Impact:** the app silently hides active all-day obligations. The detail view makes an all-day item look like a midnight appointment or an appointment on the prior day. Clock edits can create malformed all-day rows, shift the day, be rejected, or synchronize unexpectedly depending on provider behavior. A title-only one-day edit in a constant-offset timezone usually preserves timestamps; the defect is the unsupported editing shape and lost span, not an assertion that every title edit corrupts all-day events.

**Fix:** model all-day events as UTC-backed local dates with exclusive end, clip their date range to the view, render continuations, and expose an all-day date-range editor. Preserve original calendar timezone/ALL_DAY values and hide unsupported operations.

**Regression scenarios:** one/multiple days; continuation with start outside view; UTC−8 and UTC+6; date-only move across DST; title-only save preserves all-day dates; clock-time edit cannot be entered on an all-day event.

### CAL-05 · P2 · Today and date selection can fail after swiping

**Certainty:** code-confirmed state transition.

**Trigger:** enter Calendar while anchored to today, swipe to tomorrow, tap Today. Also tap today's chip after the swipe.

**Evidence:** the visible date comes from anchor plus `pagerState.currentPage` (`CalendarScreen.kt:179–183`). Today only reassigns `anchorEpochDay = LocalDate.now().toEpochDay()` (`197–199`); chip selection only reassigns the anchor (`234`). If that date already equals the anchor, Compose receives no state change, the enclosing `key(daysVisible, anchorEpochDay)` does not reset, and the pager remains on tomorrow.

**Impact:** a basic navigation affordance appears broken after another valid interaction, closely matching the user's description.

**Fix:** make selected/visible date the single source of truth, or explicitly scroll the pager to its center for Today and date selection even when the anchor is unchanged. Preserve scroll/zoom through intentional navigation.

**Regression scenarios:** swipe once/many times then Today; return to original anchor through a chip; Day↔3 days; today across midnight; selecting the already visible date remains a no-op with clear feedback.

### CAL-06 · P1/P2 · Temporal conversion can save negative intervals or compress real DST activity

**Certainty:** executed exact Java-time save conversion and compiled app clipping logic.

**Trigger:** select 02:30–03:00 on a spring-forward date; record a session crossing the repeated fall-back hour.

**Evidence:** `CalendarScreen.kt:315–318` checks ordering of wall-clock times before converting to instants, but never checks resulting epoch-millisecond ordering. America/New_York 2026-03-08 02:30 resolves to 03:30 while 03:00 remains 03:00: the stored duration is −30 minutes. `WeekDataBuilder.kt:147–154` converts instants to local times and substitutes one minute whenever the local end is not after the start. America/New_York 2026-11-01 05:45Z–06:15Z is 30 elapsed minutes but renders 01:45–01:46.

**Impact:** saved blocks can disappear (`addClipped` rejects `e <= s` at `145`) or external calendar writes can fail and silently fall back locally. Real activity becomes falsely tiny, and repeated-hour events can overlap/mislead. Editing a repeated-hour event also drops its original offset and can change the instant when `atZone` chooses the earlier offset.

**Fix:** choose explicit temporal types: appointments use start/end instants plus an event zone; all-day items use dates; an editor validates zone gaps/overlaps and final instant ordering. Preserve an existing offset when the user has not changed the timestamp. A wall-clock timeline needs a deliberate repeated-hour policy; split at offset transitions or provide an elapsed-duration indicator instead of fabricating one minute.

**Regression scenarios:** spring gap; fall overlap with both offsets; half-hour DST zones; title-only edit in a repeated hour; change phone timezone; start/end equality; final `endMillis > startMillis` invariant for every persisted timed block.

### CAL-07 · P2 · Midnight and short-event display distort time and hide adjacent events

**Certainty:** midnight/overlap probes executed against compiled classes; rendering consequence follows exact size calculation.

**Trigger:** an event from 23:59 to midnight; two adjacent three-minute usage sessions.

**Evidence:** `WeekDataBuilder.kt:148–154` represents midnight as 23:59 and rewrites any start ≥23:58 to 23:58. The real final-minute event becomes 23:58–23:59. Zero-duration events are dropped at `145` with no marker. `EventCompose.kt:58` enlarges every event below 14dp to 14dp. `EventOverlapCalculator.kt:125–149` uses only actual interval overlap, so adjacent 09:00–09:03 and 09:03–09:06 both get full-width slots; at scale 1 their rendered rectangles overlap by 11dp and the later can cover the first.

**Impact:** short activity is hard to tap or appears at the wrong time; crowded usage logs obscure actual appointments. Zero-duration device events are silently absent if such items are supported by the provider.

**Fix:** layout in minutes/seconds relative to a day boundary rather than a `LocalTime` exclusive end that cannot encode 24:00. Separate the visible event rectangle from a larger hit target, or include visual minimum heights in collision handling. Aggregate tiny usage events, and define whether zero-duration items should render as point markers.

**Regression scenarios:** 23:58/23:59/midnight, intervals with seconds; adjacent 1–3-minute events at zoom 0.5/1/2; point event; tapped hidden/overlapping short event; overlaid usage and a planned meeting.

### CAL-08 · P2 · Manual time selection is overwritten while editing the title; end-date semantics are hidden

**Certainty:** code-confirmed state logic.

**Trigger:** type “gym 6 to 7,” manually set another time, then append a word to the title. Separately, choose equal start/end or use “2 hours” at 23:00.

**Evidence:** every title change re-runs `extractActivity(... PLAN)` and overwrites times at `EventEditorSheet.kt:83–91`; manual picker changes (`152–170`) do not establish a manual override. Duration chips at `126–129` use `safePlus` (`196–199`), which clamps to 23:59 rather than crossing midnight. Save reconstruction at `CalendarScreen.kt:317` treats equal start/end as a full 24-hour interval and a lower end time as tomorrow, but the UI shows only one date and two clocks (`EventEditorSheet.kt:106–123`) with no duration/next-day indicator.

**Impact:** combining free-text parsing and manual corrections can undo an intentional choice without warning. “2 hours” creates only 59 minutes at 23:00. A mistaken equal time creates a 24-hour block.

**Fix:** parse time text into an explicit suggestion with Apply; after a manual change, stop replacing times automatically. Show end date, “next day,” and actual duration. Duration shortcuts must add elapsed/calendar duration according to a documented policy and validate the result. Preserve draft fields through rotation/process recreation where appropriate (`59–68` currently uses `remember`, not saveable state).

**Regression scenarios:** parse → override → edit title; remove parsed phrase; manual date+time with title numbers; duration crossing midnight; equal clocks; cancelled pickers; rotation while editing.

### CAL-09 · P2 · Calendar data can remain stale while the screen stays open

**Certainty:** code-confirmed subscription boundaries; background-sync timing needs runtime verification.

**Trigger:** keep Calendar foreground while a synchronized event changes remotely or another app updates the provider; continue using the timeline for several minutes.

**Evidence:** provider reads are one-shot `produceState` calls keyed by `windowStart`, permission, and `refresh` (`CalendarScreen.kt:505–514`). `refresh` changes on resume (`169–172`) or Bibo writes (`357`, `385`); there is no `ContentObserver`. Usage is also a one-shot snapshot (`516–525`) instead of observing the Room session flow. Meanwhile every composed timeline ticks the current-time indicator each second (`WeekBackgroundCompose.kt:71–75`), which creates the appearance of a live display without refreshing its data.

**Impact:** synced meetings/observations can be missing or obsolete until navigation or resume. Opening an old detail snapshot can later overwrite a concurrent provider change.

**Fix:** observer-backed repository flows and lifecycle-aware collection; debounce/coalesce provider updates, retain loading/error states, observe Room usage changes, and re-read current provider state before writes. Refresh clock once per displayed resolution instead of every second on each neighbor page. A conflict policy is needed for external edits while a sheet is open.

**Regression scenarios:** provider change while foreground; remote sync; permission revocation; app resumes; concurrent local/external edits; timer creates a block while Calendar+Tasks are visible together; stale editor conflict.

### CAL-10 · P2 · Provider errors and fallback storage are invisible and mislabelled

**Certainty:** code-confirmed UI/error policy; particular provider rejection requires device verification.

**Trigger:** manual event insertion fails, edit a read-only calendar, delete fails, or there is no app able to open the event.

**Evidence:** `DeviceCalendarRepo.kt:43–59` converts read failures into an empty/partial list; insert/update/delete expose only Booleans (`62–74`, `82–95`, `111–118`). `CalendarScreen.kt:342–352` silently turns a failed manual provider insert into a local ActivityBlock. `330–337` describes every edit failure as a repeating-event problem. `375–386` discards the deletion Boolean, dismisses the sheet, and refreshes; `389–392` swallows external-open failure. Sheets dismiss before write completion (`312`, `382`). The repository's `primaryCalendarId` (`126–143`) chooses the first primary or visible calendar without owner, writable-access, or explicit destination selection. Device event labels hardcode “Google Calendar” (`595`, `688`) although CalendarContract can contain other/local calendars.

**Impact:** the user cannot tell whether an event was saved to their actual calendar, only inside Bibo, or nowhere; failed deletes look successful. Multiple accounts/read-only calendars make behavior unpredictable. The displayed empty agenda can mean permission/provider failure rather than no events.

**Fix:** use typed results (`Success(id,destination)`, `UnsupportedRecurrence`, `ReadOnly`, `PermissionRequired`, `ProviderFailure`). Keep the editor visible on failure and identify the saved destination. Explicitly choose and remember a writable calendar or local-only mode. Separate read and write capabilities; a denied write should not automatically hide readable events (`hasPermissions` requires both at `27–31`). Support retry and success/Undo where safe.

**Regression scenarios:** no calendars; two primary accounts; read-only/shared calendar; write denied/read granted; transient query/insert failure; failed delete; no external calendar handler; verify real saved destination.

### CAL-11 · P2 · Zoom becomes inconsistent between precomposed pages

**Certainty:** source-confirmed duplicated state; gesture reproduction still needed.

**Trigger:** pinch the current day, then swipe to an already composed neighboring day.

**Evidence:** `CalendarScreen.kt:127`, `255–256`, `543` owns a shared scale; pager precomposes neighbors at `240`. Every WeekView separately initializes `localScalingFactor` using unkeyed `remember` (`WeekViewCompose.kt:45–46`) and never synchronizes it with later `weekViewConfig.scalingFactor`. Zooming one page updates its local value and parent but leaves existing neighbor local values unchanged.

**Impact:** zoom unexpectedly resets/jumps by date, and shared saved scaling can disagree with the visible page. Pinch changes content height without keeping the time under the fingers stationary (`WeekBackgroundCompose.kt:80–87` only handles a one-time scroll), adding a second jump.

**Fix:** use one controlled scale value and gesture updates, with scroll anchoring around the pinch centroid. Avoid conflicting parent/local ownership.

**Regression scenarios:** pinch → swipe next/back; three precomposed pages; Day↔3 days; rotate; zoom around a specific meeting preserves that meeting's position.

### CAL-12 · P2 · Timezone changes can split querying, displaying, and saving into different zones

**Certainty:** code-confirmed inconsistent zone sources; Android live-timezone behavior needs a device test.

**Trigger:** Calendar remains composed, user changes timezone or automatic timezone changes during travel, returns and edits/views a block.

**Evidence:** `CalendarScreen.kt:122` remembers one `ZoneId.systemDefault()` for the screen lifetime; query windows and saves use that remembered zone (`315`, `496–498`). `WeekDataBuilder.kt:138` obtains the current default afresh. Resume (`169–172`) refreshes permissions/data but not the remembered zone. Thus a read window and detail/editor can use the old zone while rendered segments use the new one. A captured date-dot window (`130–135`) also does not follow viewport navigation: goals/tasks beyond −60/+120 days from initial entry show no dots even when visible.

**Impact:** events can appear on the wrong date or be absent near day boundaries, and edits can shift timestamps unexpectedly after travel. The date strip gives a false absence cue for distant goals.

**Fix:** inject one observed clock/zone/date context and use it everywhere; react to timezone/date changes. Distinguish each appointment's original event zone from the display zone. Query date dots from the actual visible/nearby window.

**Regression scenarios:** UTC→UTC+6 while composed; calendar event near midnight; date changes with screen open; travel followed by title-only edit; due dates/milestones outside the fixed window.

## Additional usability and product-design gaps

These are product choices or smaller issues, not equivalent to destructive bugs above.

- **Focus reflections cannot be revisited from their calendar block.** `ActivityBlock.note`/`goalId` exists (`Db.kt:33–34`), but `ItemDetailSheet` (`CalendarScreen.kt:592–649`) displays neither. `sourceLabel`/`sourceColor` (`WeekDataBuilder.kt:35–46`) omits `FOCUS`, so a focus session is presented as generic “Event.” Users who wrote a meaningful reflection cannot retrieve it from the historical item. Show source, linked goal, actual elapsed time, and reflection; respect privacy and edit policies for logged facts.
- **Cross-day details show only the start date.** `CalendarScreen.kt:640–641` prints one date and both clock times, with no end date or duration. A multi-day/overnight item is indistinguishable from an incorrect reversed-time item. Show both dates when they differ and distinguish all-day ranges.
- **Past/future days display today's now line.** Config defaults `currentTimeLineOnlyToday=false` (`WeekViewConfig.kt:9`), app enables the current-time indicator unconditionally (`CalendarScreen.kt:544`), and `GridCanvas.kt:93–104` draws across every date. The historical timeline can look like “today.” Limit the line to the today column; a browsing-time reference line would need different styling/label.
- **Permission denial has no local recovery flow.** Calendar auto-prompts on entry (`CalendarScreen.kt:152–167`) but shows neither a permission explanation nor re-enable/settings action afterward. Empty calendar and saved-local-only behavior can look like an app defect. Usage-read absence is equally invisible in this screen. Provide a compact, dismissible source-status card with a user-driven enable action; request write permission when the user chooses external save, not simply to read.
- **Event interactions lack accessibility semantics.** `pointerInput`-only handlers (`EventCompose.kt:84–88`, all-day/multi-day equivalents) do not expose normal click action/role to accessibility services or keyboard users. Tiny event rectangles and 10–12sp labels are also below comfortable touch/read sizes. Prefer semantic click modifiers and an accessible agenda/list alternative; verify TalkBack and large font display.
- **Too many all-day items can consume the whole viewport.** `AllDayEventsRow.kt:44–49` emits an unbounded stack outside the scrollable timeline; `WeekBackgroundCompose.kt:120–131` leaves the grid only remaining height. Add a collapsed “N more” overflow and accessible list. Confirm on realistic calendar data and small/folded displays.
- **Unsorted source layers compete for narrow overlap columns.** Builder adds device appointments, blocks, then usage (`WeekDataBuilder.kt:68–116`) and overlap layout treats them identically. A few usage observations can squeeze an important meeting into a very narrow strip. Let users toggle sources and give factual usage a background/summary layer; separate planned versus actual time in the visual language. This is a deliberate product choice to validate with the user's daily workflow, not a reason to remove useful diary data.
- **Voice logging always anchors today, even while browsing another day.** `CalendarScreen.kt:272` assigns `LocalDate.now()`. Whether logging “just now” or planning the visible day should be explicit. The voice/parser reviewer covers date extraction and overnight parsing separately.

## Architecture recommendation

The calendar should be a composed projection of authoritative domains, not an authoritative database of whatever colored rectangle happened to be displayed.

1. **Introduce a typed domain object and identity.** `CalendarItemId(source, sourceId, occurrenceStart)`; `TimedAppointment(startInstant,endInstant,eventZone,calendarId,capabilities)`; `AllDayAppointment(startDate,endDateExclusive,calendarId,capabilities)`; `ActivityRecord(startInstant,endInstant,source,sourceRef,goalId,note)`; `UsageObservation`. The UI must not decide mutation capabilities from `item !is Usage` alone.
2. **Separate rendering segments from objects.** `TimelineSegment(originalItemId, displayedDay, clippedStart, clippedEnd, continuationFlags)` is disposable view data. Clicking/editing resolves the original item. Stable occurrence identity prevents index reassignment and stale closures from targeting another item.
3. **Move orchestration out of composables.** A Calendar ViewModel owns selected date, zoom, source visibility, loading/error state, editor draft, operation status, and actions. Repositories provide observer-backed flows and typed mutation results. Domain services enforce date/instant, writable-calendar, recurrence, and provenance invariants. Screen code renders state and sends intent.
4. **Define planning versus logging policy.** Manual planning uses a visibly chosen destination; voice/focus/timer/task histories are local factual records with provenance. A completed-task marker should not fabricate work or be aggregated as elapsed time. The tasks reviewer documents current completion-marker inconsistencies; source references are needed for reconciliation on reopen/delete/undo.
5. **Centralize time.** Inject a Clock/ZoneProvider, distinguish elapsed duration from wall-clock display, validate final instants, preserve all-day exclusive end dates, and define DST/gap/overlap behavior. Temporal conversions belong in a tested small boundary module shared by calendar, timer, stats, reminders, widget, and mentor tools.
6. **Ship a layered user model.** A default agenda plus actual-activity view or source toggles, clearer source/destination labels, explicit next-day/end-date and duration, preserved focus notes, accessible compact/list option, and recoverable permission/source status. Keep direct chronological overview, swipe, and voice capture because those are useful existing product strengths.

## Suggested acceptance matrix and coverage

Required regression coverage after fixes:

| Dimension | Representative combinations |
|---|---|
| Source and capability | Local manual; timer/focus/task; usage; writable/read-only provider; recurring occurrence/series |
| Time shape | Point; seconds/minutes; same-day; midnight; multi-day; all-day one/multiple days; repeated/missing DST hour |
| Navigation | Swipe→Today; chip→same anchor; Day↔3 days; pinch→neighbor→back; rotation; midnight/date/zone rollover |
| Mutation sequences | Add→edit→tap→edit again; delete→undo; external update with detail/editor open; switch tabs during write |
| Failure/recovery | Denied/revoked read/write; no calendar; two accounts; provider failure; process death during draft/write |
| Accessibility/density | TalkBack actions; large font; small screen; many all-day events; tiny adjacent usage blocks |

Validation performed: inspected all vendored calendar files, source paths above, and relevant data contracts; executed existing compiled `WeekDataBuilderKt.addClipped` and `EventOverlapCalculator` on the JVM plus exact `java.time` conversion probes. Probe source is `/workspace/bibo-audit/calendar-probes/CalendarTemporalProbe.java`, compiled to that helper directory. Results: fall-back 30min→1min, midnight final minute shifted earlier, zero-duration event omitted, spring-gap save duration −30min, 49h edit→1h, adjacent3min event slots full-width despite renderer14dp minimum. No source unit/instrumentation tests were found under `app/src` during this scope. No emulator operations or external-calendar writes were performed by this reviewer; provider/gesture/accessibility UX cases still need the targeted runtime checks called out above.

### Probe output (verbatim)

```text
fall-back within repeated hour: elapsedMinutes=30, added=true
  rendered=TimeSpan(start=01:45, endExclusive=01:46), renderedMinutes=1
last minute before midnight: elapsedMinutes=1, added=true
  rendered=TimeSpan(start=23:58, endExclusive=23:59), renderedMinutes=1
zero-duration provider event: elapsedMinutes=0, added=false
spring editor 02:30-03:00: resolved=2026-03-08T03:30-04:00[America/New_York] - 2026-03-08T03:00-04:00[America/New_York], durationMinutes=-30
title-only edit of 49h event: newDurationHours=1
back-to-back 3min event layouts={1=EventLayout(widthFraction=1.0, offsetFraction=0.0, overlapGroup=0), 2=EventLayout(widthFraction=1.0, offsetFraction=0.0, overlapGroup=1)}; renderer expands both to14dp at1dp/min =>11dp visual overlap
```
