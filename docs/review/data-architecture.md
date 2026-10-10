> Historical audit of commit `8e63d9d`, before the repairs. See [the implementation report](../IMPLEMENTATION.md) for current behavior and validation.

# Data architecture and cross-feature contracts audit

Reviewed checkout: `/workspace/bibo`, commit `8e63d9d`. Current Room database version is **12**, although README still says v11. This is a source and synthetic SQLite review; this agent did not operate the emulator or call an AI/calendar API. No AGENTS.md was found under `/workspace`. No source files, tests, manifests, dependency declarations, or lockfiles were edited.

The central problem is that a single user command has several independent implementations, while durable state is spread across Room, SharedPreferences, Android services, and captured Compose objects. Moving coroutines from a screen scope to `AppScope` addressed navigation cancellation but did not make commands atomic, serialize conflicting changes, or survive process death.

## Ranked findings

### D1 — P1: The reorder list retains stale task objects, then writes them over newer database state

**Evidence:** `ui/TodoScreen.kt:164-166` constructs `ordered` with `remember(activeParents.map { it.id })`. A Room update of title, startedAt, reminder, reward, due date, or goal does not recreate the list when the IDs and order are unchanged. Rows and their callbacks use the old objects at `:324-349`. `persistOrder()` at `:184-187` sends whole objects to `TodoDao.updateAll`; `data/Db.kt:158-162` uses Room `@Update`. The generated adapter confirms all columns are overwritten: `app/build/generated/ksp/debug/kotlin/com/bibo/data/TodoDao_Impl.kt:96-97`.

**User sequence:** Add two tasks A and B. Rename A while remaining on Tasks. Room has the new name, but the active row still owns the old name. Drag A below B; `persistOrder` writes the old name back. Alternatively start A's timer; `TimerController.startTask` writes startedAt at `data/TimerController.kt:137-139`, but the row retains startedAt=null. Its next Play tap starts a replacement timer; completion can skip clearing the real running timer because it tests that stale startedAt at `TodoScreen.kt:196-200`.

**Impact/confidence:** Silent field loss and inconsistent timer behavior. High confidence from the direct Compose key and generated SQL; emulator confirmation belongs in the main audit. A synthetic SQLite replay of the generated update query restores `Old title` and clears startedAt after an intervening edit/start.

**Fix:** Keep drag order as a list of IDs; derive each displayed task from the latest Room map. Persist only `UPDATE todo_tasks SET sortOrder=:position WHERE id=:id` in one transaction. Keep editors as intentional drafts and issue field-specific commands instead of copying an entire historic row.

**Regression:** Rename then reorder; start timer then reorder; edit due/reward/goal during drag; concurrent completion followed by drag; same sequence on two-pane layout. Assert every unrelated field survives and only the linked running task has a timer.

### D2 — P1: Completing the same task has different effects depending on the entry point

**Evidence:** Tasks completion creates an ActivityBlock, stops a linked task timer, and completes children (`ui/TodoScreen.kt:191-219`). Goal-profile completion only updates parent/children and clears startedAt (`ui/GoalProfileScreen.kt:76-85`), so it creates no calendar block and does not stop TimerController. Mentor completion stops the linked timer and creates a block (`ui/MentorTools.kt:715-739`) but does not complete children.

**User sequence:** Start a goal task from Tasks, open its goal profile, and complete it. The database row becomes done and stopped, while the foreground timer and notification keep running. Stopping that timer later logs time after the task was completed. Without a timer, completing from the goal produces no calendar entry, whereas checking the same task in Tasks does. Ask Mentor to complete a parent with unfinished steps: the parent is done but the children remain open.

**Impact/confidence:** Core cross-feature contradictions and misleading history. High confidence, code-confirmed; no product policy can explain the running timer remaining active while its task is done.

**Fix:** One `completeTask(taskId, commandId)` service shared by Tasks, goal profile, Mentor, and future widget actions. Define parent/child completion policy explicitly and implement it once. Return the authoritative changed state and effect receipt.

**Regression:** Complete a timed/untimed parent or child from every entry point; repeat command; fail between block and task writes; complete an already-completed task. Assert consistent child policy, one completion marker, and no active timer for the completed task.

