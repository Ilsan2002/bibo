> Historical audit of commit `8e63d9d`, before the repairs. See [the implementation report](../IMPLEMENTATION.md) for current behavior and validation.

# Tasks, goals, rewards, and reminders audit

Read-only application review of the checkout at `/workspace/bibo`; no application, manifest, dependency, or tracked documentation files changed. No `AGENTS.md` was found in the checkout or at `/workspace` and `/`. Runtime testing is owned by the parent audit agent. Findings below come from full source inspection, call-path tracing, and small in-memory SQLite fixtures using the application's queries; they are explicitly not claims of emulator reproduction.

Severity: **P1** means a likely normal-use loss of data, broken shared state, or invalid money/history. **P2** means a substantial workflow, recoverability, notification, or consistency defect. **P3** means a lower-impact improvement. Confidence describes source evidence, not device testing.

## What this part of the app is trying to do

The intended loop is: choose a long-term goal, break it into small tasks, optionally time a task, complete it, see the result in the calendar and progress, and use earned treat money for a wish. The same task can be acted on through Tasks, a goal profile, the mentor, the focus timer, notification, widget, and Quick Settings. Users naturally expect these entry points to share one definition of completion, timing, editing, undo, and progress. Today, each entry point contains its own writes and the definitions disagree.

## Feature-by-feature assessment

| Feature | What exists | What prevents a dependable workflow |
|---|---|---|
| Task list and editing | Checkboxes, swipe actions, timer, title editor, drag order | Stale remembered row objects; field rollback; no manual due/reminder editor |
| Subtasks | One-level groups, collapse/expand, progress text | Inconsistent parent completion; completed steps disappear; no safe local reversal |
| Task history | Completed tasks and timed sessions can appear on calendar | Different entry points log differently; untimed tasks fabricate duration; no task/completion provenance |
| Goals | Folder cards, profile, details, target date | First goal unreachable from empty UI; profile/card progress disagree; tree moves strand children |
| Delete and Undo | Immediate deletion and bounded snackbar restoration | Snapshot restoration bypasses current relationships/session state; concurrent delete/undo can orphan records |
| Filtering and reorder | Hidden long-press goal filter and drag handles | No visible filter scope/clear action; order writes collide globally and can overwrite other fields |
| Treats/wishlist | Weekly budget, task rewards, redemption | No atomic purchase; mutable task rows rewrite money history; management controls disappear with affordability |
| Reminders | Per-task alarm, daily rotating goal spotlight, boot rearming | Permission depends on Focus use; alerts overwrite; no direct navigation; background lifetime and local-time drift |
| Widgets/shared surfaces | One shared timer and next task | Session/task state split; reward/task refresh not consistently propagated |

## Product contracts to decide before implementing fixes

These are design choices that the present code answers inconsistently. Reasonable defaults below let a corrective implementation proceed; they should be written as invariants, not left to each screen.

| Contract | Recommended default | Decision that changes implementation |
|---|---|---|
| Parent completion | Parent represents an outcome; if incomplete steps remain, show an explicit “Complete remaining steps” action | Whether children are mandatory dependencies, optional checklist items, or independently completable outcomes |
| Reopening and completion Undo | Undo reverses the specific completion and only the children changed by its cascade | Whether later reopening resets all children or preserves independently completed steps |
| Task timing | One active session; stopping saves measured time; completing settles the linked task/descendant session once | Whether task timers support resumable accumulated sessions and whether task work counts as focused time |
| Completion history | Timestamped milestone separate from measured sessions; no invented actual duration | Whether users may supply an explicitly labeled estimate |
| Child goal membership | Inherit root parent's goal; moving root moves its subtree atomically | Whether independent child goal links are a deliberate supported feature |
| Progress | Show outcome count and step count separately; use one shared model everywhere | Whether weighted effort or leaf-only progress is desired |
| Goal removal | Archive preserves task and historical links; permanent removal is separate | Whether inbox promotion is desired on retirement versus keeping archived grouping |
| Task removal | Archive/tombstone identities referenced by history; coherent subtree Undo | Whether destructive deletion must remove all history or merely hide current work |
| Sort order | Explicit per-goal order plus stable inbox sorting, or a fully defined global order | Which scope the user expects a filtered drag to affect |
| Rewards | One immutable credit per defined task completion, atomic debits, explicit reversal policy | Whether money represents cash set aside or symbolic points; recurring task credit; weekly expiry versus carryover |
| Weekly budget | Default weekly cap with clear reset/carryover text and chosen currency | Whether changing a budget retroactively changes this week's grants; wishes larger than cap |
| Reminders | User-enabled, permission-aware, task/goal-specific, direct navigation; local-clock schedule | Whether spotlight remains enabled by default; snooze, completed-goal behavior, and delivery precision |

