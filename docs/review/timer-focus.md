> Historical audit of commit `8e63d9d`, before the repairs. See [the implementation report](../IMPLEMENTATION.md) for current behavior and validation.

# Timer, focus, blocking, and secondary-surface audit

Scope: read-only inspection of the shared timer/focus model, foreground service, accessibility blocking, Focus screen, Quick Settings tile, widget, and task/calendar integration. No tracked application files changed. No emulator interactions were performed by this reviewer. No `AGENTS.md` was found under `/workspace`.

The intended flow is one shared running activity: start from Tasks, Focus, a widget, a tile, or Mentor; observe the same session everywhere; stop or complete it once; preserve accurate history; turn distractions back on reliably. The implementation has a useful central controller but does not yet enforce that contract. Running state is split between SharedPreferences and `TodoTask.startedAt`, secondary surfaces submit unscoped toggle commands, and persistence is launched after the live state is erased.

Severity: P1 = feature unusable, loss of recorded work, or another session stopped unexpectedly; P2 = inaccurate reporting, misleading state, or recovery gap. “Confirmed by code” means the trigger and resulting behavior follow directly from the code. Android/OEM behavior is identified separately where a device check remains useful.

## Ranked defects

### TF-01 — P1: accessibility service accesses Context before attachment

- **Trigger:** enable Bibo's accessibility service, directly or through Stats → Websites → Enable.
- **Behavior:** `WebTrackingService` constructs `systemPackages` using `packageName` in an instance initializer. Android constructs a `Service` before attaching its base Context; `Service` initially extends `ContextWrapper(null)`. Evaluating `packageName` here calls a Context-dependent API before attachment and should throw a null-context exception before `onServiceConnected`.
- **Impact:** enabling accessibility crashes the entire app process; Android retrying the enabled service causes repeated crashes on restart. Both website tracking and focus app blocking become unavailable because they share the same service. The Settings switch can still look enabled, so checking only the enabled-service string is insufficient evidence that blocking works.
- **Evidence:** [WebTracking.kt:113](/workspace/bibo/app/src/main/java/com/bibo/data/WebTracking.kt:113), [WebTracking.kt:139](/workspace/bibo/app/src/main/java/com/bibo/data/WebTracking.kt:139), [StatsScreen.kt:77](/workspace/bibo/app/src/main/java/com/bibo/ui/StatsScreen.kt:77). Installed API 36 SDK stub source confirms `Service()` calls `super((Context)null)`.
- **Confidence:** reproduced by root on Android 36: `RuntimeException: Unable to create service` caused by `NullPointerException` in `ContextWrapper.getPackageName`, pointing to `WebTracking.kt:114` constructor. The crash repeated on restart. Root restored the original disabled accessibility setting. Evidence: [accessibility-crash.log](/workspace/bibo-audit/accessibility-crash.log).
- **Remediation:** initialize Context-dependent values in `onCreate`/`onServiceConnected`, or use a lazy property accessed only after attachment. Track actual service health separately from permission state.
- **Regression:** enable service on fresh install; ensure connection succeeds, URL sampling occurs, then launch a selected blocked app during focus. Disable/re-enable and restart the process.

### TF-02 — P1: Focus claims apps are blocked without obtaining its required capability

- **Trigger:** fresh install → Focus → choose a distracting app → optionally grant overlay → Start focus → launch that app.
- **Behavior:** the Focus picker checks only overlay permission. It never checks `isWebTrackingEnabled`, asks for accessibility access, or verifies a live blocker. All enforcement is in `WebTrackingService`. The running panel still says “N apps blocked.” The required accessibility grant is offered only in Stats under website tracking; Stats itself initially gates content behind Usage Access.
- **Impact:** the primary promise of focus mode silently fails. Users can complete setup and reasonably believe their distractions are blocked when the blocker is absent.
- **Evidence:** [TimerScreen.kt:296](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:296), [TimerScreen.kt:502](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:502), [TimerScreen.kt:511](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:511), [WebTracking.kt:164](/workspace/bibo/app/src/main/java/com/bibo/data/WebTracking.kt:164), [StatsScreen.kt:85](/workspace/bibo/app/src/main/java/com/bibo/ui/StatsScreen.kt:85), [StatsScreen.kt:327](/workspace/bibo/app/src/main/java/com/bibo/ui/StatsScreen.kt:327).
- **Confidence:** confirmed by code; independent of TF-01.
- **Remediation:** request and verify accessibility from the focus setup flow itself; separately explain optional overlay versus required enforcement. Display “blocking unavailable” when access is revoked or service health fails. Recheck capabilities on resume and while a session runs. Avoid coupling blocking permission to a decision to record browsing history.
- **Regression:** accessibility off/overlay on; accessibility on/overlay off; both on; revoke each mid-session; service disconnected; deny Usage Access but enable focus blocking.