### D3 — P1: Stopping a timer clears its durable running state before history is committed

**Evidence:** `data/TimerController.kt:192-203` captures SharedPreferences then clears them and stops the service. Only later an unowned `CoroutineScope(Dispatchers.IO).launch` inserts the ActivityBlock and clears the task row (`:226-233`). There is no transaction spanning those writes, no persisted session ID, and no recoverable finishing state. `clear()` (`:243-250`) similarly destroys the only session record before its caller commits completion. `MainActivity.kt:64-67` can only recover sessions whose prefs still say running.

**User sequence:** Stop a long session, then the process is killed or the Room write fails before the asynchronous insertion. Relaunch: neither a running session nor a finished block exists. A linked task can retain startedAt despite no running timer. Completing through Tasks has the same failure window because `TimerController.clear` precedes inserts and task updates.

**Impact/confidence:** Loss of real work history. High confidence failure-window analysis; process-death reproduction was not attempted here.

**Fix:** Persist active/finished session state in Room with a stable session ID. Within a single transaction finalize the session, create its history representation exactly once, and clear the task link. Stop the notification/service after commit; reconcile platform effects on startup. A finishing record/outbox can bridge platform operations if needed. Changing AppScope alone does not fix this.

**Regression:** Kill/relaunch at each boundary of start, switch, complete, and stop; inject an insert failure; press Stop from widget and notification close together. Assert the session survives once and the active task link agrees with it.

### D4 — P1: Independent timer jobs can overwrite a newer session's task state

**Evidence:** `TimerController.startTask` writes prefs synchronously but stamps the task asynchronously (`:124-139`). `stopTimer` clears prefs synchronously but clears startedAt asynchronously (`:192-233`). Neither job checks that its start/session is still current when it writes; stop clears any non-null startedAt, including a newer one. There is no mutex for timer lifecycle commands. Mentor tools can call start/stop on IO (`MentorTools.kt:619-643`) while widget/tile/service/UI invoke the same controller.

**User sequence:** Start task A, rapidly stop and restart A from a different surface. If the older stop job runs after the restart's stamp, it clears A.startedAt although the new timer is running. The inverse ordering can stamp a stopped task as running. SharedPreferences reads across the multi-step lifecycle can also interleave for two callers.

**Impact/confidence:** Stuck spinners, hidden running timers, and stopping the wrong session. High confidence concurrency window; timing is nondeterministic.

**Fix:** Serialize timer commands and use an explicit session ID; persist task association in the same transaction. If a transitional patch is needed, make startedAt updates conditional on the exact expected old value/session and reject old jobs. Avoid using two separate persisted fields as sources of truth for one active session.

**Regression:** Deterministically pause start/stop writes and release in reversed order; repeated A→B→A switches; simultaneous Mentor and notification Stop. Verify only one active session and one matching task link.

### D5 — P1: Parent completion and task deletion can leave a child/deleted task's shared timer running

**Evidence:** Tasks completes children at `TodoScreen.kt:217-219`, but clears TimerController only when the parent itself is its linked task at `:199-200`. Goal-profile has no controller check. Task deletion in `ui/TaskActions.kt:26-28` and `MentorTools.kt:438-440` never examines the active timer. No foreign key ties a running timer to a valid task.

**User sequence:** Start timing child C, complete its parent P. C.startedAt becomes null and C becomes complete, but TimerController still points at C. Or delete the task currently being timed. Its notification continues to count and later writes a TODO block with no surviving task.

**Impact/confidence:** Another direct example of feature combinations breaking each other. High confidence.

**Fix:** Completion/deletion service must resolve an active session linked to the affected subtree and apply an explicit finish/cancel/preserve-history policy. Undo should restore task state without resurrecting a stopped session.

**Regression:** Complete/delete a parent while each child is timed; delete the timed task and undo; delete via Mentor; stop later from notification. Assert the controller cannot reference a removed/completed task.

### D6 — P1: Rapid habit changes replace other changes with an old full-day snapshot

**Evidence:** `ui/HealthScreen.kt:128-136` copies the last emitted HabitDay then asynchronously upserts the entire row. Both toggles can read the same snapshot before Room emits. Mentor performs read-copy-upsert at `MentorTools.kt:607-615`; food/habit parsing performs another read-copy-upsert at `HealthScreen.kt:158-165`. `data/Db.kt:258-289` has whole-row upsert rather than a named-habit patch.

