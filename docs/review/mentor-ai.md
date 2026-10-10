> Historical audit of commit `8e63d9d`, before the repairs. See [the implementation report](../IMPLEMENTATION.md) for current behavior and validation.

# Bibo audit — Mentor, AI actions, and day review

Read-only source review of the main checkout on 2026-10-10. No app source was edited; no real AI requests, secrets, device calendars, or emulator were used. `git status --short` was clean when inspected. No AGENTS.md was found in the workspace or its ancestors. Findings below distinguish deterministic source defects from platform- or model-dependent risks.

## What this part of the product does

Mentor is both a coach and a command interface over tasks, goals, the device calendar, meals, habits, memory, and the shared timer. A message is stored in Room; up to two older chat days may be summarized by OpenRouter; a system prompt is rebuilt from current database data; the model can execute up to ten rounds of tools; final prose and concatenated action receipts are stored in chat. Automatic hourly slots invoke a smaller, read-only model request, with a cadence that slows if the user does not engage. Starting any timer also requests a cloud-generated encouragement. DayReview is a separate on-device Gemini Nano recap with a local template fallback.

The useful product idea is one place to connect “what I intended,” “what I did,” and “why it matters.” The chief implementation problem is that the chat is another independent writer of domain state, with subtly different completion, identity, calendar, and error rules than the normal UI. Model instructions are also being used where the application needs enforceable invariants.

Severity: **P1** = serious data loss or broken core workflow; **P2** = substantial inconsistent behavior/edge-case failure; **P3** = smaller trust or usability issue. “High confidence” means the code path is explicit; it does not imply a device or external API reproduction was performed.

## Ranked findings

### AI-01 — Cancelling one recurring meeting can delete the whole series

**P1 · High confidence · overlaps calendar review.**

**Trigger:** A recurring standup appears next Friday. Ask Mentor to cancel Friday’s standup.

`findUpcomingEvent` queries calendar *instances* but returns their parent `EVENT_ID`, without occurrence identity or recurrence metadata. `deleteCalendarEvent` deletes that ID using `CalendarContract.Events.CONTENT_URI`. That deletes the underlying event/series rather than the selected occurrence. The same provider does explicitly refuse recurring edits, so deletion has a materially weaker contract. Neither an occurrence/series chooser nor undo is provided by Mentor. A fuzzy title can additionally choose the wrong event.

**Evidence:** [MentorTools.kt:512](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:512), [MentorTools.kt:547](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:547), [DeviceCalendarRepo.kt:33](/workspace/bibo/app/src/main/java/com/bibo/data/DeviceCalendarRepo.kt:33), [DeviceCalendarRepo.kt:82](/workspace/bibo/app/src/main/java/com/bibo/data/DeviceCalendarRepo.kt:82), [DeviceCalendarRepo.kt:111](/workspace/bibo/app/src/main/java/com/bibo/data/DeviceCalendarRepo.kt:111).

**Remedy:** Carry calendar ID, event ID, instance begin/end, all-day, and recurrence metadata through discovery. Refuse series deletion by default; support an explicit occurrence operation through the provider’s exception mechanism. Preview destructive operations with the exact date, calendar, and series scope.

**Regression scenarios:** Cancel one occurrence, cancel an explicitly selected series, two same-title meetings in separate calendars, an exception within a series, a read-only calendar, and deletion failure.

### AI-02 — The check-in’s 25-second timeout does not cancel its 90-second HTTP call

**P1 · High confidence for timeout mismatch; device ANR consequences require runtime validation.**

**Trigger:** A check-in fires on a slow connection, or there is a summarization backlog. Try chatting while that check-in is still running.

All cloud requests use synchronous `OkHttp Call.execute()` with a 90-second call timeout. The receiver wraps `Mentor.checkIn` in `withTimeoutOrNull(25_000)`, but coroutine cancellation cannot interrupt this blocking call without a cancellation bridge. Check-in also performs up to two summarization calls before its final request. The receiver’s `pending.finish()` and release of `sendLock` occur only after the blocking call returns. The promised 25-second bound is therefore unenforced; another chat can queue behind this work. `checkIn` catches `Throwable`, including cancellation, while compaction uses `runCatching`, further obscuring cancellation.

**Evidence:** [Mentor.kt:104](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:104), [Mentor.kt:200](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:200), [Mentor.kt:377](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:377), [Mentor.kt:400](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:400), [Mentor.kt:437](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:437), [MentorCheckin.kt:136](/workspace/bibo/app/src/main/java/com/bibo/data/MentorCheckin.kt:136).