The contracts need product agreement during implementation, but none prevents correcting stale row snapshots, making writes transactional, preserving valid relations, or exposing permission status now.

## Ranked findings

### TG01 — P1: Active task rows retain old objects, then overwrite new data

- **Confidence:** Very high; deterministic from Compose `remember` keys and full-row DAO updates.
- **Evidence:** `ui/TodoScreen.kt:164` derives active tasks; `:165-166` remembers a mutable copy using only the sequence of task IDs as keys. An edit, timer start/stop, reward change, due-date change, or nonmembership-changing update leaves those keys identical. `:216`, `:221`, `:582`, and `:187` subsequently write copies of the old row. `data/Db.kt:158` uses `@Update`, so all columns are replaced. `ui/TodoScreen.kt:670-671` and `:745` display timer state from that old object.
- **Sequence:** Add “Draft”; edit its title to “Submit”; the list still shows “Draft”; complete or reorder it. Also: press Play on a task, then press the unchanged Play icon again; or start the timer and complete the task without leaving Tasks.
- **Actual:** The title does not update in the active list. Later completion/reorder can write “Draft” back over “Submit”. Timer starts in preferences/Room, but the row retains `startedAt = null` and keeps showing Play. A second press restarts the shared timer. Completing that row skips `TimerController.clear` because `:199` requires the stale non-null `startedAt`, so the calendar gets a made-up interval and the shared timer continues after completion. Conversely, entering Tasks with an already-running task snapshots a non-null start; stopping leaves the row displaying a stale running state.
- **Expected/impact:** Every surface should display the latest task and actions should change only their intended fields. This is a direct explanation for “one operation makes another stop working,” including visible failed edits and silent data rollback.
- **Fix:** Keep only an ordered list of IDs as transient drag state; look up each row in the latest DB-backed map. Reconcile IDs after emissions and after dragging. Persist order with a DAO update of only `sortOrder`; edit only `title`; complete with an ID-based command that rereads current state in a transaction. Avoid keeping a second collection of whole task records.
- **Regression scenarios:** Edit then complete; edit then reorder; start/stop without leaving Tasks; task update while goal profile is open; mentor changes reward/due date while task list is mounted in a tablet pane; reorder immediately after task update.

### TG02 — P1: Completion has different meanings depending on the screen

- **Confidence:** Very high.
- **Evidence:** Tasks completes in `ui/TodoScreen.kt:191-224`, writes an activity block, and auto-completes children. Goal profile completes in `ui/GoalProfileScreen.kt:76-85`, only updates todo rows, and never calls the timer controller or activity DAO. Mentor completion in `ui/MentorTools.kt:715-740` logs an activity and clears a matching timer, but does not complete children. The renderer in `ui/TodoScreen.kt:373` hides completed children and renders children only below active parents.
- **Sequence:** Create a goal task with two steps. Complete it through Tasks, then try the same operation on a fresh equivalent task through its goal profile or the mentor. Start its timer first for the goal-profile case.
- **Actual:** Tasks marks children completed and records history. Goal profile marks children completed but records no history and leaves a linked shared timer running. Mentor marks only the parent completed; its incomplete children become unreachable in Tasks because completed parents have no subtask rows. Rewards/progress/calendar differ for the same action.
- **Expected/impact:** A task completion should have identical side effects across entry points. Users lose calendar evidence, see running timers for completed tasks, and can strand remaining steps under a completed parent.
- **Fix:** Create one task application service with commands such as `completeTask(id)`, `reopenTask(id)`, `deleteTask(id)`, `moveTask(id, goalId)`, and `startTaskSession(id)`. All UI, AI tools, widget, and service entry points should call it. Decide once whether parent completion cascades, is blocked by incomplete steps, or requests an explicit “complete remaining steps” choice.
- **Regression scenarios:** Use the same fixture to invoke completion through Tasks, goal profile, mentor, and any future notification action; assert equal task tree, timer, reward, and history state.

### TG03 — P1: Completing/deleting a parent does not settle its running child's timer