**User sequence:** Starting with all habits off, tap Shower and Clothes quickly. Both commands start from all-false; whichever whole-row upsert finishes last can turn the first habit back off. A voice log or Mentor habit command can similarly overwrite a concurrent manual change.

**Impact/confidence:** Lost habit data; high confidence. Synthetic SQLite interleaving with the same snapshot ends showered=0, cleanClothes=1 after two positive taps.

**Fix:** `setHabit(day, habit, done)`/`toggleHabit(day, habit)` service with atomic insert-if-missing and update of just that column, or normalized unique (day, habit) rows. Toggle should evaluate the current database value under serialization/transaction. Merge only fields explicitly present in a parsed command.

**Regression:** Rapid toggles of distinct/same habits; voice+tap; Mentor+tap; cancellation/navigation. Assert no unrelated habit changes and final state reflects command order.

### D7 — P1: Date changes during asynchronous parsing can save health data to the wrong day

**Evidence:** `HealthScreen.kt:140-141` begins parsing before capturing epochDay. Its later independent IO job reads the live mutable epochDay for every food insert and habit lookup/upsert (`:148-165`). The date can change through arrows (`:200-205`) or week strip (`:238`). The confirmation appears before that job completes at `:167-173`.

**User sequence:** Choose yesterday, submit a meal, navigate to today while ML parsing runs. The meal is stored under the new selected date. There is also a narrower window where navigating between the food loop and habit write sends one parsed command to multiple days.

**Impact/confidence:** Wrong-day records with a success message; high confidence mutable capture.

**Fix:** Capture immutable `targetDay` and input before any suspension; parsed commands carry that target through one transaction. Update UI confirmation only after commit, and associate pending status/errors with that request.

**Regression:** Slow parser, multiple food entries, and date navigation at each suspension/write boundary. Assert all entries/habits land on the original chosen day.

### D8 — P1/P2: Referential integrity exists only as scattered, incomplete application code

**Evidence:** `data/Db.kt:69-83` stores parentId/goalId without Room foreign keys or indexes. ActivityBlock.goalId also has no FK (`:25-35`). Goal deletion clears only task references (`TodoScreen.kt:555-557`, DAO `Db.kt:179-180`), retaining deleted IDs in historical blocks and timer prefs. Undo restores old objects independently (`TaskActions.kt:41-43`) without validating whether their parent or goal still exists. Parent goal editing changes only its own goalId (`MentorTools.kt:467-474`), while child rows keep old goalId. Goal profiles query rows by their own goalId (`Db.kt:206-207`).

**User sequence:** Delete child C and leave its Undo pending; delete parent P; Undo C but do not Undo P. C is restored with parentId=P despite P being absent, so top-level filters cannot show it. Delete a goal after a focus session: the block's goal ID has no remaining goal name. Move a parent to another goal with Mentor: children disappear from both profiles because neither query has a matching parent/child group.

**Impact/confidence:** Invisible/orphan records and broken histories. High confidence; synthetic SQLite confirmed a restored child with nonexistent parent and a block retaining the deleted goal ID. The Tasks/goals audit independently examines these sequences.

**Fix:** Choose explicit reference policies. Parent→child can cascade on permanent deletion; validate the supported one-level hierarchy. Goal deletion should archive by default or set current links null while preserving name/color snapshots for history. Derive child goal membership through the parent instead of maintaining redundant goal IDs, or update the subtree transactionally. Undo operates on a tombstoned/versioned subtree with parent validation, not unrelated old inserts.

**Regression:** Interleaved child/parent deletes and undos; goal deleted during task editor; undo after goal deletion; parent moved while child editor open; active timer during goal archive. Verify no invisible or dangling live references.

### D9 — P2: Rewards are recomputed from mutable task fields rather than earned transactions

**Evidence:** `data/Db.kt:215-219` sums rewardCents on currently completed task rows. `Rewards.kt:35-46` treats that sum as money banked/spendable. Reopen changes completedAt to null (`TodoScreen.kt:220-221`); deleting a completed parent removes it; Mentor can edit rewardCents on completed tasks (`MentorTools.kt:454-474`). There is no earned-at snapshot or ledger.