### TF-03 — P1: asynchronous task start/stop writes can corrupt which task is running

- **Trigger:** start task A, stop quickly, or rapidly switch A → B → A; a Mentor tool and UI action can also run concurrently.
- **Behavior:** `startTask` sets session prefs synchronously and launches a separate IO coroutine that later writes `TodoTask.startedAt`. `stopTimer` clears prefs synchronously and independently launches a coroutine that later clears that task's `startedAt`. Neither write checks the session identity/version. A stopped A's delayed start can leave A marked running with no timer; an old stop A can clear A's newly restarted timer row. Every row update writes a copied entity instead of a conditional field update, so a concurrent completion/edit can also be overwritten. Controller reads/clear/write sequences have no mutex; Mentor executes tools on `Dispatchers.IO`, while UI, service, tile and accessibility callbacks also call the controller.
- **Impact:** task row, Focus screen, notification and widget disagree; pressing a task's timer button may stop an unrelated session. User actions and edits can be lost under competing writes.
- **Evidence:** [TimerController.kt:124](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:124), [TimerController.kt:137](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:137), [TimerController.kt:192](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:192), [TimerController.kt:226](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:226), [Mentor.kt:237](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:237), [MentorTools.kt:297](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:297), [TodoScreen.kt:233](/workspace/bibo/app/src/main/java/com/bibo/ui/TodoScreen.kt:233).
- **Confidence:** race confirmed by code; exact visible frequency depends on coroutine scheduling. Task-list stale-row behavior is additionally documented in `tasks-goals.md` and makes timer failures reproducible without a race.
- **Remediation:** one durable session record with a stable ID and serialized commands. Commit session changes and task transitions in one Room transaction; derive task-running presentation from that record. Use field-specific conditional updates for independent task edits and require session ID when stopping. Never allow a delayed command for old A to modify new A.
- **Regression:** barrier-controlled concurrent start/stop, A → B → A with delayed DB execution, completion during pending start, start from Mentor while UI stops, repeated Stop against the same session. Assert one running session, no orphan task markers, and exactly one saved segment.

### TF-04 — P1: stopping erases the only recoverable session before saving it

- **Trigger:** stop a meaningful session and process termination/storage failure occurs before the detached coroutine commits the activity block; similarly complete a timed task and terminate between clearing timer and task/block writes.
- **Behavior:** `stopTimer` clears running preferences and stops the service before launching its database insert. A process death after the clear but before insertion leaves no active session and no recorded activity. `clear` is another erase-first path used by completion. No durable pending-stop record or transaction spans prefs and Room. A database exception in the detached timer coroutine has no useful user-facing recovery result.
- **Impact:** the app tells the user the timer stopped/saved but work can disappear permanently; linked tasks can retain stale running markers.
- **Evidence:** [TimerController.kt:200](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:200), [TimerController.kt:226](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:226), [TimerController.kt:243](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:243), [TodoScreen.kt:199](/workspace/bibo/app/src/main/java/com/bibo/ui/TodoScreen.kt:199), [MentorTools.kt:727](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:727).
- **Confidence:** confirmed non-atomic ordering; failure injection needed to measure the loss window.
- **Remediation:** persist the ended session and clear the active session transactionally in the same store before reporting success; reconcile notifications/DND/widget afterward. Make finalization idempotent using session ID. If using an interim outbox, retain enough data to finish it after restart.
- **Regression:** terminate process before/after each persistence boundary; insert failure/disk full; restart and retry finalization. Assert recorded work survives and retries cannot duplicate the block.

### TF-05 — P1: stale widget actions can stop another session, start instead of stop, or time a different task