- **Confidence:** Very high.
- **Evidence:** `ui/TodoScreen.kt:199` clears the shared timer only if it belongs to the row being completed. `:217-218` clears child `startedAt` flags during parent completion without settling a timer linked to the child. `ui/GoalProfileScreen.kt:81-84` similarly clears flags without touching shared state. `ui/TaskActions.kt:24-43` deletes/restores records without timer coordination. `ui/MentorTools.kt:438-440` does the same for deletion. Timer state remains independently in preferences (`data/TimerController.kt:62`, `:73`).
- **Sequence:** Expand a parent; time a child; complete or delete the parent. Separately, delete a timed task, switch to another session, then undo the deletion.
- **Actual:** The task/child vanishes or becomes completed, but Focus and the notification continue the old task session. Stopping can later log work for a deleted/completed task. Undo reinstates the original `startedAt`, potentially creating a phantom running task after the real timer has moved on.
- **Expected/impact:** Shared timing and task state should satisfy “at most one running session, attached only to an existing eligible task.” Currently the user sees contradictory state and incorrect history.
- **Fix:** Let deletion/completion explicitly settle any linked session in the affected task subtree. Restore records with current valid running-session state, not a historical start flag. Timer-agent review covers controller concurrency and process lifecycle separately.
- **Regression scenarios:** Running parent delete; running child parent complete/delete; undo after timer stop; undo after switching sessions; complete through every surface while timing a descendant.

### TG04 — P1: Treat redemption is a nontransactional balance check and can overspend

- **Confidence:** Very high for the race; device timing not reproduced.
- **Evidence:** `data/Rewards.kt:49-52` reads affordability and then updates the item in a separate operation. `ui/TreatsSheet.kt:163-165` launches each tap on a parallel IO scope, does not disable in-flight redemption, and passes a possibly stale `WishlistItem`. `data/Db.kt:100` is an unconditional full-row update.
- **Sequence:** Earn $10; add two $10 wishes; quickly tap Treat on both before list/balance refresh. Two coroutines can both read $10 available before either spend is visible.
- **Actual:** Both are marked redeemed although the user only earned one item's cost. Double tapping the same item can also succeed twice against the same stale unredeemed input; it does not create two rows but still demonstrates lack of a current-state guard.
- **Expected/impact:** Spending must be atomic and idempotent. A visible “available” balance should be a reliable constraint.
- **Fix:** Perform current item read, available-balance calculation, and conditional redemption inside one Room transaction. Serialize wallet commands; use a conditional `redeemedAt IS NULL` update and a result type for success, already redeemed, and insufficient funds. Disable that item's button during the command and show failure feedback.
- **Regression scenarios:** Concurrent purchases when only one fits; same-item double tap; budget change/reopen during redemption; stale screen spanning Monday rollover.

### TG05 — P1: Rewards are computed from mutable tasks, so ordinary edits rewrite earned history

- **Confidence:** Very high; confirmed with SQLite queries.
- **Evidence:** `data/Db.kt:215-219` sums current `rewardCents` on current rows with `completedAt >= weekStart`; `data/Rewards.kt:35-46` caps that sum and subtracts redeemed wishlist costs. `ui/TodoScreen.kt:220-221` clears completion; `ui/TaskActions.kt:27-28` deletes completed records; mentor task editing can change `rewardCents` (`ui/MentorTools.kt:465-472`).
- **Sequence:** Complete a $10 task, redeem a $10 wish, then reopen/delete the task or change its reward. Separately: reopen a previous-week task and complete it this week.
- **Actual:** Earned money vanishes or changes retroactively while spent money remains. The negative state is hidden by `available.coerceAtLeast(0)`. Recompletion moves an old task's reward to a new week. Deleting old task history changes the wallet, even though housekeeping should not create a new money event.
- **Expected/impact:** A reward grant needs a defined lifecycle and a clear reversible policy. Current mechanics are surprising, easy to game unintentionally, and impossible to reconcile after spend.
- **Fix:** Use a reward ledger with immutable credit/debit/reversal events, each tied to a task completion event or wish redemption. Define whether rewards are earned once per task, once per completion, or for explicitly recurring tasks. Reopening should either reverse a credit with a visible policy or leave earned credit intact; deletion should archive task identity without erasing the ledger. Store budget periods separately if weekly budgets may change.
- **Regression scenarios:** Complete → spend → reopen/delete; edit reward on completed task; previous-week reopen/recomplete; budget below already spent; timezone change across week boundary. SQLite fixture showed earned 1000/spent 1000 becoming earned 0/spent 1000 after reopening.

### TG06 — P1: Undo can restore invisible orphan subtasks or deleted-goal links

