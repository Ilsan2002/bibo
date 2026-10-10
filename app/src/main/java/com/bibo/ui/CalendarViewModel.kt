package com.bibo.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import com.bibo.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

data class CalendarEditRequest(
    val sheetTitle: String,
    val title: String,
    val start: LocalTime?,
    val end: LocalTime?,
    val date: LocalDate,
    val source: String,
    val endDate: LocalDate? = null,
    val allDay: Boolean = false,
    val zone: ZoneId = ZoneId.systemDefault(),
    val startOffset: ZoneOffset? = null,
    val endOffset: ZoneOffset? = null,
    val editItem: CalendarItem? = null,
    val warning: String? = null,
    val requiresTimeCorrection: Boolean = false,
    val commandId: String = UUID.randomUUID().toString(),
    val destinationLocked: Boolean = false,
    val lockedCalendarId: Long? = null,
)
data class CalendarOperationState(val saving: Boolean = false, val error: String? = null, val notice: String? = null)

/** Operations own their lifetime and errors; composables only render state and submit actions. */
class CalendarViewModel(context: Context, private val savedState: SavedStateHandle) : ViewModel() {
    private val appContext = context.applicationContext
    val db = BiboDb.get(appContext)
    val calendar = DeviceCalendarRepo(appContext)
    val usage = UsageRepo(appContext)
    private val restored = savedState.get<ArrayList<String>>("calendar.editor")?.let(CalendarDraftPersistence::decode)
    val draft = MutableStateFlow<CalendarEditorValue?>(restored?.second)
    val editor = MutableStateFlow<CalendarEditRequest?>(restored?.first)
    val detail = MutableStateFlow<CalendarItem?>(null)
    private val _operation = MutableStateFlow(CalendarOperationState())
    val operation: StateFlow<CalendarOperationState> = _operation

    fun beginEdit(request: CalendarEditRequest) {
        if (_operation.value.saving) return
        detail.value = null
        _operation.value = CalendarOperationState()
        draft.value = null
        editor.value = request
        savedState["calendar.editor"] = CalendarDraftPersistence.encode(request, null)
    }
    fun updateDraft(value: CalendarEditorValue) { draft.value = value; editor.value?.let { savedState["calendar.editor"] = CalendarDraftPersistence.encode(it, value) } }
    fun showDetail(item: CalendarItem) { _operation.value = CalendarOperationState(); detail.value = item }
    fun dismissEditor() { if (!_operation.value.saving) { editor.value = null; draft.value = null; savedState.remove<ArrayList<String>>("calendar.editor"); _operation.value = CalendarOperationState() } }
    fun dismissDetail() { if (!_operation.value.saving) { detail.value = null; _operation.value = CalendarOperationState() } }
    fun clearError() { _operation.value = _operation.value.copy(error = null) }
    fun clearNotice() { _operation.value = _operation.value.copy(notice = null) }
    fun showError(message: String) { _operation.value = CalendarOperationState(error = message) }