**User sequence:** Complete a $5 task and spend the earnings on a treat. Delete that completed task to clean the archive: earned money disappears, although the treat remains spent. Alternatively ask Mentor to change the reward of an already completed task from $5 to $10: the available balance increases without another completion. This makes edit/delete history semantics affect an unrelated feature.

**Impact/confidence:** User-visible balance changes from housekeeping/editing; high confidence in behavior. Whether reopen should reverse earnings is a product decision to specify. Deleting historical tasks should not silently invalidate past rewards if the app promises money is banked.

**Fix:** An append-only reward ledger stores earn/spend/reversal entries with command ID, amount, effective week, task/wishlist references, and label snapshots. Define reopen and post-completion edits explicitly; do not reprice historic earnings. Budget/week policy becomes stable input to the ledger query.

**Regression:** Complete→edit reward; complete→delete; complete→reopen after spending; repeat completion command; week rollover; budget reduction below spent amount. Assert a deterministic, explainable balance.

**Scope correction:** Although TreatsSheet passes an onDelete callback for each item (`:168`), `WishlistRow` at `:238-241` does **not expose Remove for redeemed items**. A direct database deletion would erase historical spend, but deleting a redeemed wishlist item is not currently a UI-reproducible bug and must not be reported as one.

### D10 — P2: Two redemptions can both pass a budget check

**Evidence:** `Rewards.kt:49-53` checks the passed object's redeemedAt and calls availableCents, then independently updates a copied row. No transaction/mutex, no current-row fetch, no guarded update, no idempotency key. TreatsSheet launches each press separately on AppScope (`:163-166`) and does not disable pending redemptions.

**User sequence:** With $10 available and two $10 treats, tap both Treat buttons before flows refresh. Both jobs can read $10 available before either persists; both redeem. Available later shows $0 because it clamps negative values, hiding the overspend.

**Impact/confidence:** Budget violation; high confidence race, timing-dependent. Rapid duplicate taps on one item do not double charge under the present one-row sum, but different items can.

**Fix:** One transaction reads current item state, calculates spendable balance from a consistent week/budget snapshot, then creates a guarded/idempotent spend record. Return success/insufficient balance/already redeemed result for UI feedback.

**Regression:** Barrier-controlled concurrent redemptions of two items; duplicate taps; budget/week change during redemption. At most one $10 redemption succeeds against $10.

### D11 — P2: Most composite writes expose partial durable states

**Evidence:** No `@Transaction` or `withTransaction` appears in app sources. Examples: create parent then children/reminder (`MentorTools.kt:354-365`); delete children then parent (`TaskActions.kt:26-28`); restore parent then children (`:42-43`); completion block and task (`TodoScreen.kt:207-219`, opposite order in `MentorTools.kt:732-739`); clear goals then delete (`TodoScreen.kt:556-557`); meal entries then habit (`HealthScreen.kt:149-165`); usage-window delete then insert (`UsageRepo.kt:165-168`). Room's generated individual DAO writes are transactional, but that does not combine these caller-level sequences.

**User sequence:** Any database failure/process interruption between operations yields a task with partial steps, history without completion, completion without history, or an empty usage cache. Other concurrent feature reads can observe the intermediate state even if writes eventually succeed.

**Impact/confidence:** Broad failure amplifier. High confidence. This finding explains several above; do not double-count it as a separate user bug without a concrete failure sequence.

**Fix:** Command-sized Room transactions; perform external calendar/alarm/service work after commit with reconciliable effect status. Keep transactions short and do not hold them across AI/network calls.

**Regression:** Fault injection after each write in each composite command; concurrently observe flows during commands. Verify commit-all/rollback-all and idempotent reconciliation of external effects.

### D12 — P2: AppScope survives navigation, but has no durable job records or structured error reporting

**Evidence:** `data/AppScope.kt:19-20` is a process-local SupervisorJob+IO scope. UI call sites mostly use bare launch with no error/result handling, e.g. Tasks inserts (`:494-506`), delete undo, goal updates, Treats writes, and Health habit writes. Success haptics, dismissals, and in some cases success snackbar occur before commit. A root launch exception can become an uncaught coroutine exception; SupervisorJob prevents sibling cancellation but does not suppress exceptions or make writes durable.

