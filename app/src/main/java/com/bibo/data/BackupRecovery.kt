package com.bibo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupPreview(val schemaVersion: Int, val tasks: Long, val blocks: Long, val sourceDigest: String)
data class BackupResult(val message: String, val restartRequired: Boolean)
data class BackupOperationState(val busy: Boolean = false, val result: BackupResult? = null, val error: String? = null)

/** Portable verified snapshot. File replacement is permitted only after Room is closed. */
object BackupRecovery {
    private val operationState = MutableStateFlow(BackupOperationState())
    val operation = operationState.asStateFlow()
    private val operationMonitor = Any()
    private val maintenanceState = MutableStateFlow(false)
    val maintenance = maintenanceState.asStateFlow()
    @Volatile var isMaintenance: Boolean = false
        private set(value) { field = value; maintenanceState.value = value }
    private val lock = Mutex()
    private val recoveryMonitor = Any()
    private const val MAX_DATABASE = 128L * 1024 * 1024
    private const val MAX_METADATA = 2L * 1024 * 1024
    private val preferenceKeys = mapOf(
        "rewards" to setOf("weekly_budget_cents"),
        "bibo_health_preferences" to setOf("targets_enabled", "calories", "sugar", "caffeine", "visible_habits"),
        "bibo_privacy" to setOf("website_recording"),
        "bibo_focus_defaults" to setOf("last_blocked_apps"),
        "mentor" to (setOf("memory", "memory_prev", "persona", "checkins_enabled", "automatic_cheers", "cadence_hours", "quiet_start", "quiet_end") +
            listOf("tasks", "goals", "calendar", "activity", "habits", "food", "usage", "rewards").map { "share_$it" }),
    )

    private data class Prepared(val database: File, val preferences: JSONObject, val preview: BackupPreview, val temporaryName: String)

    suspend fun inspectImport(context: Context, source: Uri): BackupPreview = withContext(Dispatchers.IO) {
        val prepared = prepare(context, context.contentResolver.openInputStream(source) ?: error("Backup could not be opened"))
        try { prepared.preview } finally { cleanup(context, prepared) }
    }

    private suspend fun runOperation(action: suspend () -> BackupResult): BackupResult = withContext(NonCancellable + Dispatchers.IO) {
        synchronized(operationMonitor) {
            check(!operationState.value.busy) { "A backup operation is already in progress" }
            operationState.value = BackupOperationState(busy = true)
        }
        try {
            val result = action()
            operationState.value = BackupOperationState(result = result)
            result
        } catch (failure: Throwable) {
            operationState.value = BackupOperationState(error = failure.message ?: "Recovery operation failed")
            throw failure
        }
    }

    suspend fun export(context: Context, destination: Uri): BackupResult = runOperation {
        lock.withLock {
            check(!isMaintenance) { "Restart Bibo before another recovery operation" }
            quiesce(context)
            try {
                val database = context.getDatabasePath("bibo.db")
                val version = checkpointAndVerify(database)
                context.contentResolver.openOutputStream(destination, "wt")?.use { writeArchive(database, preferences(context), version, it) }
                    ?: error("Backup destination could not be opened")
                BackupResult("Verified backup exported. API keys and permission grants were excluded. Restart Bibo to continue.", true)
            } catch (failure: Exception) {
                // Existing DAO owners are now closed: keep maintenance active and require restart even on failure.
                throw IllegalStateException("Export failed: ${failure.message}. Your data is unchanged; restart Bibo to continue.", failure)
            }
        }
    }

    suspend fun restore(context: Context, source: Uri, expectedDigest: String? = null): BackupResult = runOperation {
        lock.withLock {
            check(!isMaintenance || startupRecovery()) { "Restart Bibo before restoring" }
            val prepared = prepare(context, context.contentResolver.openInputStream(source) ?: error("Backup could not be opened"))
            try {
                if (expectedDigest != null) check(prepared.preview.sourceDigest == expectedDigest) { "Backup changed after preview; select it again" }
                replace(context, prepared)
            } finally { cleanup(context, prepared) }
        }
    }

    fun previousBackupAvailable(context: Context): Boolean = previous(context).isFile
    fun recoveryPending(context: Context): Boolean = journal(context).isFile

    suspend fun retryInterruptedRecovery(context: Context): BackupResult = runOperation {
        lock.withLock {
            require(recoveryPending(context)) { "No interrupted restore is pending" }
            isMaintenance = true
            BiboDb.closeForMaintenance()
            val old = prepare(context, journalSource(context).inputStream())
            try { install(context, old); check(journal(context).delete()) { "Recovery could not commit" } }
            finally { cleanup(context, old) }
            BackupResult("Verified recovery completed. Restart Bibo and reconnect Mentor to continue.", true)
        }
    }

