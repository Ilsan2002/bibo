> Historical audit of commit `8e63d9d`, before the repairs. See [the implementation report](../IMPLEMENTATION.md) for current behavior and validation.

# Bibo repair and redesign plan

Companion to [AUDIT.md](AUDIT.md). Proposed implementation only; no application source changes were made by the audit.

Organize work by one user workflow at a time. Keep changes small enough to review, carry characterization tests for actual bugs, and retain an installable app after each stage. Priorities express order and risk, not estimated calendar dates.

## Stage 1 — Stop destructive and immediate failures

Deliverables:

1. Move WebTracking's Context-dependent initialization into its attached lifecycle; add a service-readiness result. Correct resource-ID reporting and verify supported browser lookup.
2. Protect recurring deletion in both Calendar and Mentor. Until occurrence semantics exist, route recurring changes to the calendar app or require explicit series scope. Preserve original event/occurrence identity through read and action paths.
3. Replace Tasks' cached entity list with ordered IDs resolved against current records. Introduce field-specific title/order updates; persist reordering atomically under a defined scope.
4. Preserve complete original calendar intervals through editing. Provide end date/all-day shape, validate final instants, and avoid automatic time replacement after manual correction.
5. Capture target day/session/entity IDs before asynchronous work. Patch habits atomically by named field and verify that unrelated changes survive.
6. Correct obvious false capability/success messages. Show missing blocking, DND and notification readiness from the feature that needs them.
7. Contain reward overspending now: read the current item, check the current balance and conditionally redeem inside one transaction, with an in-flight UI state. The larger ledger/history redesign remains in Stage 3.

Acceptance gate:

- Accessibility enable/restart does not crash; blocker readiness is visible.
- One recurring occurrence cannot silently delete the series from any surface.
- Rename → reorder → completion retains edited values; task Play/Stop shows current state.
- A 49-hour title edit preserves 49 hours; all-day dates survive; spring-gap input cannot save a negative interval.
- Concurrent distinct habit changes survive; delayed logging stays on its original day.
- No success message claims a capability that was not applied.
- Two purchases against a balance that covers one commit only one redemption.

Relevant findings: TG01, CAL-01/02/03/04/06/08, HV01–04, TF-01/02/08, AI-01/05. Calendar gesture identity and same-anchor Today navigation can be repaired alongside this small slice.

## Stage 2 — Establish one task and session workflow

Deliverables:

1. Add a shared task command service. Route Tasks, goal profiles and Mentor completion/deletion/move through it before further feature-specific changes.
2. Decide parent/child semantics, goal inheritance, archive/delete policy and order scope. Record cascade provenance for safe Undo rather than recreating arbitrary stale row snapshots.
3. Add durable Room session/segment records with stable IDs and links to task/goal. Derive running task presentation from the active session. Serialize competing session commands.
4. Transactionally finalize the ended session and its history once. Persist local results before reconciling service, notification, widget, DND and blocker effects.
5. Give secondary controls explicit task/session parameters. Repeated Stop is idempotent; switching sessions has one policy across UI, widget, tile and Mentor.
6. Stop immediately before reflection; attach notes to the ended session. Make notes/goal/source visible when reviewing the record.
7. Introduce ViewModels for Tasks and Focus with immutable state, pending results and preserved drafts; typed entity navigation for task/session actions.

Recommended initial product defaults:

- One active session. A conflicting Start returns a switch-required result or uses an explicitly configured switch policy.
- Parent completion makes remaining-step consequences explicit. Undo reverses only changes caused by that completion.
- Goal ownership derives from the root task or is moved transactionally with its subtree.
- Archive preserves historical identity; permanent deletion is a distinct action.
- Completion is a milestone; measured work stays in sessions. Estimates, if offered, are explicitly labelled and excluded from measured totals.

Acceptance gate:

- Identical fixtures completed via Tasks/Goal/Mentor yield the same tree, history, session and reminder intent.
- A → B → A with delayed writes cannot clear the newest session; two Stops save one result.
- Completing/deleting a parent with a running child leaves no active orphan session.
- Reflection for A never changes B; notification/widget actions resolve their displayed identities.
- Process termination at persistence boundaries recovers a session once; platform-effect failure does not erase committed history.
- Delete/Undo and move preserve renderable relationships; unsupported hierarchy/cycles are rejected.

Relevant findings: TG02/03/06/07/09/11/15, TF-03/04/05/06/07/11, D2–5/D8/D11–13, AI-03/04/12.

## Stage 3 — Make records, rewards, and analytics reliable