- **Confidence:** High; relational result confirmed by SQLite fixture. UI sequence depends on snackbar timing.
- **Evidence:** `ui/TaskActions.kt:26-28` snapshots children and issues separate deletes; `:42-43` re-inserts original snapshots without validating current parent/goal state or a transaction. `data/Db.kt:69-83` has no foreign keys. Tasks only displays top-level rows and their children (`ui/TodoScreen.kt:155-158`, `:373`); goal profile also roots rendering at parents (`ui/GoalProfileScreen.kt:69`, `:222`).
- **Sequence:** Delete a child, then delete its parent before undoing the child; press the first Undo, but let the parent's Undo expire. Or delete a goal before undoing a task that belonged to it.
- **Actual:** Undo can put a child back with a missing parent or a task back with a nonexistent goal. The child remains in the database but appears in no task list. Capture/delete/restore can also lose children inserted between operations because it is not atomic.
- **Expected/impact:** Undo should restore a coherent entity tree or explain a conflict. “Undo” should not silently recover data into an unreachable state.
- **Fix:** Use transactional subtree deletion with a deletion token/snapshot. Undo should validate dependencies and restore the parent subtree together, or intentionally promote the task to the inbox when its parent is unavailable. Add self-parent and goal referential rules with supported deletion behavior. Report restore conflicts instead of silently accepting them.
- **Regression scenarios:** Child-delete then parent-delete and first-only undo; several queued deletes; undo after goal archive/delete; concurrent subtask creation during delete; process restart during restore. The fixture restored `(child=2, parentId=1)` after parent deletion and showed zero renderable top-level rows.

### TG07 — P2: Completed subtasks become inaccessible, and reopening parents does not undo their cascade

- **Confidence:** Very high.
- **Evidence:** `ui/TodoScreen.kt:373` displays only incomplete children. Done-today rows (`:409-427`) and archived parent rows (`:452-465`) render no children. `:220-221` reopens only the parent. `ui/GoalProfileScreen.kt:79-85` also only reopens the tapped row, but happens to render all children there.
- **Sequence:** Complete an ungrouped subtask by mistake. Or complete a parent and reopen it immediately.
- **Actual:** The accidentally completed child disappears with no completion Undo and no UI route to reopen an ungrouped child's record. Reopening the parent leaves all automatically completed children checked; the parent can show “2/2 subtasks” with nothing to do. Goal-bound children can be recovered only through a different screen.
- **Expected/impact:** The user's safe reversal path should be visible where the action happened. Parent completion/reopening semantics should be symmetric or explicitly explained.
- **Fix:** Keep completed steps in an expandable group, show “show completed steps,” and provide completion Undo. If a cascade is used, record which child completions were caused by it; undo/reopen can revert only those, preserving previously completed children.
- **Regression scenarios:** Ungrouped child completion/reopen; parent with previously completed and incomplete steps; cascade undo; goal profile and Tasks restoration parity.

### TG08 — P2: Completing tasks fabricates work time; reopening accumulates duplicate history

- **Confidence:** Very high.
- **Evidence:** `ui/TodoScreen.kt:202-214` creates a 15-minute interval whenever the task had no timer or ran less than a minute. `ui/MentorTools.kt:730-738` duplicates that policy. Reopen (`ui/TodoScreen.kt:220`) does not reverse/delete the logged block. `data/Db.kt:26-35` stores no `taskId`, `sessionId`, or completion identifier, preventing reliable matching. `data/TimerController.kt:214` logs a stopped task interval at 10 seconds, so a 10–59-second stopped timer followed by completion creates real time plus a made-up 15-minute interval.
- **Sequence:** Complete a task without timing it, reopen, and complete again; or stop a short timed task and then complete it.
- **Actual:** The calendar repeatedly shows work that did not happen; a guessed marker is stored as an ordinary actual interval. Combined history feeds mentor context and any timeline-based interpretation. Other-screen completion can omit history entirely (TG02).
- **Expected/impact:** Checking a box confirms an outcome, not a duration. Planning, measured time, estimates, and completion markers must be distinguishable.
- **Fix:** Model a completion as a timestamped event with task provenance; show a compact milestone marker if useful. Preserve measured sessions separately. If an estimate is desired, ask or label it as estimated; do not count it as observed work. Use a unique completion event ID to make writes idempotent and support reversal.
- **Regression scenarios:** Repeated complete/reopen; short timed then complete; pause/resume then complete; completion near midnight; same action through goal profile and mentor.

### TG09 — P2: Moving a parent between goals strands its children in both goal profiles

- **Confidence:** Very high; query/render consequence confirmed with SQLite fixture.
- **Evidence:** `ui/MentorTools.kt:467-474` updates only the matched task's `goalId`. `ui/GoalProfileScreen.kt:68` fetches tasks filtered by goal, then `:69-70` roots child rendering at parents in that same filtered set. `ui/TodoScreen.kt:155` uses all tasks, so it still finds the children, masking the inconsistency on the main list.
- **Sequence:** Give a goal-A parent a child; ask the mentor to move the parent to goal B; inspect both profiles.
- **Actual:** A's query finds the child but no parent, so it renders nothing. B's query finds the parent but not its child, so it renders only the parent. Counts can include hidden children and the child disappears from both profiles.
- **Expected/impact:** Goal membership should have one meaning for a task tree. Children either inherit the parent's goal automatically or support deliberate independent goals with an explicit UI.
- **Fix:** Derive a subtask's goal from its root task, or propagate moves transactionally to its descendants. Query/render a coherent tree and validate moves. The mentor-agent review covers its tool argument/matching defects separately.
- **Regression scenarios:** Move parent A→B with completed/running/reminded children; move child alone; remove goal membership; profile counts before/after. Fixture output: A queried child only and rendered no IDs; B queried/rendered parent only.