**User sequence:** Submit a task/meal, sheet clears, DB operation fails. The user has no recoverable draft or accurate failure state. Process death kills all pending AppScope jobs despite the comments describing durable writes.

**Impact/confidence:** Error handling/ownership gap; high confidence architecture observation. A database failure was not injected into the app.

**Fix:** A ViewModel submits immutable commands to an application-owned repository/service and observes command results. Immediate local commands commit before showing success. Long/retryable jobs use persistent status and appropriate WorkManager/service ownership, rather than treating AppScope as durability. Report domain failures, retain drafts, and log diagnostic IDs.

**Regression:** Fail DB writes deliberately; leave/reenter the screen while a command is pending; kill process; retry command. No false success and no silent duplicate effect.

### D13 — P2: Session and completion concepts are conflated, preventing trustworthy totals and undo

**Evidence:** ActivityBlock contains title, interval, and arbitrary source string but no taskId, sessionId, completionId, event origin ID, or interval quality (`Db.kt:25-35`). Task timer stops write source TODO (`TimerController.kt:207-222`); untimed task completion fabricates a 15-minute interval (`TodoScreen.kt:202-206`), also source TODO. Real focus totals exclude TODO (`TimerScreen.kt:110-112,127-130`; `DayReview.kt:30-32`; `Mentor.kt:833-838`). Reopening a task changes its row without identifying/removing its completion marker (`TodoScreen.kt:220-221`).

**User sequence:** Work 45 minutes using a task timer: calendar gets the session, but Focus/Daily/Mentor focused time omits it. Complete an untimed task then reopen/recomplete: the calendar accumulates unrelated synthetic 15-minute blocks. Deleting/editing a block cannot reconcile the original task because its ID was never stored.

**Impact/confidence:** Ambiguous history and inaccurate cross-feature feedback. High confidence. These are distinct data concepts; simply including all TODO blocks in focus totals would incorrectly count fabricated work.

**Fix:** Separate actual WorkSession from TaskCompletion and CalendarEvent/ActivityLog. Store task/session/completion linkage and provenance; expose a projected CalendarItem for rendering. A completion marker is a marker, not a claimed 15 minutes of work. Explicitly decide whether edits change only history snapshots or the original task.

**Regression:** Actual task work vs untimed completion; repeated completion/reopen; calendar deletion of a marker vs real session; linked goal totals. Assert truthful durations and traceable effects.

### D14 — P2: Daily totals sum full overlapping sessions, and observed week/day boundaries are inconsistent

**Evidence:** ActivityBlock overlap queries are correctly half-open (`Db.kt:45-57`), but `TimerScreen.kt:127-130`, `DayReview.kt:32`, and `Mentor.kt:838` sum full session length rather than clipping to the requested day/week. TimerScreen's weekBlocksFlow is remembered only by weekStart although its end parameter is dayEnd (`:125`), so staying on Focus across midnight within one week can keep yesterday's end bound. Health retains a selected day initialized with LocalDate.now (`HealthScreen.kt:102`); Todo earned state is recomputed only when tasks change (`TodoScreen.kt:137-139`); Treats money is recomputed only on wishlist/refresh (`TreatsSheet.kt:71-74`). No central day/week/timezone observable exists. Timers measure duration with wall clock (`TimerController.kt:126,194,206,214`) rather than a monotonic elapsed clock.

**User sequence:** A session from 23:30 to 00:30 is counted as one full hour in each day's review. Keep the app open over midnight/Monday: date-sensitive projections/balances may stay stale while another surface recomputes. Manually move the device clock backwards during a timer: stop can discard it through the minimum-length check.

**Impact/confidence:** False totals and boundary bugs; high confidence source paths. Travel/timezone policy is undefined and deserves specification rather than an assumed correct behavior.

**Fix:** Inject Clock and a reactive local-day/week/timezone provider. Clip every interval using max(start, windowStart)/min(end, windowEnd); key flows by the full window. Track elapsed time monotonically during a boot and keep timestamp/boot reconciliation for persisted sessions. Decide whether historic daily logs keep their recorded local date/zone or regroup under the current zone.