**Remedy:** Use a cancellable coroutine adapter over OkHttp and cancel the actual call when the coroutine ends. Put scheduled network work in WorkManager with bounded request timeouts, retry/backoff, and work deduplication. Keep compaction out of the notification’s immediate path and prioritize an explicit user message over automatic work. Rethrow `CancellationException` before ordinary error handling.

**Regression scenarios:** HTTP server that stalls, offline retry, timeout while waiting for the mutex, cancellation during compaction, and chatting while check-in is underway. Assert actual network cancellation and completion of the receiver within its budget.

### AI-03 — Actions and receipts are not durably coupled; failures can leave partial changes

**P1 · High confidence.**

**Trigger:** Mentor creates a task with several steps; the process dies after the parent or some steps are inserted, or after a calendar write but before the final reply. Relaunch and retry the request.

The code comments say the action log solves crashes after tools, but the log is only an in-memory list until the final chat row (or ordinary exception handler) is inserted. It is appended *after* each tool returns. Parent creation, child creation, task completion plus activity creation, and other multi-write actions are separate DAO operations without a shared transaction. A process death leaves state with no durable receipt. `MentorTools.execute` catches all `Throwable` and turns partial failure or cancellation into a string; `send`’s explicit cancellation branch does not persist its collected receipts. Exact-title guards reduce some task duplicates but do not protect calendar or food writes, and do not repair half-created parents.

**Evidence:** [Mentor.kt:254](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:254), [Mentor.kt:308](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:308), [Mentor.kt:329](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:329), [Mentor.kt:338](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:338), [MentorTools.kt:281](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:281), [MentorTools.kt:354](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:354), [MentorTools.kt:732](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:732).

**Remedy:** Execute all UI and AI mutations through transactional domain commands. Store a durable command ID, validated input, state transition, entity IDs, and outcome in the same transaction as local changes. For calendar side effects, use an outbox with stable external IDs and recovery rules. Publish structured receipts from that journal rather than trusting generated prose or in-memory strings. Treat cancellation as cancellation.

**Regression scenarios:** Inject failure after each parent/child/block write; restart after an applied command but before reply; retry the same request; verify no duplicate calendar/meal write and an accurate visible record of partially or fully applied changes.

### AI-04 — Completing a task in Mentor does not complete its subtasks

**P1/P2 · High confidence · overlaps tasks review.**

**Trigger:** Create a goal task with three steps, then tell Mentor the task is finished.

Mentor updates only the matched `TodoTask` and writes one TODO activity. Tasks-tab completion and Goal-profile completion also update unfinished children. The same user action therefore produces a different final task tree depending on entry point. The completed parent moves out of active Tasks while unfinished children are no longer rendered beneath it; the goal’s count includes those unfinished children, so it appears incompletely achieved even though Mentor says the task is done.

**Evidence:** [MentorTools.kt:715](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:715), [TodoScreen.kt:191](/workspace/bibo/app/src/main/java/com/bibo/ui/TodoScreen.kt:191), [TodoScreen.kt:216](/workspace/bibo/app/src/main/java/com/bibo/ui/TodoScreen.kt:216), [GoalProfileScreen.kt:76](/workspace/bibo/app/src/main/java/com/bibo/ui/GoalProfileScreen.kt:76), [Db.kt:188](/workspace/bibo/app/src/main/java/com/bibo/data/Db.kt:188).

**Remedy:** One `CompleteTask` domain command must own parent/child completion, timer termination, activity logging, reminder cancellation, and reward behavior. Explicitly define how reopening a parent affects children and whether completing a step automatically completes its parent.

**Regression scenarios:** Complete/reopen the same tree through Tasks, Goal, and Mentor; run a child timer while completing its parent; verify identical task, session, reminder, and reward outcomes.

### AI-05 — Fuzzy name matching silently mutates whichever entity comes first

**P1 for deletion, P2 otherwise · High confidence.**

**Trigger:** Have “Prepare tax return,” “Prepare pitch,” and an older completed task with a similar name. Ask to delete or finish “Prepare.” Or have multiple meetings named “Meeting.”

Task edit/delete/complete, goal edit/archive, and calendar edit/delete accept titles rather than stable IDs. Exact matches are tried first, then `contains` in either direction chooses the first entity. There is no candidate list, ambiguity result, goal/date/calendar qualifier, or user confirmation. Task delete and edit also search completed tasks. Goals and tasks with common titles are normal product data, so arbitrary selection is not an exceptional database situation.

**Evidence:** [MentorTools.kt:317](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:317), [MentorTools.kt:425](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:425), [MentorTools.kt:451](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:451), [MentorTools.kt:512](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:512), [MentorTools.kt:715](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:715). The live prompt presents no task IDs: [Mentor.kt:640](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:640).