### TG10 — P2: Goal card and profile use different progress denominators

- **Confidence:** Very high.
- **Evidence:** `ui/TodoScreen.kt:305` passes only `allParents` into goal cards; `:894-925` counts those parent tasks. `ui/GoalProfileScreen.kt:72-73` counts parents plus children. Goal spotlight `data/Db.kt:182-185` selects the next incomplete top-level task, never the first pending small step.
- **Sequence:** Create one parent with nine steps; complete five steps; compare its card and profile.
- **Actual:** The card reports `0 / 1 done`; profile reports 5/10 progress. Completing the parent can instantly jump to 100% by cascading; another completion entry point can show “All done” on the card with remaining children in the profile.
- **Expected/impact:** The same goal should have one progress model and an actionable next step. Arbitrary subdivision should not dramatically change reported progress.
- **Fix:** Choose root-outcome progress, leaf-step progress, or effort-weighted progress and use it consistently. Show both outcomes and steps if needed (e.g. “0/1 outcomes · 5/9 steps”). Derive “next action” from the chosen dependency rule, normally the first incomplete leaf under the next parent.
- **Regression scenarios:** Vary subtask counts while outcomes remain identical; completed parent with remaining children; goal with only completed tasks; move task between goals.

### TG11 — P2: Reordering a filtered goal corrupts global ordering and writes unrelated fields

- **Confidence:** Very high.
- **Evidence:** `ui/TodoScreen.kt:158` restricts `parents` to the selected goal. `:185-187` writes positions `0..N` for only the visible active rows, while `data/Db.kt:170` orders all tasks by `sortOrder` without a tie-breaker. Other goals can already have those same positions. The whole-row write also causes TG01 rollback.
- **Sequence:** Long-press goal A to filter it and reorder its tasks; filter goal B and reorder those; return to the unfiltered list.
- **Actual:** Each subset is independently assigned identical global positions. Unrelated rows shift, and tasks across goals tie with no explicit stable ordering. Reordering is not a safe local operation.
- **Expected/impact:** The user expects their folder order to persist without unexpectedly reshuffling other work.
- **Fix:** Decide global vs per-goal ordering. For global order, reorder selected IDs within the positions they occupy in the complete order. For per-goal order, store/query the order scope and define an intentional inbox sort. Persist only the order field and use a stable secondary key.
- **Regression scenarios:** Interleaved two-goal tasks; reorder both filtered views; complete/reopen task between drags; new task added while dragging; drag with a changed title/reward/timer.

### TG12 — P2: Core reminder permission is requested only when starting a Focus session

- **Confidence:** Very high from complete permission-call search; OS symptom depends on Android version/install state.
- **Evidence:** Manifest declares POST_NOTIFICATIONS (`app/src/main/AndroidManifest.xml:14`), but the only request in the app is `ui/TimerScreen.kt:168-169` inside Focus onStart. App launch eagerly schedules goal/task reminders (`MainActivity.kt:61-63`); task reminders can be created through mentor (`ui/MentorTools.kt:365`). `data/TaskReminders.kt:84` and `data/GoalReminders.kt:79` swallow notification failures. Task Play uses `TimerController.startTask` directly with no permission education/request.
- **Sequence:** Fresh Android 13+ install; create a goal or ask mentor for a reminder; never start Focus; wait for the reminder.
- **Actual:** Reminders can be correctly stored/scheduled but invisible because the permission was never requested. UI/mentor reports no actionable failure. Starting Focus incidentally unlocks unrelated reminders.
- **Expected/impact:** A reminder feature needs clear delivery readiness at the moment the user enables it. Notification success should not depend on discovering another feature.
- **Fix:** Provide one notification capability/settings state. Request permission in context when enabling the first reminder or starting a task timer; expose denial/revocation and a settings link. Preserve tasks/reminders but show a visible “notifications off” status. Avoid swallowing all failures without recording capability state.
- **Regression scenarios:** Fresh install on 13+; deny once; revoke later; allow through system settings; goals-only workflow; mentor-created reminder before any focus use.

### TG13 — P2: Multiple task reminders overwrite one another and do not deep-link to their task