**Regression:** Midnight, Monday reset, cross-week/multi-day session, DST 23/25-hour days, timezone change, wall-clock adjustment, process restart. All surfaces use the same boundary contract.

### D15 — P2/P3: Cached usage refresh can erase its replacement window or briefly report empty/stale data

**Evidence:** `UsageRepo.kt:159-162` marks lastIngest before doing the read/write work, so another read within the throttle window skips waiting for the in-progress refresh and can read old or empty Room rows. `:165-168` prunes/deletes/inserts outside one transaction; failure/cancellation after delete leaves the most recent 48 hours erased until a successful later ingestion. Refresh only occurs when a usage query invokes it; Mentor gathers directly from Room (`Mentor.kt:863-874`), so coaching can use stale/missing usage if Stats/Calendar has not refreshed.

**User sequence:** Open Calendar and Stats (or two-pane consumers) against a fresh cache together: one begins ingestion and the other can report no usage. Interrupted refresh causes recent data to disappear. Talk to Mentor after not opening Stats: its facts can lag actual screen time.

**Impact/confidence:** Medium severity cache/read-model weakness; high confidence ordering. Detailed usage reconstruction accuracy is covered by the platform/usage audit.

**Fix:** An application-scoped ingestion repository coalesces concurrent requests and publishes a timestamp/status; transactionally replace the refreshed window. Define when data is refreshed and whether stale-but-known data is returned. Mentor should request the same read model instead of bypassing its freshness contract.

**Regression:** Concurrent empty-cache readers; failure after deletion; revoked Usage Access; app unused longer than the system's event retention; Mentor snapshot before opening Stats.

### D16 — P2: AI action receipts are persisted after effects, so process death defeats retry guards

**Evidence:** `Mentor.kt:302-315` uses a per-turn in-memory map keyed by raw JSON strings. Durable action receipts are inserted only after the entire tool loop (`:329-336`) or the caught error path (`:345-352`). Tool actions commit earlier. Parent creation dedup (`MentorTools.kt:330-337`) does not cover calendar events/food and is not a persisted command identity. Process death is not an exception that this catch block can persist.

**User sequence:** Mentor inserts a food entry/calendar event, app dies before saving its assistant receipt, then the user retries the same request. The model sees no receipt and can repeat the side effect. Exact same arguments in different JSON formatting do not share the in-memory key even within one turn.

**Impact/confidence:** Repeated side effects after retries/crashes; high confidence failure window. AI-specific matching/approval UX is covered elsewhere.

**Fix:** Persist tool command ID, canonical validated input, pending/completed status, and result; apply each local action transactionally with its receipt. External calendar operations need a traceable origin or reconciliable status. Replay the stored result for duplicate IDs; title dedup alone is not idempotency and can reject legitimate distinct tasks.

**Regression:** Crash after each tool effect before assistant reply; retry same command ID; equivalent JSON argument order; repeated food/event commands vs intentionally duplicated new requests.

## Migration integrity and data-loss risk

Observed registration covers **2→3→4→5→6→7→8→9→10→11→12** (`Db.kt:557-560`):

| Migration | Change | Data preservation assessment |
|---|---|---|
| 2→3 | Add sortOrder; backfill from createdAt (`:326-330`) | Preserves rows and establishes deterministic initial order |
| 3→4 | Create usage cache + unique package/start index (`:358-370`) | Additive |
| 4→5 | Create website sessions + start index (`:373-384`) | Additive |
| 5→6 | Add habits and food tables/index (`:387-402`) | Additive |
| 6→7 | Add goals and nullable task goal/due fields (`:405-416`) | Additive; no reference enforcement |
| 7→8 | Add nullable activity note/goal fields (`:418-423`) | Additive |
| 8→9 | Add chat/messages/digests (`:487-500`) | Additive |
| 9→10 | Add goal details (`:502-506`) | Additive |
| 10→11 | Add reminder fields (`:508-513`) | Additive |
| 11→12 | Add rewardCents default=0 and wishlist (`:515-524`) | Additive; old completed tasks receive no retrospective earnings |

Good: destructive fallback was deliberately removed (`Db.kt:561-565`), so missing migrations do not silently delete history. Current migration SQL contains no destructive table/drop rewrite. Instance creation uses volatile+synchronization (`:548-565`). Unique cache key and SQL overlap queries are sensible starting choices.