**Remedy:** Add read-only discovery/search tools returning stable IDs and distinguishing context. Mutation tools must accept an ID plus optional version, fail on unresolved ambiguity, and show reversible action cards/Undo. Require explicit scope for bulk deletion.

**Regression scenarios:** Duplicate exact titles under different goals, partial-title ambiguity, renamed task, completed/open duplicates, duplicate calendar titles, and a stale entity version.

### AI-06 — The mentor cannot move or cancel an event it just created in Bibo’s local calendar

**P2 · High confidence.**

**Trigger:** Without a writable phone calendar, ask Mentor to schedule a meeting, then ask it to move or cancel that meeting. Later connect the phone calendar.

`addEvent` silently falls back to a local `ActivityBlock(source="MANUAL")` when permission/insertion fails. Calendar edit and delete only query external device-calendar instances, so the local event is never found. The prompt’s day calendar and upcoming calendar also only use external instances; local future plans disappear from Mentor’s scheduling picture. Once permissions are granted, trying to reschedule can produce a second external event beside the existing local one rather than moving the original.

**Evidence:** [MentorTools.kt:395](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:395), [MentorTools.kt:409](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:409), [MentorTools.kt:512](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:512), [Mentor.kt:682](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:682), [Mentor.kt:816](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:816).

**Remedy:** Create a unified scheduling repository that discovers and mutates local and provider events by typed identity. Store planned events separately from logged activities. Surface the destination and connection/error state; avoid treating a provider failure as interchangeable success. Define explicit local-to-provider migration/deduplication.

**Regression scenarios:** No permission, no writable calendar, denied write despite read access, provider insert failure, local reschedule/delete, permissions granted later, and duplicate title across local/provider events.

### AI-07 — A Mentor focus request can fail after leaving behind a running timer/DND state

**P2 · High source confidence; Android background-start outcome is platform-dependent and was not device-reproduced.**

**Trigger:** Ask Mentor to start focus, then press Home before the model has answered. Alternatively ask it to block distractions without enabling accessibility/DND access.

The send continues in `AppScope`. `startFocus` stores running state and can enable DND before starting a foreground service. `TimerService.start` unconditionally uses `ContextCompat.startForegroundService`; when the application has become backgrounded, modern Android may reject the start. The exception becomes a generic tool failure, but the prior prefs/DND writes are not reverted. Separately the tool always announces “phone silenced” and “apps blocked” based on requested configuration, while `FocusDnd.enable` silently returns without permission and app blocking requires an enabled accessibility service. The normal Focus UI exposes those prerequisites; the AI path skips them.

**Evidence:** [ChatScreen.kt:87](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:87), [MentorTools.kt:619](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:619), [TimerController.kt:144](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:144), [TimerService.kt:95](/workspace/bibo/app/src/main/java/com/bibo/data/TimerService.kt:95), [FocusDnd.kt:21](/workspace/bibo/app/src/main/java/com/bibo/data/FocusDnd.kt:21), [WebTracking.kt:53](/workspace/bibo/app/src/main/java/com/bibo/data/WebTracking.kt:53).

**Remedy:** Make timer start a capability-checked state machine with rollback if the service cannot start. Ask the user to return to the app when system interaction is required. Return actual applied capabilities (“timer started; DND access needed”) rather than configuration promises.

**Regression scenarios:** Home/lock during AI response, denied notification permission, denied DND access, disabled accessibility, service-start exception, and switching from another running timer.

### AI-08 — Connection cannot be removed in the UI; automatic cloud use is not disclosed accurately

**P2 · High confidence.**

**Trigger:** Connect an OpenRouter key for a chat, then decide to stop Mentor requests or use Bibo locally. Hide Mentor notifications and stop opening chat.

The key dialog can save only values longer than ten characters; there is no Disconnect/Delete key action. A blank key can be stored programmatically, but the UI never permits it. `MainActivity` always schedules hourly 06:00–20:00 check-in slots; a key activates them, with no in-app cadence/pause/opt-out. Notification permission/channel disabling suppresses display, not the network request. All timer starts also use that key for encouragement. The setup card promises the key is “used only for your mentor chats,” while these automatic calls send memory, goals, and snapshots independently of an explicit chat. The snapshot includes calendar titles, habits including prayer, food labels, and app-use labels.