    /** Called before opening the main database, including from workers and services. */
    fun recoverInterruptedRestore(context: Context) = synchronized(recoveryMonitor) {
        if (isMaintenance || !journal(context).isFile) return@synchronized
        val ownsOperation = synchronized(operationMonitor) {
            if (operationState.value.busy) false else {
                operationState.value = BackupOperationState(busy = true)
                true
            }
        }
        isMaintenance = true
        try {
            val old = prepare(context, journalSource(context).inputStream())
            try { install(context, old) } finally { cleanup(context, old) }
            check(journal(context).delete()) { "Could not finish interrupted restore recovery" }
            isMaintenance = false
            if (ownsOperation) operationState.value = BackupOperationState(result = BackupResult("Interrupted restore recovered; your local data is ready to open.", false))
        } catch (failure: Exception) {
            // Keep the recovery marker and original snapshot; never replace them with an empty database.
            val error = "Interrupted restore needs recovery. The recovery snapshot is preserved: ${failure.message}"
            if (ownsOperation) operationState.value = BackupOperationState(error = error)
            throw IllegalStateException(error, failure)
        }
    }

    suspend fun restorePrevious(context: Context): BackupResult = runOperation {
        lock.withLock {
            check(!isMaintenance) { "Restart Bibo before restoring" }
            val source = previous(context)
            check(source.isFile) { "No recovery snapshot is available" }
            val prepared = prepare(context, source.inputStream())
            try { replace(context, prepared) } finally { cleanup(context, prepared) }
        }
    }

    private suspend fun quiesce(context: Context) {
        com.bibo.ui.Mentor.pauseForBackup(context)
        try { TimerController.prepareForBackup(context) { isMaintenance = true } }
        catch (failure: Exception) {
            if (!isMaintenance) com.bibo.ui.Mentor.resumeAfterBackup(context)
            throw failure
        }
        AppScope.io.coroutineContext.cancelChildren()
        WebTrackingService.resetRecordingHistory(context)
        BiboDb.closeForMaintenance()
    }

    private suspend fun replace(context: Context, prepared: Prepared): BackupResult {
        if (startupRecovery()) return replaceUnreadable(context, prepared)
        quiesce(context)
        val target = context.getDatabasePath("bibo.db")
        val safety = previous(context)
        var preserved = false
        try {
            val originalVersion = checkpointAndVerify(target)
            safety.parentFile?.mkdirs()
            val safetyTemporary = File(safety.parentFile, "previous.tmp")
            safetyTemporary.outputStream().use { writeArchive(target, preferences(context), originalVersion, it) }
            check(safetyTemporary.renameTo(safety)) { "Could not preserve the current recovery snapshot" }
            preserved = true
            // A durable marker precedes every change to the database/preferences pair. On process death,
            // the next database open rolls both back from the verified safety archive.
            FileOutputStream(journal(context)).use { it.write("restore-pending".toByteArray()); it.fd.sync() }
            install(context, prepared)
            check(journal(context).delete()) { "Could not commit the restored snapshot" }
            return BackupResult("Backup restored. Previous data is retained in Recovery. Reconnect Mentor and restart Bibo to continue.", true)
        } catch (failure: Exception) {
            if (preserved) {
                // Roll back immediately; a retained archive also supports a subsequent user-initiated recovery.
                runCatching {
                    val old = prepare(context, safety.inputStream())
                    try {
                        install(context, old)
                        if (journal(context).isFile) check(journal(context).delete())
                    } finally { cleanup(context, old) }
                }.onFailure { throw IllegalStateException("Restore failed and automatic rollback failed. The previous snapshot is preserved in Recovery. Restart Bibo and restore it: ${it.message}", failure) }
            }
            throw IllegalStateException("Restore failed: ${failure.message}. Restart Bibo; the previous data was retained.", failure)
        }
    }

    private fun previous(context: Context): File = File(context.filesDir, "backup-recovery/previous.bibo.zip")
    private fun journal(context: Context): File = File(context.filesDir, "backup-recovery/restore-pending")
    private fun accepted(context: Context): File = File(context.filesDir, "backup-recovery/accepted.bibo.zip")
    private fun journalSource(context: Context): File = if (journal(context).readText() == "finish-verified") accepted(context) else previous(context)
    private fun startupRecovery(): Boolean = !AppStartup.ready.value && AppStartup.error.value != null