    fun save(value: CalendarEditorValue) {
        val request = editor.value ?: return
        if (_operation.value.saving) return
        if (request.editItem == null && request.destinationLocked && value.calendarId != request.lockedCalendarId) {
            showError("This pending save is tied to its original destination. Retry there, or close this draft and check that destination before making a new entry.")
            return
        }
        if (value.title.isBlank()) { showError("Enter an event title."); return }
        if (request.requiresTimeCorrection && !value.timeCorrectionAccepted) { showError("Choose and apply the activity date or time before saving."); return }
        val conversion = if (value.allDay) CalendarTime.allDay(value.start.toLocalDate(), value.end.toLocalDate())
            else CalendarTime.timed(value.start, value.end, value.zone, value.startOffset, value.endOffset)
        if (conversion is CalendarTimeResult.Invalid) { showError(conversion.message); return }
        val interval = (conversion as CalendarTimeResult.Valid).interval
        if (request.editItem == null && !request.destinationLocked) {
            // One durable create command must never be replayed across provider and local stores.
            val lockedRequest = request.copy(destinationLocked = true, lockedCalendarId = value.calendarId)
            draft.value = value
            editor.value = lockedRequest
            savedState["calendar.editor"] = CalendarDraftPersistence.encode(lockedRequest, value)
        }
        _operation.value = CalendarOperationState(saving = true)
        AppScope.io.launch {
            val result = try {
                when (val item = request.editItem) {
                    is CalendarItem.Device -> calendar.changeEvent(item.event, CalendarWriteRequest(item.event.calendarId,
                        value.title, interval.startMillis, interval.endMillis, value.allDay, if (value.allDay) "UTC" else value.zone.id, request.commandId))
                    is CalendarItem.Block -> {
                        if (item.block.source == "COMPLETION") CalendarResult.Failure(CalendarFailure.READ_ONLY, "Task completion history is changed from the task, not the calendar.")
                        else db.withTransaction {
                            val current = db.activityBlocks().byId(item.block.id)
                            when {
                                current == null -> CalendarResult.Failure(CalendarFailure.NOT_FOUND, "This activity was removed. Reload the calendar.")
                                current != item.block ->
                                    CalendarResult.Failure(CalendarFailure.CONFLICT, "This activity changed. Close the editor and reload it before saving.")
                                else -> {
                                    db.activityBlocks().update(current.copy(title = value.title, startMillis = interval.startMillis,
                                        endMillis = interval.endMillis, allDay = value.allDay, eventZone = if (value.allDay) "UTC" else value.zone.id))
                                    CalendarResult.Success(CalendarWriteReceipt(current.id, "Bibo only"))
                                }
                            }
                        }
                    }
                    is CalendarItem.Usage -> CalendarResult.Failure(CalendarFailure.READ_ONLY, "Usage observations cannot be edited.")
                    null -> if (value.calendarId != null) {
                        calendar.createEvent(CalendarWriteRequest(value.calendarId, value.title, interval.startMillis,
                            interval.endMillis, value.allDay, if (value.allDay) "UTC" else value.zone.id, request.commandId))
                    } else {
                        val source = if (request.source == "MANUAL" || request.source == "PLAN") "PLAN" else request.source
                        db.withTransaction {
                            val existing = db.activityBlocks().byCommandId(request.commandId)
                            if (existing != null) {
                                if (existing.title == value.title && existing.startMillis == interval.startMillis && existing.endMillis == interval.endMillis && existing.allDay == value.allDay)
                                    CalendarResult.Success(CalendarWriteReceipt(existing.id, "Bibo only"))
                                else CalendarResult.Failure(CalendarFailure.CONFLICT, "This save was already applied. Open the saved local plan or activity to change it.")
                            } else {
                                val id = db.activityBlocks().insert(ActivityBlock(title = value.title, startMillis = interval.startMillis,
                                    endMillis = interval.endMillis, source = source, allDay = value.allDay,
                                    eventZone = if (value.allDay) "UTC" else value.zone.id, commandId = request.commandId,
                                    provenance = if (source == "PLAN") "USER_PLAN" else "USER_LOG"))
                                CalendarResult.Success(CalendarWriteReceipt(id, "Bibo only"))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                CalendarResult.Failure(CalendarFailure.PROVIDER_UNAVAILABLE, "Your changes could not be saved. They remain in the editor; try again.")
            }
            when (result) {
                is CalendarResult.Success -> withContext(Dispatchers.Main.immediate) { editor.value = null; draft.value = null; savedState.remove<ArrayList<String>>("calendar.editor"); _operation.value = CalendarOperationState(notice = "Saved to ${result.value.calendarName}") }
                is CalendarResult.Failure -> _operation.value = CalendarOperationState(error = result.message)
            }
        }
    }

    fun delete(item: CalendarItem) {
        if (_operation.value.saving) return
        _operation.value = CalendarOperationState(saving = true)
        AppScope.io.launch {
            val result = try {
                when (item) {
                    is CalendarItem.Device -> calendar.removeEvent(item.event)
                    is CalendarItem.Block -> if (item.block.source == "COMPLETION") {
                        CalendarResult.Failure(CalendarFailure.READ_ONLY, "Task completion history is changed from the task, not the calendar.")
                    } else db.withTransaction {
                        val current = db.activityBlocks().byId(item.block.id)
                        when {
                            current == null -> CalendarResult.Failure(CalendarFailure.NOT_FOUND, "This activity was already removed.")
                            current != item.block -> CalendarResult.Failure(CalendarFailure.CONFLICT, "This activity changed. Reload it before deleting.")
                            else -> { db.activityBlocks().delete(current); CalendarResult.Success(CalendarWriteReceipt(current.id, "Bibo only")) }
                        }
                    }
                    is CalendarItem.Usage -> CalendarResult.Failure(CalendarFailure.READ_ONLY, "Usage observations cannot be deleted.")
                }
            } catch (_: Exception) { CalendarResult.Failure(CalendarFailure.PROVIDER_UNAVAILABLE, "The item could not be deleted. Try again.") }
            when (result) {
                is CalendarResult.Success -> { detail.value = null; _operation.value = CalendarOperationState(notice = "Deleted from ${result.value.calendarName}") }
                is CalendarResult.Failure -> _operation.value = CalendarOperationState(error = result.message)
            }
        }
    }
}