**Evidence:** [ChatScreen.kt:432](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:432), [ChatScreen.kt:457](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:457), [MainActivity.kt:61](/workspace/bibo/app/src/main/java/com/bibo/MainActivity.kt:61), [MentorCheckin.kt:43](/workspace/bibo/app/src/main/java/com/bibo/data/MentorCheckin.kt:43), [MentorCheckin.kt:129](/workspace/bibo/app/src/main/java/com/bibo/data/MentorCheckin.kt:129), [TimerController.kt:84](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:84), [Mentor.kt:806](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:806).

**Remedy:** A settings surface should separate Connect/Disconnect, check-in cadence and quiet hours, automatic timer encouragement, per-category data sharing, and history/memory deletion. Explain the external provider and exactly when data is sent before activating automatic use. Disconnect cancels pending work and removes the binding without requiring app-data erasure or an intentionally invalid key.

**Regression scenarios:** Disconnect with a queued job, disable notifications while check-ins are enabled, no-chat timer use, reduced/disabled cadence, and excluded data categories. Assert no network request after disabling the relevant feature.

### AI-09 — Recent conversation days can be permanently skipped by memory compaction

**P2 · High confidence.**

**Trigger:** Chat and log activity on at least three days, then return after several days without triggering compaction.

`compactPendingDays` processes at most two conversational days through the LLM. Its subsequent “data-only days” loop does not check whether a day has conversation; any remaining recent day with logged facts gets a `ChatDay` containing only those facts. `undigestedDays` excludes any date with a `ChatDay`, so those unprocessed chat days are never summarized on later calls. Their commitments/facts never enter semantic memory unless already captured by `remember`, and recent digests lose conversation context. Original rows remain searchable, so this is loss of summarized/retrievable working context, not deletion of raw history.

**Evidence:** [Mentor.kt:524](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:524), [Mentor.kt:536](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:536), [Db.kt:459](/workspace/bibo/app/src/main/java/com/bibo/data/Db.kt:459).

**Remedy:** Track digest type and completion state (`factsOnly`, `conversationPending`, `conversationCompacted`) separately. Only mark chat compaction complete after both digest and versioned memory updates succeed. The cheap facts pass must not discharge conversation work.

**Regression scenarios:** Three/seven conversational days each with/without activity, a failed compaction among them, retry after app restart, and aging beyond the configured summarization horizon.

### AI-10 — Duplicate-call protection prevents the documented duplicate-cleanup operation

**P2 · High confidence.**

**Trigger:** There are three identical tasks. Ask Mentor to remove the duplicates and keep one.

`delete_task`’s tool description says to call again with the same title to delete the next match, and its result repeats that instruction. But `send` caches the first execution by exact tool name and raw arguments. A second identical `delete_task` in the same turn returns the earlier result without deleting another row. `all_matching=true` removes every match, so it is not a safe substitute for keeping one. Conversely raw JSON whitespace/key-order changes can evade the cache, so it is also an inconsistent idempotency mechanism.

**Evidence:** [MentorTools.kt:111](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:111), [MentorTools.kt:436](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:436), [MentorTools.kt:446](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:446), [Mentor.kt:299](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:299).

**Remedy:** Discover specific task IDs, then delete those IDs with command-ID replay protection. If a dedicated deduplication command is justified, it should name the retained ID and removed IDs, preview the result, and execute transactionally. Do not use raw JSON equality as semantic action identity.

**Regression scenarios:** Three duplicates, retries of the same command ID, intentional repeated operations, and differently formatted argument JSON.

### AI-11 — Existing-task reminders and clearing fields are impossible through the tools

**P2 · High confidence.**

**Trigger:** Ask “remind me at 10 about the first step I already have,” “move that reminder to tomorrow,” “remove the due date,” or “unfile this from its goal.”

Only `create_task` has `reminder`/`reminder_note`; its duplicate guard refuses creation when the task already exists. `edit_task` has no reminder fields or reminder cancellation operation. This conflicts with the system instruction to set a concrete reminder on an existing first step. Optional setters use `str(...)` and `day(...)` followed by `?: existing`; blank/null cannot mean “clear.” Therefore due dates, goal membership, goal target dates, and goal details cannot be cleared through Mentor. Invalid date strings are silently treated as no change or no date, yet the receipt still says “Updated” or “Created.” A past reminder is persisted and announced as set even though `TaskReminders.schedule` simply returns.

**Evidence:** [Mentor.kt:762](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:762), [MentorTools.kt:63](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:63), [MentorTools.kt:124](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:124), [MentorTools.kt:309](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:309), [MentorTools.kt:467](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:467), [MentorTools.kt:489](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:489), [TaskReminders.kt:27](/workspace/bibo/app/src/main/java/com/bibo/data/TaskReminders.kt:27).