    private suspend fun replaceUnreadable(context: Context, prepared: Prepared): BackupResult {
        com.bibo.ui.Mentor.pauseForBackup(context)
        isMaintenance = true
        AppScope.io.coroutineContext.cancelChildren()
        TimerController.resetForDatabaseReplacement(context)
        WebTrackingService.resetRecordingHistory(context)
        BiboDb.closeForMaintenance()
        val directory = File(context.filesDir, "backup-recovery").also { check(it.isDirectory || it.mkdirs()) }
        // Preserve every closed original byte, including sidecars, even if SQLite cannot open it.
        val forensic = File(directory, "unreadable-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val original = context.getDatabasePath("bibo.db")
        listOf(original, File(original.path + "-wal"), File(original.path + "-shm")).filter { it.exists() }.forEach { it.copyTo(File(forensic, it.name)) }
        FileOutputStream(File(forensic, "preferences.json")).use { output -> output.write(preferences(context).toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
        val temporary = File(directory, "accepted.tmp")
        temporary.outputStream().use { writeArchive(prepared.database, prepared.preferences, prepared.preview.schemaVersion, it) }
        check(temporary.renameTo(accepted(context))) { "Could not stage verified recovery" }
        FileOutputStream(journal(context)).use { it.write("finish-verified".toByteArray()); it.fd.sync() }
        // On interruption, startup completes this already accepted verified snapshot instead of reopening broken data.
        install(context, prepared)
        check(journal(context).delete()) { "Recovery could not commit" }
        return BackupResult("Verified backup recovered. Unreadable original files are retained locally. Restart Bibo and reconnect Mentor.", true)
    }

    private fun install(context: Context, prepared: Prepared) {
        val target = context.getDatabasePath("bibo.db")
        val replacement = File(target.parentFile, "bibo.restore.tmp")
        prepared.database.inputStream().use { input -> FileOutputStream(replacement).use { output -> input.copyTo(output); output.fd.sync() } }
        checkpointAndVerify(replacement)
        check(!File(target.path + "-wal").exists() || File(target.path + "-wal").delete()) { "Could not remove closed database WAL" }
        check(!File(target.path + "-shm").exists() || File(target.path + "-shm").delete()) { "Could not remove closed database shared memory" }
        check(replacement.renameTo(target)) { "Could not install the verified snapshot" }
        // Disconnect the device binding without exporting or copying Android Keystore material.
        check(context.getSharedPreferences("mentor_credentials", Context.MODE_PRIVATE).edit().clear().commit()) { "Could not disconnect Mentor credentials" }
        applyPreferences(context, prepared.preferences)
    }

    private fun prepare(context: Context, input: InputStream): Prepared {
        val directory = File(context.cacheDir, "backup-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val raw = File(directory, "database.sqlite")
        val found = mutableSetOf<String>()
        var metadata: JSONObject? = null
        var prefs: JSONObject? = null
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in setOf("database.sqlite", "preferences.json", "manifest.json") && found.add(entry.name)) { "Unexpected or duplicate backup entry" }
                    val limit = if (entry.name == "database.sqlite") MAX_DATABASE else MAX_METADATA
                    val file = File(directory, entry.name)
                    file.outputStream().use { output -> total += copyLimited(zip, output, limit) }
                    check(total <= MAX_DATABASE + 2 * MAX_METADATA) { "Backup is too large" }
                    when (entry.name) {
                        "manifest.json" -> metadata = JSONObject(file.readText())
                        "preferences.json" -> prefs = JSONObject(file.readText())
                    }
                    zip.closeEntry()
                }
            }
            val manifest = metadata ?: error("Missing backup manifest")
            require(manifest.getInt("format") == 1 && manifest.getString("application") == "com.bibo") { "Unsupported backup format" }
            check(found.size == 3 && raw.isFile && prefs != null) { "Backup is incomplete" }
            val digest = sha256(raw)
            check(digest == manifest.getString("database_sha256")) { "Database checksum mismatch" }
            check(sha256(File(directory, "preferences.json")) == manifest.getString("preferences_sha256")) { "Preferences checksum mismatch" }
            validatePreferences(prefs)
            checkpointAndVerify(raw)
            // The archive's identity hash is untrusted. Removing it from this temporary copy makes
            // Room validate every actual table/column/index even when the version already matches.
            SQLiteDatabase.openDatabase(raw.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TABLE IF EXISTS room_master_table") }
            checkpointAndVerify(raw)
            // Room verifies identity/schema and runs only supported additive migrations in a temporary checkout.
            val name = "backup-validation-${UUID.randomUUID()}.db"
            val room = BiboDb.builder(context, name).createFromFile(raw).build()
            val validation = context.getDatabasePath(name)
            try {
                val sql = room.openHelper.writableDatabase
                val version = sql.version
                val tasks = sql.query("SELECT COUNT(*) FROM todo_tasks").use { it.moveToFirst(); it.getLong(0) }
                val blocks = sql.query("SELECT COUNT(*) FROM activity_blocks").use { it.moveToFirst(); it.getLong(0) }
                val preview = BackupPreview(version, tasks, blocks, sha256((digest + ":" + manifest.getString("preferences_sha256")).toByteArray()))
                room.close()
                checkpointAndVerify(validation)
                return Prepared(validation, prefs, preview, name)
            } catch (failure: Throwable) {
                room.close(); context.deleteDatabase(name); throw failure
            }
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
        finally { directory.deleteRecursively() }
    }

    private fun cleanup(context: Context, prepared: Prepared) { context.deleteDatabase(prepared.temporaryName) }

    private fun checkpointAndVerify(file: File): Int {
        check(file.isFile) { "Database file is missing" }
        val sql = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            sql.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { check(it.moveToFirst() && it.getInt(0) == 0) { "Database is busy; checkpoint did not complete" } }
            sql.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") { "Database integrity verification failed" } }
            return sql.version
        } finally { sql.close() }
    }

    private fun preferences(context: Context): JSONObject = JSONObject().apply {
        preferenceKeys.forEach { (name, allowed) ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.filterKeys { it in allowed }.forEach { (key, value) ->
                values.put(key, when (value) { is Set<*> -> JSONObject().put("type", "strings").put("value", JSONArray(value.toList())); else -> value })
            }
            put(name, values)
        }
    }

    private fun applyPreferences(context: Context, values: JSONObject) {
        preferenceKeys.forEach { (name, allowed) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            val group = values.optJSONObject(name) ?: JSONObject()
            allowed.forEach { key -> if (group.has(key)) {
                val value = group.get(key)
                when {
                    value is Number -> editor.putInt(key, value.toInt())
                    else -> when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is JSONObject -> if (value.optString("type") == "strings") {
                        val array = value.getJSONArray("value")
                        editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                    }
                }
                }
            } }
            check(editor.commit()) { "Preferences could not be restored" }
        }
    }

