package com.bibo.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRecoveryTest {
    private lateinit var context: IsolatedContext
    private lateinit var source: File
    private lateinit var archive: File
    @Before fun setup() = runBlocking {
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db = BiboDb.builder(context, "source.db").build()
        db.todos().insert(TodoTask(title = "Preserve me", createdAt = 123))
        db.close()
        source = context.getDatabasePath("source.db")
        context.getSharedPreferences("rewards", Context.MODE_PRIVATE).edit().putInt("weekly_budget_cents", 4321).commit()
        context.getSharedPreferences("mentor", Context.MODE_PRIVATE).edit().putString("api_key", "do-not-export").putString("persona", "Local notes").commit()
        context.getSharedPreferences("mentor_credentials", Context.MODE_PRIVATE).edit().putString("ciphertext", "do-not-export-encrypted-binding").commit()
        archive = File(context.cacheDir, "verified.zip")
        BackupRecovery.writeSnapshot(context, source, archive.outputStream())
    }
    @After fun cleanup() { context.cleanup() }

    @Test fun archiveExcludesCredentialsAndValidatesRealRoomSchema() = runBlocking {
        val preview = BackupRecovery.inspectImport(context, Uri.fromFile(archive))
        assertEquals(13, preview.schemaVersion)
        assertEquals(1L, preview.tasks)
        val prefs = JSONObject(entries(archive).getValue("preferences.json").toString(Charsets.UTF_8))
        assertEquals("Local notes", prefs.getJSONObject("mentor").getString("persona"))
        assertFalse(prefs.toString().contains("do-not-export"))
        assertFalse(prefs.has("mentor_credentials"))
        assertEquals("do-not-export", context.getSharedPreferences("mentor", Context.MODE_PRIVATE).getString("api_key", null))
    }
    @Test fun corruptChecksumAndInvalidSchemaNeverModifyCurrentData() = runBlocking {
        val original = source.readBytes()
        val corrupted = entries(archive).toMutableMap()
        corrupted["database.sqlite"] = corrupted.getValue("database.sqlite").copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val corruptFile = writeEntries(corrupted, "corrupt.zip")
        assertTrue(runCatching { BackupRecovery.inspectImport(context, Uri.fromFile(corruptFile)) }.isFailure)
        assertArrayEquals(original, source.readBytes())
        val invalidDb = context.getDatabasePath("invalid.db")
        val identity = android.database.sqlite.SQLiteDatabase.openDatabase(source.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { sql ->
            sql.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { it.moveToFirst(); it.getString(0) }
        }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(invalidDb, null).use {
            it.execSQL("CREATE TABLE unrelated(id INTEGER)")
            it.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
            it.execSQL("INSERT INTO room_master_table VALUES(42, ?)", arrayOf(identity))
            it.version = 13
        }
        val invalid = entries(archive).toMutableMap()
        invalid["database.sqlite"] = invalidDb.readBytes()
        val metadata = JSONObject(invalid.getValue("manifest.json").toString(Charsets.UTF_8))
        metadata.put("database_sha256", sha(invalid.getValue("database.sqlite")))
        invalid["manifest.json"] = metadata.toString().toByteArray()
        assertTrue(runCatching { BackupRecovery.inspectImport(context, Uri.fromFile(writeEntries(invalid, "schema.zip"))) }.isFailure)
        assertArrayEquals(original, source.readBytes())
        assertFalse(BackupRecovery.isMaintenance)
    }
    @Test fun previewDigestAlsoBindsPreferencesAndRejectsWrongTypes() = runBlocking {
        val before = BackupRecovery.inspectImport(context, Uri.fromFile(archive))
        val changed = entries(archive).toMutableMap()
        val prefs = JSONObject(changed.getValue("preferences.json").toString(Charsets.UTF_8))
        prefs.getJSONObject("rewards").put("weekly_budget_cents", 9999)
        changed["preferences.json"] = prefs.toString().toByteArray()
        val metadata = JSONObject(changed.getValue("manifest.json").toString(Charsets.UTF_8))
        metadata.put("preferences_sha256", sha(changed.getValue("preferences.json")))
        changed["manifest.json"] = metadata.toString().toByteArray()
        val after = BackupRecovery.inspectImport(context, Uri.fromFile(writeEntries(changed, "changed.zip")))
        assertNotEquals(before.sourceDigest, after.sourceDigest)
        prefs.getJSONObject("rewards").put("weekly_budget_cents", "broken")
        changed["preferences.json"] = prefs.toString().toByteArray()
        metadata.put("preferences_sha256", sha(changed.getValue("preferences.json")))
        changed["manifest.json"] = metadata.toString().toByteArray()
        assertTrue(runCatching { BackupRecovery.inspectImport(context, Uri.fromFile(writeEntries(changed, "invalid-prefs.zip"))) }.isFailure)
    }
    @Test fun interruptedReplacementRollsBackDatabaseAndPreferencesTogether() = runBlocking {
        val previous = File(context.filesDir, "backup-recovery/previous.bibo.zip")
        previous.parentFile!!.mkdirs(); archive.copyTo(previous)
        val current = BiboDb.builder(context, "bibo.db").build()
        current.todos().insert(TodoTask(title = "Partial replacement", createdAt = 456)); current.close()
        context.getSharedPreferences("rewards", Context.MODE_PRIVATE).edit().putInt("weekly_budget_cents", 111).commit()
        File(previous.parentFile, "restore-pending").writeText("restore-pending")
        BackupRecovery.recoverInterruptedRestore(context)
        assertFalse(BackupRecovery.isMaintenance)
        assertFalse(File(previous.parentFile, "restore-pending").exists())
        val recovered = BiboDb.builder(context, "bibo.db").build()
        try { assertEquals(listOf("Preserve me"), recovered.todos().allOnce().map { it.title }) } finally { recovered.close() }
        assertEquals(4321, context.getSharedPreferences("rewards", Context.MODE_PRIVATE).getInt("weekly_budget_cents", 0))
        assertFalse(context.getSharedPreferences("mentor_credentials", Context.MODE_PRIVATE).contains("ciphertext"))
        // A subsequent startup is idempotent and preserves the recovered snapshot.
        BackupRecovery.recoverInterruptedRestore(context)
        assertTrue(previous.isFile)
    }
    @Test fun interruptedRecoveryCompletesAlreadyAcceptedSnapshotWithoutOpeningBrokenOriginal() = runBlocking {
        val directory = File(context.filesDir, "backup-recovery").also { it.mkdirs() }
        archive.copyTo(File(directory, "accepted.bibo.zip"))
        context.getDatabasePath("bibo.db").writeText("unreadable original database")
        File(directory, "restore-pending").writeText("finish-verified")
        BackupRecovery.recoverInterruptedRestore(context)
        assertFalse(BackupRecovery.isMaintenance)
        assertFalse(File(directory, "restore-pending").exists())
        val recovered = BiboDb.builder(context, "bibo.db").build()
        try { assertEquals(listOf("Preserve me"), recovered.todos().allOnce().map { it.title }) } finally { recovered.close() }
    }
    @Test fun leavingTheScreenKeepsOperationOwnedAndRejectsConcurrentRestoreUntilValidationFinishes() = runBlocking {
        val pipe = File(context.cacheDir, "delayed-backup")
        android.system.Os.mkfifo(pipe.path, 384)
        val screen = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val command = screen.launch { runCatching { BackupRecovery.restore(context, Uri.fromFile(pipe)) } }
        try {
            withTimeout(5_000) { BackupRecovery.operation.first { it.busy } }
            // Simulate a destroyed screen: the accepted recovery keeps its critical section alive.
            command.cancel()
            assertTrue(BackupRecovery.operation.value.busy)
            assertTrue(runCatching { BackupRecovery.restore(context, Uri.fromFile(archive)) }.isFailure)
            assertTrue(BackupRecovery.operation.value.busy)
            assertNull(BackupRecovery.operation.value.error)
        } finally {
            // Release the blocked document read. Malformed input must finish as a retained error,
            // without touching the existing database or leaving Close permanently disabled.
            withContext(Dispatchers.IO) { pipe.outputStream().use { it.write("invalid archive".toByteArray()) } }
            withTimeout(5_000) { command.join() }
            screen.cancel()
            pipe.delete()
        }
        val recreatedScreenState = BackupRecovery.operation.value
        assertFalse(recreatedScreenState.busy)
        assertNotNull(recreatedScreenState.error)
        assertFalse(BackupRecovery.isMaintenance)
        assertEquals("do-not-export", context.getSharedPreferences("mentor", Context.MODE_PRIVATE).getString("api_key", null))
    }
    private fun entries(file: File): Map<String, ByteArray> = buildMap {
        ZipInputStream(file.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()); zip.closeEntry() } }
    }
    private fun writeEntries(entries: Map<String, ByteArray>, name: String): File = File(context.cacheDir, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (key, bytes) -> zip.putNextEntry(ZipEntry(key)); zip.write(bytes); zip.closeEntry() } }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "backup-test-${UUID.randomUUID()}-"
        private val root = File(base.cacheDir, prefix).also { it.mkdirs() }
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").also { it.mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").also { it.mkdirs() }
        override fun getDatabasePath(name: String): File = File(File(root, "databases").also { it.mkdirs() }, name)
        override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferences += prefix + name
            return baseContext.getSharedPreferences(prefix + name, mode)
        }
        fun cleanup() { root.deleteRecursively(); preferences.forEach { baseContext.deleteSharedPreferences(it) } }
    }
}