**Remedy:** Use validated patch semantics that distinguish absent, set, and clear, and include schedule/update/cancel reminder commands. Reject invalid/past input with a typed reason rather than silently omitting it. Return scheduled time only after the alarm operation has succeeded.

**Regression scenarios:** Set/move/clear an existing reminder, clear a due date/goal/target, malformed dates, DST gaps, and an already-past reminder time.

### AI-12 — Moving a task to another goal leaves its steps in the old goal

**P2 · High confidence · tasks reviewer also covers this.**

**Trigger:** Ask Mentor to move a parent with steps from goal A to goal B, then open either goal profile.

`editTask` updates only the matched row’s `goalId`. Children retain A. Goal profiles load only rows with that goal ID and render children grouped under locally loaded parents. A therefore has children with no visible parent and B has a parent with no visible children. Steps appear to vanish from both profiles while the all-tasks page still has them. Goal counts also diverge.

**Evidence:** [MentorTools.kt:451](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:451), [MentorTools.kt:467](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:467), [GoalProfileScreen.kt:68](/workspace/bibo/app/src/main/java/com/bibo/ui/GoalProfileScreen.kt:68), [Db.kt:206](/workspace/bibo/app/src/main/java/com/bibo/data/Db.kt:206).

**Remedy:** Make parent goal ownership authoritative, or move the entire tree in one transaction. Avoid storing a child’s derived goal separately unless exceptions are an intentional product feature.

**Regression scenarios:** Move/unfile a tree through each interface and verify both profiles, goal counts, timer goal attribution, and completion.

### AI-13 — “Complete current picture” is falsely asserted over truncated and incomplete data

**P2 · High confidence.**

**Trigger:** Have more than 25 open parent tasks or eight subtasks, ask about a later one, or plan around a local Bibo event. Complete a task after timing it from Tasks and ask what work was done.

The prompt tells the model TODAY/RIGHT NOW/NEXT 7 DAYS are the user’s “complete current picture” and calls OPEN TASKS the live existing list. Open parents are truncated to 25 with no truncation notice; child titles are truncated to eight. Today’s calendar truncates to eight external events and upcoming calendar to fifteen, without including local planned blocks. Logged task timers use `source="TODO"` but Mentor and DayReview count only FOCUS/TIMER as focus. Search tools search chat/digests/memory, not current tasks/events, so the model cannot discover an omitted task directly. The hard duplicate guard catches identical open titles, but reworded duplicates and poor planning remain possible.

**Evidence:** [Mentor.kt:640](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:640), [Mentor.kt:660](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:660), [Mentor.kt:687](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:687), [Mentor.kt:744](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:744), [Mentor.kt:818](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:818), [Mentor.kt:833](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:833), [TimerController.kt:209](/workspace/bibo/app/src/main/java/com/bibo/data/TimerController.kt:209), [MentorTools.kt:651](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:651).

**Remedy:** Clearly label snapshot coverage/counts/caps, show stable entity IDs, and provide paged domain discovery tools. Use a shared analytics definition of actual measured work across entry points. Never instruct the model that a partial snapshot is complete.

**Regression scenarios:** 26/100 tasks, nine steps, many simultaneous events, Bibo-only plans, timed Tasks work, and an omitted task renamed by Mentor.

### AI-14 — Cross-midnight focus duration is counted in full on both days

**P2 · High confidence · overlaps stats/timer review.**

**Trigger:** Focus from 23:30 until 00:30 and generate both days’ recaps or ask Mentor about each day.

The DAO returns all blocks overlapping a day. Mentor and DayReview sum `endMillis - startMillis` without clipping to the day boundary. The one-hour session is consequently reported as one hour for each day rather than half an hour. Mentor clips usage sessions correctly in the same file, showing the intended interval operation is already understood elsewhere. Per-session integer-minute truncation can additionally undercount many short sessions.

**Evidence:** [Db.kt:52](/workspace/bibo/app/src/main/java/com/bibo/data/Db.kt:52), [Mentor.kt:833](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:833), [Mentor.kt:838](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:838), [Mentor.kt:863](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:863), [DayReview.kt:30](/workspace/bibo/app/src/main/java/com/bibo/ui/DayReview.kt:30).

**Remedy:** Reuse an interval-intersection aggregator (`max(0, min(blockEnd, windowEnd)-max(blockStart, windowStart))`), sum milliseconds first, then round once for presentation. Base all summaries, charts, and goal accounting on it.

**Regression scenarios:** Midnight crossover, multi-day session, DST day, sub-minute sessions, and comparison across recap/Mentor/stats.

### AI-15 — Saving memory can race with compaction and silently lose the user’s corrections

**P2 · High confidence for unsynchronized writes; the precise overwritten content depends on timing/model output.**