- **Confidence:** Very high.
- **Evidence:** Every task uses `manager.notify(1000, notification)` at `data/TaskReminders.kt:84`, regardless of task identity. Notification activity intent `:67-72` only asks for the Tasks tab. Goal spotlight opens a generic launch intent (`data/GoalReminders.kt:64-68`) with no goal or tab. Alarm identities are distinct for normal task IDs, but notification identities are not.
- **Sequence:** Schedule two tasks near each other; leave the first reminder unacknowledged until the second fires; tap the remaining notification.
- **Actual:** The second replaces the first notification. Its tap sends the user to the whole task list; the reminded task may be collapsed inside a parent or hidden by another goal/filter. Goal reminder can open Calendar rather than the featured goal.
- **Expected/impact:** Each reminder is a promise to surface a particular action. Replacing unrelated reminders and failing to locate the action makes them easy to miss and increases friction.
- **Fix:** Use stable per-task notification IDs (or an intentional grouped summary), pass task/goal IDs through typed navigation, and reveal/highlight the relevant task. Add appropriate Complete/Snooze actions through the shared task/reminder service.
- **Regression scenarios:** Simultaneous reminders; reminder for child; goal-filtered view; already-running activity vs cold start; goal/task deleted before tap.

### TG14 — P2: Reminder receiver work outlives `onReceive` without preserving the process lifetime

- **Confidence:** High platform-lifecycle risk; device kill timing not reproduced.
- **Evidence:** Normal task alarm handling correctly uses `goAsync` (`data/TaskReminders.kt:96-106`), but the boot branch `:90-92` calls `rescheduleAll`, which launches an IO coroutine (`:40-45`) and returns. `data/GoalReminders.kt:85-88` calls notify, which launches a coroutine (`:46-80`) without `goAsync`, even for ordinary spotlight delivery.
- **Sequence:** Boot or receive a goal alarm when the app process has no other active component; OS can terminate the process after receiver return before Room query/notification or rearming finishes.
- **Actual:** Goal alerts or task rearming can be skipped depending on process lifetime. This is precisely the class of intermittent background failure not caught by a foreground UI smoke test.
- **Expected/impact:** Receiver work should remain owned until its bounded operation completes.
- **Fix:** Use `goAsync` with `finish` in `finally` for short work; use WorkManager/job scheduling for any long operation. One-shot local-time rescheduling can replace fixed-24-hour repeating spotlight schedules (`data/GoalReminders.kt:35-36`) to avoid DST drift. Listen/recompute appropriately for time/timezone changes.
- **Regression scenarios:** Force-stop versus ordinary process death documented separately; reboot with reminders; alarm with process absent; DST transition; timezone change; battery idle.

### TG15 — P2: Goal deletion/archive semantics differ and can leave an invisible active filter

- **Confidence:** Very high.
- **Evidence:** UI deletion clears todo goal links and permanently deletes the goal (`ui/TodoScreen.kt:551-557`), without Undo/confirmation. Mentor deletion archives the goal but leaves goal links intact (`ui/MentorTools.kt:502-508`). Goal query hides archives (`data/Db.kt:139`). Tasks retains `filterGoalId` until its own UI delete callback clears it (`ui/TodoScreen.kt:130`, `:158`, `:554`); there is no reconciliation when the selected goal disappears from the DB. Existing activity blocks (`data/Db.kt:34`) and running timer goal preferences are never cleared by UI deletion.
- **Sequence:** Long-press a goal to filter; on a large screen or during an in-flight mentor operation archive it; revisit/keep the mounted Tasks pane. Also compare Delete in profile with “delete goal” in mentor.
- **Actual:** A filtered view can keep selecting an ID whose card is absent, hiding unrelated work without a visible clear-filter control. The same named action either removes recovery/history association or preserves an archive depending on entry point. Permanent deletion leaves historical/session goal IDs dangling.
- **Expected/impact:** Goal retirement should preserve history and show clear scope. Destructive deletion should be a separate, recoverable, explicit action.
- **Fix:** Use Archive as the standard goal action across surfaces; provide an archive browser and restore. Reconcile selected IDs against live goals, display a visible “All tasks / Goal A” filter, and define historical goal retention. If permanent deletion remains, apply a deliberate relational policy atomically and offer Undo or a clear destructive explanation.
- **Regression scenarios:** Archive selected goal while Tasks remains mounted; delete goal with active timer and history; archive/restore through mentor and UI; undo task after goal deletion.

### TG16 — P2: Clock, budget, and task changes do not consistently refresh money/day UI