- **Trigger A:** widget displays task A as “Next up”; reorder, complete, delete or filter tasks elsewhere; tap the widget's displayed Start.
- **Behavior A:** the callback re-queries the top task instead of using the displayed task's ID, so it can start B. If a focus session is already running but the widget has not refreshed, `startTask` silently stops that focus.
- **Trigger B:** widget displays Stop; session was already stopped elsewhere and widget update is still pending/failed; tap displayed Stop.
- **Behavior B:** `ToggleTimerAction` treats Stop as a toggle and starts a new “Quick timer” when nothing is running.
- **Impact:** a visible control acts on another entity or performs the opposite action, matching the user's complaint that combining features breaks another feature.
- **Evidence:** [BiboWidget.kt:54](/workspace/bibo/app/src/main/java/com/bibo/widget/BiboWidget.kt:54), [BiboWidget.kt:92](/workspace/bibo/app/src/main/java/com/bibo/widget/BiboWidget.kt:92), [BiboWidget.kt:101](/workspace/bibo/app/src/main/java/com/bibo/widget/BiboWidget.kt:101), [TimerController.kt:125](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:125), [bibo_widget_info.xml:7](/workspace/bibo/app/src/main/res/xml/bibo_widget_info.xml:7). The provider's periodic update is 30 minutes; none of the controller's start paths refresh widgets. Task/reward mutations also have no widget refresh path.
- **Confidence:** confirmed by code. Launcher delivery timing determines how long staleness lasts. Even reliable refresh does not eliminate the need for entity-scoped commands.
- **Remediation:** pass displayed task ID and expected session ID as action parameters. Stop is idempotent Stop, never Toggle. Start explicitly validates that task remains actionable and handles an existing session through a consistent switch policy. Refresh widget/tile on committed state changes; use a chronometer or label that does not promise a live elapsed value when rendered once.
- **Regression:** A displayed then A completed/reordered/deleted; focus started in app while idle widget displayed; stop from notification then stale widget Stop; task deleted between callback query and command. Assert no unrelated session is stopped.

### TF-06 — P1: “Stop & reflect” keeps timing and can stop the next session

- **Trigger:** running A → Stop & reflect → spend time writing; or switch tabs without saving; or stop A from notification and start B from widget while the old reflection sheet remains.
- **Behavior:** tapping Stop only sets `showReflection=true`; the sheet says “Session done” but the actual `stopTimer` is called only on Save/Skip/dismiss. Reflection writing remains part of the timed interval and blocking/DND remain active. Navigating away destroys the remembered sheet state without finalizing A. The sheet has no captured session ID; saving it after B starts stops B and attaches A's note to B.
- **Impact:** inaccurate recorded work, lost reflection, unexpectedly continuing blocking, and a later timer stopped by a stale dialog.
- **Evidence:** [TimerScreen.kt:158](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:158), [TimerScreen.kt:225](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:225), [TimerScreen.kt:553](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:553), [TimerScreen.kt:564](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:564), [MainActivity.kt:139](/workspace/bibo/app/src/main/java/com/bibo/MainActivity.kt:139).
- **Confidence:** confirmed by code.
- **Remediation:** Stop commits an ended session immediately; reflection edits that saved session by ID. Persist an optional reflection draft with saved-state support. Offer edit/skip afterward without touching whatever session is currently active.
- **Regression:** reflect for five minutes; dismiss sheet; navigate away; rotate; process recreation; stop from notification and start B before Save. Assert A ends at first Stop, B is unaffected, and notes attach to A.

### TF-07 — P1: Pomodoro phase progression drifts after suspension/restart