**Trigger:** While Mentor is summarizing or thinking, edit “What the mentor remembers” to correct an important fact, then wait for the model response.

`sendLock` serializes sends and check-ins, but the UI’s `saveMemory` does not participate in that lock or use a revision. The compaction prompt reads a memory snapshot before the request; its later result replaces whatever memory exists at completion, including an intervening user edit. Model `edit_memory` also rewrites blindly. `previousMemory` records one prior version but has no caller/UI recovery affordance. The dialog says memory is tidied “each night,” although no nightly compaction job exists: it runs lazily on later send/check-in and may be skipped.

**Evidence:** [Mentor.kt:125](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:125), [Mentor.kt:136](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:136), [Mentor.kt:140](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:140), [Mentor.kt:564](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:564), [Mentor.kt:591](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:591), [ChatScreen.kt:238](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:238), [ChatScreen.kt:303](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:303), [MentorTools.kt:709](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:709).

**Remedy:** Store versioned memory with provenance and authoritative user corrections. Apply generated changes using compare-and-set or an explicit merge; distinguish facts from coach inferences. Show version history/Restore and an accurate “last updated” status. User-deleted facts must not be recreated from earlier transcript without an explicit policy.

**Regression scenarios:** User edit during compaction, simultaneous remember/edit, bad generation, intentional deletion, restoring an older version, and no network/key after a conversation day.

### AI-16 — Chat loses pending-operation state on navigation and has no retry/cancel contract

**P2 · High confidence.**

**Trigger:** Send a message, switch tabs, and return before the answer. Or accidentally submit a request and try to stop it.

The request survives navigation, but `sending` is composable-local `remember` state. On return it resets to false, so the typing state disappears and another send can be queued behind the invisible first job. The input is cleared before success, there is no per-turn pending/failure state, and failures are only ordinary chat text without a retry control. There is no cancel control while tools may still execute. A ten-iteration tool loop can exhaust and return a successful “…” even though no final model answer was received. Ten 90-second calls plus compaction also have no overall interactive deadline.

**Evidence:** [ChatScreen.kt:76](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:76), [ChatScreen.kt:82](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:82), [Mentor.kt:272](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:272), [Mentor.kt:327](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:327).

**Remedy:** Persist/use a lifecycle-aware turn state (`queued`, `generating`, `applying`, `partiallyApplied`, `done`, `failed`, `cancelled`) keyed by turn ID. Render progress on reentry, give safe cancellation and retry with durable idempotency, and report loop/time-budget exhaustion explicitly. Do not allow the pending job to retain an Activity context unnecessarily.

**Regression scenarios:** Tab switch/rotation/Home mid-turn, app restart, queued second send, cancellation after a tool, network error after tool application, and tool-loop exhaustion.

### AI-17 — Key handling contradicts the “only on this phone” guarantee

**P2/P3 · High confidence about configuration; backup transfer occurs only on a device with backups enabled.**

The API key is stored in plain app-private SharedPreferences. The application manifest does not declare `allowBackup` or backup/data-extraction exclusions, so the platform default allows backing up app data; no rule excludes the `mentor` preference file or private chat/health data. The key dialog also places the full existing key in an ordinary unmasked `OutlinedTextField`. No direct remote key leak was found, but “stored only on this phone” is not supported by this configuration.

**Evidence:** [Mentor.kt:112](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:112), [Mentor.kt:118](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:118), [AndroidManifest.xml:33](/workspace/bibo/app/src/main/AndroidManifest.xml:33), [ChatScreen.kt:228](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:228), [ChatScreen.kt:457](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:457).

**Remedy:** Use a platform-backed secure key binding and explicit backup/transfer exclusions for credentials. Decide consciously how private user data is backed up. Mask the input by default with a deliberate reveal option. State the actual retention/backup behavior.

**Regression scenarios:** Backup/restore and device transfer configuration, new/replaced/deleted key, reveal behavior, and disconnect persistence.

### AI-18 — Recaps treat unrecorded habits as failure and can remain stale

**P2/P3 · High confidence.**

**Trigger:** Log one habit, leave the rest unrecorded, and generate a recap; then add another habit or meal.

Once a `HabitDay` exists, all false booleans are labeled “Missed” in DayReview or ✗ in Mentor; the data model has no distinction between “not done,” “not logged,” and “not applicable.” The recap can therefore scold a user for missing activity they simply did not record. DayReview’s displayed text is a cached value changed only on Generate; later food/habit changes do not invalidate it. If the health date changes while a generation is pending, shared `reviewing` blocks generation on the new day while the late result updates the abandoned old review state and is lost. Compiled capture inspection by the health reviewer confirms that it does not display the old result on the new day. The core stale-after-new-log defect is explicit.