Deliverables:

1. Add completion records separate from sessions and local planned events. Keep calendar items as projections with stable source identity and provenance.
2. Add an immutable reward ledger and atomic redemption. Define earning/reversal, budget period, reset/carryover and currency; provide a visible ledger explanation.
3. Centralize Clock/ZoneProvider, day/week boundaries, half-open interval arithmetic and rounding. Use shared measured-work, progress and coverage read models across Timer, Goals, Daily, Stats, widget and Mentor.
4. Give usage ingestion one serialized/coalesced owner. Replace windows transactionally, advance the watermark on success, preserve accurate raw intervals, and separate timeline gap merging from measurement.
5. Record collection coverage/freshness and unknown states. Handle DST bin partitioning; choose union versus sum for overlapping app use.
6. Make health save atomic with stable operation IDs, explicit habit unknown/done/skipped state, validated nutrition estimates and captured dates. Associate review responses with day and facts revision.
7. Add provider observers and typed calendar results. Make local/provider destinations explicit; use recoverable operation status for external mutations.

Acceptance gate:

- Total daily work partitions a session across midnight/DST correctly and sums to the reporting-window total.
- Work, break, completion and estimate have distinct inclusion rules; untimed checkmarks never create measured effort.
- Concurrent purchases cannot overspend; task housekeeping does not silently rewrite credits/debits.
- Stats headline, trend and heatmap use one committed snapshot and metric; missing coverage is not shown as measured zero.
- Cancel/fail ingestion between writes leaves previous valid data intact.
- All entries in one health log save together; reviews do not switch dates or silently use an obsolete facts revision.
- Failed provider operations keep drafts, state the cause/destination and support appropriate recovery.

Relevant findings: TG04/05/08/10/16, HV05/11–16, TF-09/10, CAL-09/10/12, D9–15, AI-06/13/14/18.

## Stage 4 — Make everyday use understandable and recoverable

Deliverables:

1. Add task details for goal/due/reminder/reward/steps; goal creation in empty states; visible filters; accessible completed steps and archive/restore.
2. Add a persistent active-session bar and exact task/goal/session deep links. Preserve drafts through tabs, rotation, fold/unfold and process recreation where supported.
3. Provide a capability/settings surface with configured, granted, applied, healthy, unsupported and failed states. Separate app blocking from website recording.
4. Fix Today/pager/zoom ownership. Provide planned-versus-actual source visibility, complete interval labels, source/destination status and a compact agenda option.
5. Make wishes editable/removable independently of affordability. Add local meal editing, exact/manual nutrition, delete Undo and applicable-habit/target settings.
6. Give reminders unique notification identities, local editing/cancel/snooze, bounded receiver ownership and appropriate background scheduling. Reconcile time/timezone changes and notification permission.
7. Introduce a speech state machine with session tokens, explicit cancel/error/retry and tap controls. Parse into an editable draft showing target date, range, foods/habits and estimates. Bound engine latency and use a real local recognizer where that guarantee is offered.
8. Correct semantic actions, state descriptions, contrast, chart text alternatives, keyboard-safe scrollable editors and Back behavior.

Acceptance gate:

- A user can create a goal first, manage an existing task/reminder and correct a meal without AI credentials.
- A date/goal filter is always apparent and clearable; completed children remain reachable.
- Navigation/layout changes retain meaningful drafts and pending operations; taps open exact entities.
- Cancellation never submits speech; an errored partial is not silently saved.
- Denial/revocation/service failure produces an honest status and recovery route.
- TalkBack and large-text/compact-screen users can complete the principal plan → focus → finish → review loop.

Relevant findings: TG12–17, CAL-05/07/08/11 plus UX gaps, HV08–10/15–17, TF-02/08 plus UX gaps.

## Stage 5 — Put Mentor on dependable product contracts

Deliverables:

1. Thin tool adapters invoke the same domain commands as the UI. Discovery returns stable IDs, qualifiers and coverage; mutations reject unresolved ambiguity and preserve explicit set/clear semantics.
2. Persist conversation turns and command results with statuses for queued/generating/applying/partially applied/done/failed/cancelled. Retry reuses a durable command ID; action cards show actual changes, target/destination and Undo where supported.
3. Bridge OkHttp cancellation to the actual call. Bound whole-turn/tool budgets and give explicit exhaustion/error results. Move scheduled network work and compaction to owned, deduplicated jobs with retry/backoff.
4. Use facts-only/conversation-pending/compacted states for day digests; version memory updates and preserve authoritative user corrections. Offer version history/restore/selective deletion.
5. Add existing-reminder editing/cancellation, local-calendar discovery and correct dated health actions. Label prompt truncation/coverage and provide paged domain search.
6. Add Connect/Disconnect, check-in rhythm/quiet hours, timer encouragement and data-sharing controls. Disconnect cancels pending automatic work. Align README and UI disclosure with actual providers and scheduling.
7. Separate exported boot handling from non-exported internal alarm receivers and validate allowed actions. Enforce domain authority independently of model instructions or external record text. Mask/exclude credentials from backup transfer under an explicit policy.