- **Trigger:** start 25/5 Pomodoro, let the device suspend CPU or restart app/service after the saved phase deadline; resume well after one or multiple phase boundaries.
- **Behavior:** only a main-thread `Handler.postDelayed` drives phase changes. There is no phase alarm or clock-based catch-up. At first overdue tick, `advancePhase` advances once and sets the next end to *current time + duration*, throwing away elapsed time since the old deadline. Example: work starts 10:00, phone/app resumes 11:00; app moves from work to a fresh break ending 11:05, despite 60 minutes having passed. App blocking reads this stored phase, so it follows the incorrect phase too. `START_STICKY` can restart a killed service but does not preserve the phase timetable.
- **Impact:** countdown freezes while asleep or restarts into the wrong phase; distraction blocking and completed-pomodoro count no longer correspond to the schedule. Runtime timing depends on phone power behavior.
- **Evidence:** [TimerService.kt:29](/workspace/bibo/app/src/main/java/com/bibo/data/TimerService.kt:29), [TimerService.kt:38](/workspace/bibo/app/src/main/java/com/bibo/data/TimerService.kt:38), [TimerService.kt:65](/workspace/bibo/app/src/main/java/com/bibo/data/TimerService.kt:65), [TimerController.kt:172](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:172), [WebTracking.kt:168](/workspace/bibo/app/src/main/java/com/bibo/data/WebTracking.kt:168).
- **Confidence:** overdue transition behavior confirmed by code; actual suspension/alert delivery needs physical-device or emulator power/restart tests.
- **Remediation:** define explicit product policy for overdue phases, then derive phase from saved deadlines/session segments and reconcile missed boundaries. UI polling should render state, not determine truth. Schedule boundary notifications through a supported alarm mechanism if a prompt transition is required; avoid holding CPU awake continuously. Record work and break segments separately.
- **Regression:** resume two minutes late, a whole break late, multiple cycles late, service process restart, reboot, manual clock jump, DND active. Verify phase, blocker, counts and saved work/break durations.

### TF-08 — P2: DND is not declared as a capability and silently reports success

- **Trigger:** Focus → Silence phone (DND), or ask Mentor to start focus with distractions blocked.
- **Behavior:** manifest lacks `android.permission.ACCESS_NOTIFICATION_POLICY`. `FocusDnd.hasAccess` returns false without grant; `enable`/`disable` silently return or swallow exceptions. Focus stores the requested `dnd=true` rather than a successfully applied state. Mentor unconditionally says “phone silenced” when `block_distractions=true`. The DND switch does not automatically complete the user's pending intent after returning from Settings.
- **Impact:** DND cannot be configured through the expected ordinary permission flow, and user/Mentor-facing promises do not reflect the phone's real state.
- **Evidence:** [AndroidManifest.xml:5](/workspace/bibo/app/src/main/AndroidManifest.xml:5), [FocusDnd.kt:10](/workspace/bibo/app/src/main/java/com/bibo/data/FocusDnd.kt:10), [FocusDnd.kt:21](/workspace/bibo/app/src/main/java/com/bibo/data/FocusDnd.kt:21), [TimerScreen.kt:442](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:442), [TimerController.kt:153](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:153), [MentorTools.kt:628](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:628).
- **Confidence:** missing manifest requirement and false-success strings confirmed by code; actual Settings listing/grant behavior should be checked on supported Android versions.
- **Remediation:** declare required capability, return structured capability/result status, recheck access on resume, and base UI/Mentor text on the result. Define priority exceptions accurately. On versions with global DND mutation, preserve prior state rather than always setting ALL at end; on Android 15+ target behavior, use an app-owned automatic rule and respect the user's other active rules. Treat OEM/API differences explicitly.
- **Regression:** denied grant; grant while form open; revoke mid-session; user already in DND; user changes DND during focus; Android 14 versus Android 15/16; process death while rule active.

### TF-09 — P2: focus statistics count the wrong durations and omit task timing

- **Trigger A:** focus spans midnight, e.g. 23:50–00:10.
- **Behavior A:** `blocksIn` correctly selects overlapping blocks, but Focus sums each entire interval. The 20-minute session counts as 20 minutes for each day rather than 10 minutes per day.
- **Trigger B:** run 25-minute work plus 5-minute break Pomodoro then stop; compare “focused today” with time spent working.
- **Behavior B:** saved block spans the full session, including every break. No phase segments exist, so all break time is shown as focused time.
- **Trigger C:** time a task from Tasks or widget, then inspect Focus history/total.
- **Behavior C:** task timer saves source `TODO`; Focus includes only `TIMER` and `FOCUS`, so the timer the user just saw on the Focus screen disappears from its history and focused totals. Because `ActivityBlock` has neither task ID nor distinct timed-versus-completion subtype, the UI cannot reliably distinguish actual timed task work from fabricated task-completion markers.
- **Additional edge:** `weekBlocksFlow` is remembered with only `weekStart`, although its query captures `dayEnd`; if the screen stays composed past midnight within the same week, the week query continues ending at the old midnight.
- **Impact:** productivity feedback is misleading across modes and days; breaks inflate “focused” totals.
- **Evidence:** [TimerScreen.kt:105](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:105), [TimerScreen.kt:125](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:125), [TimerController.kt:209](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:209), [TimerController.kt:215](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:215), [Db.kt:25](/workspace/bibo/app/src/main/java/com/bibo/data/Db.kt:25), [Db.kt:45](/workspace/bibo/app/src/main/java/com/bibo/data/Db.kt:45).
- **Confidence:** confirmed by code. Product must define whether plain/task timers are “focus”; current one-shared-timer presentation suggests they should be visible consistently.
- **Remediation:** clip intervals to the reporting window; store work/break segments and session/task provenance; differentiate a completion marker from timed activity. Choose explicit metric labels and inclusion rules. Key query flows by every boundary they capture.
- **Regression:** midnight and week boundary; DST day; Pomodoro stopped in break; task timer pause/resume/completion; un-timed completion. Assert sum of daily clipped work durations equals weekly totals.