**Evidence:** [DayReview.kt:41](/workspace/bibo/app/src/main/java/com/bibo/ui/DayReview.kt:41), [Mentor.kt:848](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:848), [Db.kt:259](/workspace/bibo/app/src/main/java/com/bibo/data/Db.kt:259), [HealthScreen.kt:124](/workspace/bibo/app/src/main/java/com/bibo/ui/HealthScreen.kt:124), [HealthScreen.kt:258](/workspace/bibo/app/src/main/java/com/bibo/ui/HealthScreen.kt:258).

**Remedy:** Model habit states explicitly and let the user choose which habits apply. Distinguish “not logged” from “missed” in coaching. Associate each recap with date, facts revision, and generated-at timestamp; invalidate or label stale recaps and ignore results for an outdated date/revision.

**Regression scenarios:** One logged habit with others unknown, no applicable prayer/workout, new logs after recap, switching dates during generation, and cancellation/retry.

## Concrete architecture recommendations

1. **Unify domain commands before adding more AI behaviors.** Tasks, goals, timers, calendar, reminders, food, and rewards need shared validated use cases invoked identically by UI/voice/AI/widget. Room should enforce local transactions and relevant identity/relationship constraints. This removes the need for the model to preserve application invariants.
2. **Give planning and tracking separate types.** A planned appointment, a task deadline, a completion marker, and a measured session are distinct concepts. Bibo currently stores several of these in ActivityBlock and then filters by string source. Keep measured duration and estimated/completion-marker duration separate so an AI recap cannot turn a checkbox into an invented fifteen minutes of focus.
3. **Make AI output a proposed command, with typed outcomes.** Discovery tools expose entity IDs and versions. Application validators resolve scope and capabilities. Safe additive operations can apply automatically; destructive or ambiguous operations need a precise preview/Undo or selection. Receipts identify the changed entities and reflect applied state, not the request’s wish or model’s prose.
4. **Separate interactive generation, durable actions, and scheduled work.** A lifecycle-aware conversation repository owns pending turns; a command journal owns persistence and replay; cancellable network requests own deadlines; WorkManager owns scheduled retries and compaction. An in-process global CoroutineScope helps navigation but does not make work durable across process death.
5. **Define one facts/analytics pipeline.** Use a shared interval clipper, source definitions, snapshot revision, and explicit missing-data metadata. Provide the same numbers to charts, DayReview, goal profiles, and Mentor. Include domain search with pagination instead of claiming that a truncated prompt knows everything.
6. **Make memory inspectable and correctable.** Maintain facts with source, confidence, date, and version; keep coach hypotheses separate; retain user corrections and deletion intent. Compaction should not silently overwrite an authoritative correction. Give history, restore, and selective deletion controls.

## Product and UX recommendations

- Treat the Mentor as an optional layer over a reliable local product. Basic logging/task/timer use must not require a key or network, and connection must be removable without losing logs.
- Start check-ins with a user-selected rhythm and quiet hours. Let the user opt into accountability tone; no data does not mean avoidance. The current 15 hourly slots, request-on-any-timer, and strong “push them” persona need explicit controls and accurate data-sharing explanations.
- Replace concatenated ⚙️ receipt text with action cards showing entity/date/destination, Open, Undo, and partial failure/retry. Preserve a visible pending turn across tabs.
- Show which facts a suggestion used and what is missing. “I see 25 of 40 tasks,” “calendar access unavailable,” and “workout not logged” improve trust more than an emphatic persona built on incomplete data.
- Keep nutrition estimates labeled estimates, and distinguish “I ate this yesterday” from today’s intake. Current `log_food` and `set_habit` can only write today; they need explicit dates or must reject retrospective statements rather than changing today silently.
- Align capabilities with promises: existing reminders, recurrence, all-day/cross-midnight calendar events, goal unfiling, and correction of prior food logs need defined actions. A model should not be prompted to perform capabilities the tool surface does not offer.

## Additional evidence-backed architecture risks and smaller issues