- **Confidence:** Very high.
- **Evidence:** Tasks earnings recompute only on `LaunchedEffect(tasks)` (`ui/TodoScreen.kt:135-139`); TreatsSheet recomputes only on wishlist/refresh (`ui/TreatsSheet.kt:70-74`), even though tasks can change under its still-mounted background screen. Budget changes therefore do not refresh the Tasks chip after the sheet closes. Tasks day partition calls `LocalDate.now()` only on recomposition (`ui/TodoScreen.kt:150`), and its clock ticks only while a task has `startedAt` (`:141-148`). DAO `earnedCentsSinceFlow` exists (`data/Db.kt:219`) but is unused. Widget is updated only around timer actions (`data/TimerController.kt:235`, `:250`, `widget/BiboWidget.kt:96`, `:110`), not normal task/reward changes.
- **Sequence:** Leave Tasks open idle over midnight or Monday; open Treats then complete a goal task in a still-mounted pane; change weekly budget then dismiss Treats; complete a rewarded task with a homescreen widget installed.
- **Actual:** Done-today grouping and displayed earnings can stay on the old day/week. Treat affordability can be stale (redeem checks a newer balance but silently fails). Tasks chip/widget may disagree with the Treats sheet until unrelated data or timer changes.
- **Expected/impact:** “Today”, weekly earnings, and affordability should have one current state, including after resume and day/timezone rollover.
- **Fix:** Provide injectable clock/day/week state and observable budget/ledger flows; combine them into a shared wallet UI state. Refresh on lifecycle resume and scheduled boundaries. Drive widget invalidation from relevant domain changes rather than only timer stop.
- **Regression scenarios:** Idle midnight/Monday; budget change; task completion under open sheet; time/timezone change; resume after several days; widget update after task completion and redemption.

### TG17 — P2: Basic creation/editing/recovery actions are missing or hidden in the UI

- **Confidence:** Very high for missing routes; layout/accessibility risks need device verification.
- **Evidence:** Empty Tasks state `ui/TodoScreen.kt:250-276` omits `GoalSummaryRow`, which contains the only New Goal UI (`:941-955`), so the first goal cannot be created manually before creating a task. Goal filtering is only `combinedClickable.onLongClick` (`:906-908`) with no visible filter chip or instruction. Task Edit (`:564-587`) only edits title; new task sheet (`:788-878`) has goal/reward but no due/reminder fields; those exist only in mentor tools. Treat wishlist row (`ui/TreatsSheet.kt:238-241`) only shows Remove if an item is unaffordable, so an affordable or redeemed wish cannot be removed; no price/name editing exists. Add/goal sheets use non-scrollable Columns without `imePadding` (`ui/TodoScreen.kt:812`, `:980`) despite variable goal/color content. Goal overlay has no `BackHandler` (`ui/GoalProfileScreen.kt:61`, entire file).
- **Sequence:** Fresh empty app → try to create goal; try to move/change due date or cancel reminder without AI; make a wish affordable then try to remove it; use a small phone/large font/keyboard; open goal profile and use Android Back.
- **Actual/impact:** Users must discover hidden gestures, create placeholder tasks, or ask the model to perform routine configuration. Affordability unexpectedly removes a management affordance. Edits/filters and a missing local reminder editor make it hard to diagnose apparent missing work. Layout and Back behavior can compound this on actual devices.
- **Fix:** Always show goal/task creation entry points. Introduce a task detail editor with title, goal, due date, reminder, reward, steps, and clear state. Add visible All/Goal filters. Give every wish an independent overflow Edit/Remove action, regardless of affordability/redemption. Make sheets scrollable and keyboard-safe; use normal navigation with explicit Back handling and save drafts on recreation.
- **Regression scenarios:** Empty-first-run goal; keyboard at large font on compact device; 20 goals in picker; edit due/reminder without credentials; removal in all wish states; Android Back from profile; rotate/fold with an unsaved editor.

## Architecture recommendations

The immediate fix is a consistent domain layer, not a wholesale framework rewrite. Keep Compose and Room; make the screens consumers of state and commands.

1. **One task workflow service.** Compose, AI tools, timer service, widget, notifications, and tile invoke the same ID-based commands. These commands own validation, transactions, timer settlement, history provenance, reward events, reminders, and error results. ViewModels expose immutable state and in-flight operation states; `AppScope` alone is not a substitute for a shared consistency model or process durability.
2. **Separate task outcome, time session, completion event, and reward ledger.** `TodoTask.startedAt`, timer preferences, TODO activity blocks, and computed wallet currently conflate independent concepts. A stable session/completion ID gives idempotency and a known restoration path. A checkmark need not fabricate a duration.
3. **Enforce tree/goal invariants.** Decide whether children inherit goals; enforce in one command/transaction and schema. Parent state must have a documented relation to child state. Use transactional subtree operations and explicit undo tokens, not arbitrary historical object insertion. Add stable order scoping and secondary keys.
4. **Observe state from one place.** Rows read the newest task records. Drag state contains identities only. Wallet observes ledger, budget period, and clock. Notification capability and reminder schedule are visible states; the domain layer reports failures rather than silently swallowing them.
5. **Treat background work as owned jobs.** Use bounded receiver lifetimes and persistent scheduling for deferred work. Reconcile timer/session state after process death and restart. Centralize time/date/week computations behind an injected clock and zone.

