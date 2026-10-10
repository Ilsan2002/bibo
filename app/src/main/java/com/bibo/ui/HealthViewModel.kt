package com.bibo.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.bibo.data.AppScope
import com.bibo.data.BiboDb
import com.bibo.data.FoodEntry
import com.bibo.data.HealthCommands
import com.bibo.data.HealthHabit
import com.bibo.data.HealthLogConflict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.LocalDate
import java.util.UUID

data class HealthDayState(val day: Long, val habit: com.bibo.data.HabitDay? = null, val foods: List<FoodEntry> = emptyList(),
    val blocks: List<com.bibo.data.ActivityBlock> = emptyList(), val loaded: Boolean = false, val error: String? = null)

data class HealthDraft(val day: Long, val log: HealthLog, val operationId: String = UUID.randomUUID().toString())
data class HealthOperationState(val parsing: Boolean = false, val saving: Boolean = false, val draft: HealthDraft? = null,
    val message: String? = null, val error: String? = null)

class HealthViewModel(context: Context, private val savedState: SavedStateHandle) : ViewModel() {
    private val db = BiboDb.get(context.applicationContext)
    private val commands = HealthCommands(db)
    private val parser = HealthParser(context.applicationContext)
    private val restored = savedState.get<ArrayList<String>>(DRAFT_KEY)?.let(HealthDraftPersistence::decode)
    private val mutable = MutableStateFlow(HealthOperationState(draft = restored))
    val state = mutable.asStateFlow()
    init { if (restored != null && savedState.get<Boolean>(ACCEPTED_KEY) == true) saveDraft() }

    private fun persistDraft(draft: HealthDraft?) {
        if (draft == null) savedState.remove<ArrayList<String>>(DRAFT_KEY)
        else savedState[DRAFT_KEY] = HealthDraftPersistence.encode(draft)
    }

    fun observeDay(day: Long, zone: ZoneId): Flow<HealthDayState> {
        val window = com.bibo.data.AppTime.window(LocalDate.ofEpochDay(day), zone)
        return combine(db.habits().forDay(day), db.foods().forDay(day), db.activityBlocks().blocksIn(window.startMillis, window.endMillis)) { habit, foods, blocks ->
            HealthDayState(day, habit, foods, blocks, loaded = true)
        }.onStart { emit(HealthDayState(day)) }.catch { error ->
            if (error is CancellationException) throw error
            emit(HealthDayState(day, error = "Could not read this day’s records. Retry before making changes."))
        }
    }

    fun parse(text: String, targetDay: Long, referenceDay: Long = com.bibo.data.AppTime.today().toEpochDay()) {
        if (mutable.value.parsing || mutable.value.saving) return
        persistDraft(null)
        savedState[ACCEPTED_KEY] = false
        mutable.value = HealthOperationState(parsing = true)
        viewModelScope.launch {
            try {
                val log = parser.parse(text, LocalDate.ofEpochDay(referenceDay))
                mutable.value = if (log.isEmpty) HealthOperationState(error = log.warnings.firstOrNull() ?: "No completed food or habit found. Use manual nutrition entry for other foods.")
                    else HealthOperationState(draft = HealthDraft(log.explicitEpochDay ?: targetDay, log))
                persistDraft(mutable.value.draft)
            } catch (e: CancellationException) { mutable.value = HealthOperationState(); throw e }
            catch (e: Exception) { mutable.value = HealthOperationState(error = "Could not interpret that log. You can enter it manually.") }
        }
    }
    fun manual(food: FoodItem, day: Long) {
        if (mutable.value.parsing || mutable.value.saving) return
        mutable.value = HealthOperationState(draft = HealthDraft(day, HealthLog(foods = listOf(food))))
        persistDraft(mutable.value.draft); savedState[ACCEPTED_KEY] = false
    }
    fun revise(log: HealthLog) { if (!mutable.value.saving) mutable.value.draft?.let { mutable.value = mutable.value.copy(draft = it.copy(log = log)); persistDraft(mutable.value.draft) } }
    fun moveDraftDay(delta: Long) { if (!mutable.value.saving) mutable.value.draft?.let { mutable.value = mutable.value.copy(draft = it.copy(day = it.day + delta)); persistDraft(mutable.value.draft) } }
    fun dismissDraft() { if (!mutable.value.saving) { mutable.value = HealthOperationState(); persistDraft(null); savedState[ACCEPTED_KEY] = false } }
    fun saveDraft() {
        val draft = mutable.value.draft ?: return
        if (mutable.value.saving) return
        mutable.value = mutable.value.copy(saving = true, error = null)
        persistDraft(draft); savedState[ACCEPTED_KEY] = true
        // Once explicitly accepted, the transaction survives tab/activity departure.
        AppScope.io.launch {
            try {
                commands.saveLog(draft.day, draft.log, draft.operationId)
                withContext(Dispatchers.Main.immediate) {
                    mutable.value = HealthOperationState(message = "Saved to ${LocalDate.ofEpochDay(draft.day)}")
                    persistDraft(null); savedState[ACCEPTED_KEY] = false
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { withContext(Dispatchers.Main.immediate) {
                mutable.value = mutable.value.copy(saving = false, error = if (e is HealthLogConflict) e.message else "Nothing was saved. Check the values and retry.")
                savedState[ACCEPTED_KEY] = false
            } }
        }
    }
    fun cycle(day: Long, habit: HealthHabit) { write { commands.cycleHabit(day, habit) } }
    fun updateFood(entry: FoodEntry) { write {
        require(FoodItem(entry.label, entry.calories, entry.sugarG, entry.caffeineMg).valid)
        db.foods().update(entry)
    } }
    fun deleteFood(entry: FoodEntry, onDeleted: () -> Unit) { write { db.foods().delete(entry); onDeleted() } }
    fun restoreFood(entry: FoodEntry) { write { db.foods().insert(entry) } }
    private fun write(block: suspend () -> Unit) { AppScope.io.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(error = "The change could not be saved. Try again.") }
    } }
    fun clearNotice() { mutable.value = mutable.value.copy(message = null, error = null) }
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = HealthViewModel(context, extras.createSavedStateHandle()) as T
    }
    companion object {
        private const val DRAFT_KEY = "health.draft"
        private const val ACCEPTED_KEY = "health.accepted"
    }
}