Verification: `/workspace/bibo-audit/check_data_contracts.py` executes the repository migration SQL from synthetic starting versions 2 through 11 and compares resulting column names/types/nullability/primary keys to the generated v12 fresh schema; preserves representative task/activity rows, checks sort-order/reward backfills, and runs SQLite integrity_check. All ten paths pass. Results: `/workspace/bibo-audit/data-contract-results.json`.

Limits: this is not Android Room identity validation and not real released historical database fixtures. The synthetic v2 schema is inferred from the oldest committed entity definitions with the pre-sortOrder fields. Defaults are excluded from shape comparison because Kotlin constructor defaults are not Room ColumnInfo default values; current migration adds SQL defaults while fresh schema does not declare them. No mismatch was asserted from that difference alone.

Remaining assurance gaps:

- No **1→2** migration exists. A device with schema v1 would fail to open v12; there is no evidence in this checkout that a v1 APK/database was released (oldest committed database was already v5). This is a **conditional legacy compatibility risk**, not proven current user damage.
- `@Database(exportSchema=false)` at `Db.kt:533` means versioned schema snapshots are not committed. There are no test source sets or Room migration test dependencies. Enable schema export and validate every supported released version with real fixtures/Room MigrationTestHelper before the next schema refactor.
- There is no user-visible app export/import or tested backup/restore/recovery flow in the reviewed source; README `:116-117` lists it as a future idea. Since nearly all user history is local and architecture remediation needs migrations, add a recoverable backup/export procedure before destructive data-model work. Android's default backup behavior is not an in-app recovery product or sufficient evidence of a tested WAL-safe restore.
- `AndroidManifest.xml:33-36` declares neither `allowBackup` nor `dataExtractionRules`/backup exclusions, so the app relies on the OS default policy. Decide and document which history/preferences may be backed up and whether API credentials must be excluded, then verify restore across install/update/device transfer. This is an unspecified policy and verification gap; it does not prove that private data or keys were leaked.
- Foreign-key repair must precede enabling constraints: identify orphan parents/goals and contradictory child memberships; do not drop those rows silently. Keep repair/export reports and preserve historic labels. Introduce new ledger/session tables additively and reconcile old ambiguous TODO blocks without pretending their fabricated time is measured work.

## A safer architecture without a full rewrite

Keep Kotlin/Compose/Room and a single app module initially. Introduce a small domain-command layer before reorganizing packages:

1. **Repositories own authoritative state.** Room owns tasks/goals/session/health/ledger records; DataStore or prefs owns preferences and credentials only. Expose Flow-based read models that combine the correct tables and external-service freshness/status. A running task is derived from ActiveSession instead of duplicated startedAt in two stores.
2. **A command service owns each invariant.** `CompleteTask`, `DeleteTask`, `MoveTaskToGoal`, `SetHabit`, `Start/FinishSession`, `RedeemTreat` have immutable targets, validated inputs, command IDs, transactions, and structured results. All UI, Mentor tools, widget/tile, and notifications call these services.
3. **UI owns drafts and navigation only.** ViewModels expose one state and pending/error results per screen. Reorder IDs, fetch current rows, and patch selected fields. Capture date/session IDs before suspending. Preserve drafts until persistence succeeds.
4. **Platform adapters own external effects.** Calendar provider, alarms, DND, accessibility, and notifications are interfaces used after local commits. An effect status/outbox and startup reconciliation handle failures; avoid transactions across network/system calls.
5. **Read models distinguish truth and projection.** A CalendarItem is a rendering projection of actual sessions, completion markers, planned events, and observed usage. A reward balance is ledger-derived. Daily review distinguishes unknown permission/data freshness from recorded zero and uses one window-clipping helper.
6. **Tests verify combinations/invariants.** Use fake Clock/ZoneId, in-memory Room, deterministic coroutine scheduling, fake platform adapters, and a few UI flows. Favor sequence tests (start→move→complete→undo→restart) and process-failure boundaries over checking each button in isolation.

Recommended sequence: patch D1; route completion/delete through one service; make session persistence atomic; patch habits/target captures; introduce FK-safe memberships and transaction boundaries; then ledger/fresh read models and migration recovery. Do not start a broad rewrite before the current behavior is characterized.