## Product/UX proposal

- Make the main Tasks screen an inbox with visible All/Goal filtering and a clear first action. Goal cards show an understandable, consistent progress summary and next actionable step. Creation should remain available in the empty state.
- Let a tap open task details; keep a labeled checkbox for completion, a consistent timing control, and accessible menus. Do not require long-press or voice/AI for common management. Swipe completion/deletion must have an obvious Undo and a current state/result.
- Show completed steps within their parent so progress is recoverable. Offer explicit “Complete remaining steps” rather than silently completing a tree; reopening should explain exactly what is restored.
- Use Archive for goals and completed work to protect progress/history. Make permanent removal a separately named action with a recovery path.
- Present the calendar as planned events + measured sessions + completion markers, with clear provenance. Users should never infer measured effort from a guessed interval.
- Decide what Treats is for: money actually set aside weekly, symbolic points, or a wishlist progress game. Explain earning, spending, reset, and reversal policy in the UI. A weekly cap currently makes wishes over the cap impossible to afford; either clarify this or support carryover savings. Choose currency explicitly rather than hard-coding dollar symbols and mixing Russian/English copy.
- Give reminders direct task/goal navigation, optional snooze/complete, visible permission status, and user-controlled timing. A completed goal should receive congratulation/archive suggestions rather than “No next task yet — add one.”

## Useful regression matrix

Test combinations, not just buttons in isolation. Build a small domain fixture containing two goals, one parent with completed/incomplete steps, a running child, reminders, earned rewards, and redeemed wishes. For each supported entry point, exercise:

| Combination | Required invariant |
|---|---|
| Edit → drag → complete | Edited fields survive; one completion event; correct timer settlement |
| Start task → start another → stop/reopen | At most one coherent session; no phantom row flags |
| Running child → complete/delete parent → undo | No timer attached to missing/completed tasks; coherent tree restore |
| Complete child → complete parent → reopen/undo | Visible, defined recovery; preserve independently completed steps |
| Move parent A→B | Entire tree belongs to a consistent goal; no hidden rows |
| Delete child → delete parent → first Undo | No invisible orphan |
| Two wishes redeem concurrently | Total debits cannot exceed permitted credits/budget |
| Complete → spend → delete/reopen/edit reward | Defined, auditable ledger policy |
| Filter A → reorder → filter B → reorder | Stable intentional global/per-goal order |
| Deny/revoke notifications → set reminder | Visible readiness and recovery path |
| Midnight/Monday/timezone/resume | Current day/week everywhere; no stale affordability |
| Process death/reboot during alarm/session/write | Reconciliation or explicit pending result; no silent split state |

## Coverage and limits

Supplemental root runtime verification: Start task → unchanged Play → second tap restarts → Complete leaves the shared timer running and adds a synthetic fifteen-minute block was reproduced on Android 36. See [TASK_TIMER_REPRO.md](/workspace/bibo-audit/TASK_TIMER_REPRO.md) for screenshots and persisted data. The sub-audit's SQL/source evidence and runtime limits below remain distinct.

Full reads: `ui/TodoScreen.kt` (1065 lines), `ui/GoalProfileScreen.kt` (270), `ui/TaskActions.kt` (46), `ui/TreatsSheet.kt` (245), `data/Rewards.kt` (78), `data/TaskReminders.kt` (110), `data/GoalReminders.kt` (91), relevant `data/Db.kt` entities/DAOs/migrations, `data/AppScope.kt`, and `data/TimerController.kt` (252). Cross-file inspected: task/goal mutation paths in `ui/MentorTools.kt`, permission/start/state logic in `ui/TimerScreen.kt`, launch/rearming/navigation in `MainActivity.kt`, `AndroidManifest.xml`, wallet/task actions and refresh hooks in `widget/BiboWidget.kt`, and `README.md`.

No committed unit/instrumented test files were found. In-memory SQLite checks confirmed goal-move rendering loss, reward reopening inconsistency, and orphan-subtask restoration. They validate query/data consequences, not Compose lifecycle/device behavior. No runtime emulator interaction, source mutation, or production data changes were performed for this sub-audit. Parent audit may add separate runtime evidence.