- **Calendar dates/times:** `addEvent` computes default `start.plusHours(1)` as a LocalTime and attaches it to the same date. Starting at 23:30 produces an end of 00:30 on the *same* date (because even its fallback also wraps), a negative interval. Explicit overnight end times are also replaced with a wrapped one-hour default. [MentorTools.kt:402](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:402). Use zoned date-time duration/end-date handling; include 23:30→00:30 and DST in calendar regression coverage.
- **Untrusted content elevated to instruction context:** Device-calendar titles, task/food labels, reflection text, and memory are interpolated directly into the same system message as operating rules, then the model is allowed to delete/write state without separate enforcement ([Mentor.kt:611](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:611), [Mentor.kt:724](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:724), [Mentor.kt:816](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:816)). Calendar titles can originate outside the user’s direct control. This is an evidence-backed prompt-injection boundary weakness, **not an exploit reproduced in this review**. Put records in structured data messages, explicitly label them as data, and enforce mutation authority in code independently of any prompt.
- **Exported receiver can be externally triggered:** MentorCheckinReceiver is `exported=true` for boot handling, but every non-BOOT action enters the paid check-in path; there is no action equality check or caller permission. A different installed app can explicitly target that receiver and induce a check-in when a key and cadence gates permit. TaskReminderReceiver likewise accepts task IDs on arbitrary non-BOOT actions and posts their titles as notifications. This is a concrete externally reachable code path, **not a second-APK reproduction or unrestricted spam claim**. Separate the boot receiver from private alarm receivers, restrict exports/permissions, and whitelist actions. [AndroidManifest.xml:57](/workspace/bibo/app/src/main/AndroidManifest.xml:57), [MentorCheckin.kt:124](/workspace/bibo/app/src/main/java/com/bibo/data/MentorCheckin.kt:124), [TaskReminders.kt:88](/workspace/bibo/app/src/main/java/com/bibo/data/TaskReminders.kt:88).
- **Memory/historical context growth:** `MAX_RAW_MESSAGES` bounds message count but not text size; editable persona/memory and day transcripts have no application-enforced byte/token limit. `searchHistory` loads the entire conversation and summaries before filtering; ChatScreen observes all messages and builds all row descriptors eagerly. These are maintenance/scaling issues, not measured current performance failures. Add paged queries and actual input/context budgets. [Mentor.kt:83](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:83), [Mentor.kt:549](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:549), [MentorTools.kt:660](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:660), [ChatScreen.kt:70](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:70).
- **Restore/archive semantics differ by interface:** Mentor “delete_goal” archives and retains tasks’ goalIds, despite its receipt saying tasks lose the folder; the UI clears goal links and physically deletes the goal. Decide whether the product operation is Archive, Delete, or Unfile and expose them distinctly. [MentorTools.kt:502](/workspace/bibo/app/src/main/java/com/bibo/ui/MentorTools.kt:502), [TodoScreen.kt:551](/workspace/bibo/app/src/main/java/com/bibo/ui/TodoScreen.kt:551).
- **Date label staleness:** ChatScreen captures today once with `remember`; a screen left open over midnight continues showing yesterday as Today and uses the wrong date for suggestion visibility. [ChatScreen.kt:79](/workspace/bibo/app/src/main/java/com/bibo/ui/ChatScreen.kt:79).
- **Documentation/provider drift:** README still describes Claude/Anthropic and its SDK while implementation uses OpenRouter/GLM and OkHttp. This matters for setup, user expectations about where personal logs go, and operating-cost assumptions. [README.md:34](/workspace/bibo/README.md:34), [README.md:63](/workspace/bibo/README.md:63), [Mentor.kt:54](/workspace/bibo/app/src/main/java/com/bibo/ui/Mentor.kt:54).
- **Voice fallback readiness:** `SmartVoiceParser.entityParse` blocks on `Tasks.await(downloadModelIfNeeded())` without a timeout; the regex fallback cannot run until it returns. Cancellation is also swallowed by several catch-Throwable/runCatching paths. This was sent to the voice reviewer for ownership. [SmartVoiceParser.kt:94](/workspace/bibo/app/src/main/java/com/bibo/ui/SmartVoiceParser.kt:94).

## Coverage and limits

Reviewed all of Mentor.kt, MentorTools.kt, ChatScreen.kt, DayReview.kt, MentorCheckin.kt, and their relevant Room, TimerController, TimerService, TaskReminders, FocusDnd, DeviceCalendarRepo, Rewards, GoalProfileScreen, TodoScreen, HealthScreen, SmartVoiceParser, manifest, and README contracts. Followed cross-feature writes and snapshot provenance; checked that error rows are excluded by DAO queries before reaching the model (so there is no error-row/history defect despite the generic role mapping). Verified no test source sets are present.

No AI model, provider account, real key, calendar, live personal data, or emulator was exercised. Model selection/reliability, OpenRouter routing/retention, notification OEM behavior, Android service-start/receiver failures, Gemini availability/download completion, and actual prompt-injection exploitability remain runtime/external validation areas. Confidence and consequences above are scoped accordingly. Suggested regression scenarios are future validation work, not tests claimed to have run.