### TF-10 — P2: task suggestion selection carries the previous task's goal

- **Trigger:** select Focus suggestion A with goal X, then suggestion B with no goal, then start.
- **Behavior:** clicking a suggestion only sets `goalId` when that task's goal is non-null. B inherits X.
- **Impact:** time for an unrelated task is attributed to the wrong goal; user cannot trust auto-populated context.
- **Evidence:** [TimerScreen.kt:357](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:357).
- **Confidence:** confirmed by code.
- **Remediation:** assign `goalId = t.goalId` including null; retain selected task identity instead of matching only its title. Editing the intention should have an explicit policy for detaching the task/goal.
- **Regression:** goalful A → goalless B; goalless B → A; duplicate titles with different goals; goal deleted/archived while setup is open.

### TF-11 — P2: reboot/force-stop recovery treats unattended downtime as running work

- **Trigger:** running timer → reboot/force-stop → wait → reopen Bibo.
- **Behavior:** only `MainActivity.onCreate` restarts TimerService from surviving prefs; no timer boot receiver reconciles state. Before launch there is no timer notification/phase driver. On reopen the timer continues from the old wall-clock start, counting the entire gap. All durations/deadlines use `System.currentTimeMillis`; a backward clock correction can show negative elapsed time and cause `stopTimer` to discard activity under its ten-second guard.
- **Impact:** stale focus state survives for hours or days; recorded work includes downtime and Pomodoro state/blocking can be stale. Force-stop and device reboot require an explicit product decision rather than silently treating missing runtime as productive work.
- **Evidence:** [MainActivity.kt:64](/workspace/bibo/app/src/main/java/com/bibo/MainActivity.kt:64), [AndroidManifest.xml:49](/workspace/bibo/app/src/main/AndroidManifest.xml:49), [TimerController.kt:110](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:110), [TimerController.kt:206](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:206), [TimerController.kt:214](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:214), [TimerScreen.kt:285](/workspace/bibo/app/src/main/java/com/bibo/ui/TimerScreen.kt:285).
- **Confidence:** recovery policy and wall-clock behavior confirmed by code; reboot restrictions and DND reconciliation need platform testing.
- **Remediation:** record durable session ID, monotonic elapsed anchor, wall-clock anchor and boot identity. Reconcile on process start, service restart and boot under supported Android rules. Ask the user to resume/end an interrupted session where continuity cannot be established. Wall time is for calendar placement; monotonic time is for elapsed measurement.
- **Regression:** process reclaim, Android Task Manager Stop, force-stop, reboot, clock moved forward/back, timezone change. No silent phantom active session and no negative or unbounded duration.

## Related task integration defects

The Tasks/Goals reviewer owns the detailed evidence for these shared-session failures:

- The task list caches full active-row snapshots keyed only by IDs; starting a task can leave its row showing Play and old `startedAt`, making a second tap restart the timer and completion use fabricated duration.
- Goal-profile completion clears the task row but does not stop the shared timer or create the calendar block.
- Completing a parent clears a running child's row without settling that child's shared session.
- Delete/Undo does not settle a linked timer; restoring an old `startedAt` can create a phantom running task after another session has started.

These should be repaired through the same application commands rather than four UI-specific patches.

## Architectural recommendation