    private fun validatePreferences(values: JSONObject) {
        preferenceKeys.forEach { (name, allowed) ->
            val group = values.optJSONObject(name) ?: return@forEach
            allowed.forEach { key -> if (group.has(key)) {
                val value = group.get(key)
                val valid = when {
                    key in setOf("memory", "memory_prev", "persona") -> value is String
                    key == "last_blocked_apps" -> value is JSONObject && value.optString("type") == "strings" &&
                        value.optJSONArray("value")?.let { array -> (0 until array.length()).all { array.opt(it) is String } } == true
                    key in setOf("weekly_budget_cents", "calories", "sugar", "caffeine", "visible_habits", "cadence_hours", "quiet_start", "quiet_end") ->
                        (value is Int || value is Long) && when (key) {
                            "weekly_budget_cents" -> (value as Number).toLong() in 0..100_000_000L
                            "visible_habits" -> (value as Number).toLong() in 0..15L
                            "cadence_hours" -> (value as Number).toLong() in 1..24L
                            "quiet_start", "quiet_end" -> (value as Number).toLong() in 0..23L
                            else -> (value as Number).toLong() in 1..1_000_000L
                        }
                    else -> value is Boolean
                }
                require(valid) { "Invalid backup preference: $name/$key" }
            } }
        }
    }

    private fun writeArchive(database: File, preferences: JSONObject, version: Int, output: OutputStream) {
        val bytes = preferences.toString().toByteArray(Charsets.UTF_8)
        val manifest = JSONObject().put("format", 1).put("application", "com.bibo").put("schema_version", version)
            .put("exported_at", AppTime.nowMillis()).put("database_sha256", sha256(database)).put("preferences_sha256", sha256(bytes))
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("database.sqlite")); database.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            zip.putNextEntry(ZipEntry("preferences.json")); zip.write(bytes); zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.finish(); zip.flush()
            if (output is FileOutputStream) output.fd.sync()
        }
    }

    /** Caller owns quiescence; used for a closed local candidate or the main maintenance snapshot. */
    internal fun writeSnapshot(context: Context, database: File, output: OutputStream) {
        writeArchive(database, preferences(context), checkpointAndVerify(database), output)
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(8192); var count = 0L
        while (true) {
            val read = input.read(buffer); if (read < 0) return count
            count += read; require(count <= limit) { "Backup entry is too large" }; output.write(buffer, 0, read)
        }
    }
    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val read = input.read(buffer); if (read < 0) break; hash.update(buffer, 0, read) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