## Existing strengths to preserve

- Room-backed user records and Flow-based reads already give a good local/offline foundation; database instance construction is thread-safe, and individual generated DAO writes have Room's transaction protection. The missing unit of atomicity is the complete domain command.
- The explicit additive migration chain and removal of destructive fallback protect valuable history. Migration SQL smoke found no destructive operation or schema-shape discrepancy for inferred supported versions 2–11.
- Activity/usage queries use proper half-open interval overlap semantics; clipping bugs are in consumers rather than the database selection itself.
- A shared TimerController already connects screen, tile, widget, notification, and blocker. Preserve that single access point while giving its commands one durable transactional model.
- AppScope deliberately prevents routine tab navigation from canceling a database write (`AppScope.kt:7-17`). Retain application ownership for short commands, while distinguishing that from process durability and surfacing failures.
- Mentor's send/check-in mutex (`Mentor.kt:102,238,378`), duplicate-title/subtask guards, in-turn repeated-call guard, and normal-error action receipts address real duplication patterns. Extend these into persisted typed idempotent commands rather than remove the protections.
- Timer start-comment application checks that the original session's start still matches (`TimerController.kt:86-90`), preventing a late response from annotating a replacement session. Apply the same request/session targeting discipline to task stamps, parser writes, and day reviews.
- Previous Mentor memory is retained before a rewrite (`Mentor.kt:125-140`), and excessively short generated replacements are rejected (`:589-595`). This is useful recovery infrastructure to extend into versioned durable history.

## Additional architecture observations

- Query indexes are present for food day/chat day/website start and unique usage package+start, but not for task parent/goal or local activity interval windows. Queries such as `TaskActions.allOnce` for a subtree load the whole task history. This is a scale/performance risk rather than a measured present bug; profile realistic history and inspect query plans before indexing blindly.
- Completion SQL uses inclusive `BETWEEN` (`Db.kt:194-195`) while interval windows are half-open. A task completed exactly at midnight can appear in both days' Mentor history. A consistent `>= start AND < end` contract removes this boundary duplication.
- Archived goal queries hide goal labels from historic focus blocks (`GoalDao.allOnce`, `Mentor.kt:812`). Historical read models should resolve archived labels or use snapshots even when selection lists omit archived goals.
- Day review's coroutine captures the original `remember(epochDay)` review delegate (`HealthScreen.kt:261-264`). After navigation it updates an abandoned delegate, losing the result rather than displaying it on the new day. Shared `reviewing` still blocks generation on other dates; immutable request targets and date-keyed status should govern this work. Compiled capture evidence is in `health-usage-voice-probes/review-capture-bytecode.txt`.
- Chat-day digest and memory-note rewrite live in different stores (`Mentor.kt:587-595`), so interruption can mark a day summarized before its semantic memory update. Future memory storage should persist versioned notes/digests/receipt together and retain more than one recoverable generation.

## Reviewed coverage

Supplemental root runtime verification reproduced the task-row/session/completion inconsistency on Android 36, including the restarted session and synthetic fifteen-minute completion block. [TASK_TIMER_REPRO.md](/workspace/bibo-audit/TASK_TIMER_REPRO.md) documents screenshots and persisted state; it does not establish the separate concurrent-write or process-death windows.

Read every entity, DAO, migration and database builder in `data/Db.kt`; generated fresh Room schema and whole-row Todo adapter; `data/AppScope.kt`; all local writes found by an app-wide DAO/write search. Examined write/reader contracts in `ui/TodoScreen.kt`, `GoalProfileScreen.kt`, `TaskActions.kt`, `TreatsSheet.kt`, `HealthScreen.kt`, `CalendarScreen.kt`, `TimerScreen.kt`, `DayReview.kt`, `MentorTools.kt`, and `Mentor.kt`; persisted timer lifecycle and background service; task/goal reminder database access; usage ingest/cache; website write lifecycle; widget reads/actions; MainActivity startup recovery; manifest backup/config and Gradle test declarations; README/history for supported versions. Root/other agents own detailed visual UX, parser/calendaring semantics, Android platform behavior, and live emulator evidence.