Retain the app's on-device approach, but move the live session into the same transactional store as recorded work. Add stable `sessionId`, `taskId`, `goalId`, status, monotonic/wall anchors, work/break segments and an idempotent finalization key. `TodoTask.startedAt` should be derived from the active session or maintained only inside that same transaction.

Expose application commands such as `StartTask(taskId, expectedCurrentSessionId)`, `StartFocus(config)`, `StopSession(sessionId)`, `CompleteTask(taskId)`, `DeleteTask(taskId)` and `AttachReflection(sessionId, note)`. Serialize conflicting commands, validate current task/session state in the transaction, and return a structured success/error/capability result. Compose screens, Mentor tools, widget, tile, notification and accessibility overlay all call these commands. They should never directly coordinate prefs, task rows and activity inserts independently.

Foreground service, notifications, blocking and DND are projections of committed state. Reconciliation can retry them safely without recording activity twice. A Room `Flow` provides one UI state snapshot to every screen instead of per-second reads of unrelated preference keys. Update widgets and request tile refresh from committed events. Keep app blocking as a capability independent of website recording so a user can block distractions without electing to retain browsing history.

## Product and UX decisions, distinct from defects

- Let users choose a focus preset once and reuse it; setup currently resets intention, duration, DND and app selection when navigating away. `rememberSaveable` helps forms; persisted presets help repeated sessions.
- Give one consistent meaning to Pause, Stop, Finish task and Switch activity. Existing task “pause” saves an interval then starting again creates a new interval; there is no accumulated session model. Make the displayed duration and completion history reflect that choice.
- Make switching away from an active focus visible. A secondary-surface Start can currently end blocking and DND immediately with no context. A concise “Switch from X to Y” action or explicit switch setting can preserve one-tap use while avoiding surprise.
- Display actual blocking and DND state, plus a short recovery action if a capability is lost. Selecting an app should not itself be described as having blocked it.
- Pomodoro transition notifications deliberately use a channel with sound/vibration disabled; a phase alert is therefore easy to miss with the screen off. Provide an explicit optional sound/haptic policy and define how it interacts with DND.
- Notification taps use the package launch intent and do not request the Timer tab. Opening a running timer should take users to the active session, using the existing `EXTRA_OPEN_TAB` mechanism.
- Add a deliberate emergency/temporary access design to blocking if the product needs it, rather than requiring the user to end the whole session for every necessary interruption. Validate overlay keyboard/screen-reader navigation and foldable behavior.
- The notification is public on the lock screen and includes a remotely generated Mentor comment. Offer title/comment visibility settings if users need private intentions; current visibility is an intentional implementation choice, not an accidental disclosure finding.

## Platform checks still needed

- API 29–33 foreground-service startup: code passes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` whenever API >=29, although that type was introduced in API 34. Existing lint flags the mismatch. Test the supported older devices and use a version-specific foreground-service type contract.
- API 35/36 background-start restrictions after a delayed Mentor tool result; if `startForegroundService` fails, start paths have already committed running prefs and possibly DND, so recovery must settle the partial operation.
- Overlay active-window feedback: after Bibo's full-screen overlay becomes the active accessibility root, `enforceBlock` sees Bibo as a system package and may remove its own overlay, causing flicker/repeated display. Device validation required; root owns runtime interactions.
- Launcher-specific widget update behavior, tile refresh after changes elsewhere, DND ownership across Android releases, OEM power suspension, and lockscreen transition alert visibility.

## Reviewed file coverage

Fully reviewed: `data/TimerController.kt`, `data/TimerService.kt`, `data/FocusDnd.kt`, `data/WebTracking.kt`, `ui/TimerScreen.kt`, `tile/TimerTile.kt`, `widget/BiboWidget.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `res/xml/bibo_widget_info.xml`, `res/xml/web_tracking_service.xml`, `data/AppScope.kt`.

Reviewed integration sections: `data/Db.kt` activity/task entities and DAOs; `ui/TodoScreen.kt` state, timer and completion paths; `ui/GoalProfileScreen.kt` completion; `ui/TaskActions.kt` delete/undo; `ui/MentorTools.kt` start/stop/complete/delete; `ui/Mentor.kt` tool dispatch threading; `ui/StatsScreen.kt` permission/accessibility enable flow; `data/WebsiteRepo.kt` clipped aggregation; relevant README permission/workflow documentation; existing lint report for timer API compatibility.