Acceptance gate:

- UI and Mentor issue equivalent actions through the same implementation.
- Ambiguous names cause selection rather than arbitrary deletion; occurrence scope is exact.
- Effects and receipts survive a restart; retries cannot duplicate local actions; ambiguous provider results have a visible recovery state.
- Pending turns remain visible across navigation; Cancel stops cancellable work and reports any already-applied effects accurately.
- Stalled HTTP actually cancels at the deadline; user chat is not indefinitely blocked by automatic compaction.
- Three or more unprocessed conversation days remain eligible until their conversation summaries are complete; user memory corrections survive compaction.
- Disabling/disconnecting the relevant automatic feature produces no new network request from it.

Relevant findings: AI-01–18 and additional trust boundaries, D16, HV10 and voice adapter contracts.

## Migration and release discipline across all stages

Before schema-changing work, add exported Room schemas and validate a recoverable backup/export path. A verified developer/offline backup and restore procedure is sufficient initially; polished in-app export/import can follow separately. Use real fixtures for released supported versions where available; otherwise build documented representative fixtures and run Android Room migration validation, recording the historical-coverage gap. Missing historic databases should not indefinitely block additive repairs. Keep migrations additive where possible. Current synthetic SQL migration checks pass for 2–11→12, but do not replace Android Room verification.

Migration safeguards:

- Preserve orphan/contradictory legacy records in a repair report before adding constraints; do not discard invisible rows to make validation pass.
- Import legacy active timer prefs into a durable session once, with explicit reconciliation of stale `startedAt` flags and interrupted runtime.
- Preserve existing blocks with a legacy provenance label. Old TODO blocks do not reveal whether time was measured or fabricated; do not silently relabel them all as measured work.
- Preserve legacy habit provenance. Existing false cannot distinguish unlogged from deliberately skipped; migrate it under an explicit unknown/legacy policy rather than asserting confirmed historical failure.
- Decide an explicit opening-balance strategy for rewards. Current mutable rows cannot reconstruct a trustworthy immutable earning history without an agreed snapshot policy.
- Preserve historical goal/task labels/links through archive and retain provider IDs during calendar refactoring.
- Validate credential backup exclusions separately from the user's chosen data backup/transfer policy.

Add CI that builds, runs the targeted domain/repository tests and Room migration checks, and runs lint. Triage current lint failures instead of creating a broad baseline that hides new issues. Use physical-device release checks for blocker/browser/recognizer/OEM/DND behavior that an emulator cannot establish.

## Tests worth building

| Layer | Focus |
|---|---|
| Pure domain | Temporal boundaries/DST, interval partitions, progress policies, parser phrase tables, reward policies |
| Room/repositories | Command transactions, relationship validation, conditional patches, concurrent redemption, ingestion replacement, migration identity |
| Controlled concurrency | A→B→A timer interleavings, multiple habit writers, stop/completion races, late review/parse results, retry identity |
| UI/instrumentation | Rename→drag→complete, stale widget controls, Today after swipe, manual override after parsing, draft restoration, permission round trips, exact deep links |
| Failure/recovery | Process death at durable boundaries, provider ambiguity, service-start failure, stalled/cancelled HTTP, receiver/job lifetime |
| Real device | Supported browsers, screen off/lock, DND API versions, notification denial, Nano/recognizer availability, TalkBack, fold and large text |

Use invariant checks such as one active session, no live orphan task, an ended session finalized once, no unsupported overspend, immutable log target day, valid timed intervals, and identical command effects across entry points. Each repaired defect should bring the sequence that demonstrated it, without adding tests that merely duplicate a low-impact UI implementation.

## First implementation slice

The best initial slice is WebTracking lifecycle, recurring-delete protection, and current task-row/field-update correctness. It addresses an installed-app crash, destructive external writes, and the most direct cause of everyday combination bugs. Follow immediately with shared task completion and durable session finalization; subsequent work can then build on a consistent core.
