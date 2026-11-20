package com.personal.disciplinelock.data.task

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.personal.disciplinelock.domain.task.DailyTaskItem
import com.personal.disciplinelock.domain.task.MissionVerificationType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * SQLite helper for Daily Habits, Today's Tasks, and Local Notes.
 *
 * Provides a 100% offline, lightweight storage mechanism with zero external server dependencies.
 */
class HabitTaskDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "HabitTaskDbHelper"
        private const val DATABASE_NAME = "discipline_tasks.db"
        private const val DATABASE_VERSION = 7

        // Table: habits (recurring)
        const val TABLE_HABITS = "habits"
        const val COL_HABIT_ID = "id"
        const val COL_HABIT_TITLE = "title"
        const val COL_HABIT_CATEGORY = "category"
        const val COL_HABIT_IS_ACTIVE = "is_active"
        const val COL_HABIT_CREATED_AT = "created_at"
        const val COL_HABIT_VERIFICATION_TYPE = "verification_type"
        const val COL_HABIT_VERIFICATION_ENDPOINT = "verification_endpoint"
        const val COL_HABIT_VERIFICATION_EXPECTED = "verification_expected"
        const val COL_HABIT_VERIFICATION_STATUS = "verification_status"

        // Table: habit_completions
        const val TABLE_HABIT_COMPLETIONS = "habit_completions"
        const val COL_COMPLETION_ID = "id"
        const val COL_COMPLETION_HABIT_ID = "habit_id"
        const val COL_COMPLETION_DATE = "completion_date" // YYYY-MM-DD
        const val COL_COMPLETION_TIMESTAMP = "completed_at"

        // Table: daily_tasks (ephemeral / specific to a day)
        const val TABLE_DAILY_TASKS = "daily_tasks"
        const val COL_TASK_ID = "id"
        const val COL_TASK_TITLE = "title"
        const val COL_TASK_DATE = "task_date" // YYYY-MM-DD
        const val COL_TASK_IS_COMPLETED = "is_completed"
        const val COL_TASK_PRIORITY = "priority"
        const val COL_TASK_CREATED_AT = "created_at"
        const val COL_TASK_CLOUD_ID = "cloud_id"
        const val COL_TASK_SYNC_STATUS = "sync_status" // 0: Synced, 1: Pending Create, 2: Pending Update, 3: Pending Delete
        const val COL_TASK_VERIFICATION_TYPE = "verification_type"
        const val COL_TASK_VERIFICATION_ENDPOINT = "verification_endpoint"
        const val COL_TASK_VERIFICATION_EXPECTED = "verification_expected"
        const val COL_TASK_VERIFICATION_STATUS = "verification_status"
        const val COL_TASK_LAST_VERIFIED_AT = "last_verified_at"
        const val COL_TASK_IS_RECURRING = "is_recurring" // Default 1 (defaulted to everyday)

        // Table: todo_tasks (casual everyday to-dos, independent of missions)
        const val TABLE_TODO_TASKS = "todo_tasks"
        const val COL_TODO_ID = "id"
        const val COL_TODO_TITLE = "title"
        const val COL_TODO_IS_COMPLETED = "is_completed"
        const val COL_TODO_CREATED_AT = "created_at"

        // Table: discipline_day_history (overall daily completion for streak tracking)
        const val TABLE_DISCIPLINE_DAYS = "discipline_day_history"
        const val COL_DISC_DATE = "date" // YYYY-MM-DD
        const val COL_DISC_COMPLETED_AT = "completed_at"
        const val COL_DISC_TOTAL_MISSIONS = "total_missions"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_HABITS (
                $COL_HABIT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_HABIT_TITLE TEXT NOT NULL,
                $COL_HABIT_CATEGORY TEXT DEFAULT 'General',
                $COL_HABIT_IS_ACTIVE INTEGER DEFAULT 1,
                $COL_HABIT_CREATED_AT INTEGER NOT NULL,
                $COL_HABIT_VERIFICATION_TYPE TEXT DEFAULT 'MANUAL',
                $COL_HABIT_VERIFICATION_ENDPOINT TEXT,
                $COL_HABIT_VERIFICATION_EXPECTED TEXT,
                $COL_HABIT_VERIFICATION_STATUS TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_HABIT_COMPLETIONS (
                $COL_COMPLETION_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_COMPLETION_HABIT_ID INTEGER NOT NULL,
                $COL_COMPLETION_DATE TEXT NOT NULL,
                $COL_COMPLETION_TIMESTAMP INTEGER NOT NULL,
                UNIQUE($COL_COMPLETION_HABIT_ID, $COL_COMPLETION_DATE)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DAILY_TASKS (
                $COL_TASK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TASK_TITLE TEXT NOT NULL,
                $COL_TASK_DATE TEXT NOT NULL,
                $COL_TASK_IS_COMPLETED INTEGER DEFAULT 0,
                $COL_TASK_PRIORITY INTEGER DEFAULT 1,
                $COL_TASK_CREATED_AT INTEGER NOT NULL,
                $COL_TASK_CLOUD_ID TEXT,
                $COL_TASK_SYNC_STATUS INTEGER DEFAULT 0,
                $COL_TASK_VERIFICATION_TYPE TEXT DEFAULT 'MANUAL',
                $COL_TASK_VERIFICATION_ENDPOINT TEXT,
                $COL_TASK_VERIFICATION_EXPECTED TEXT,
                $COL_TASK_VERIFICATION_STATUS TEXT,
                $COL_TASK_LAST_VERIFIED_AT INTEGER,
                $COL_TASK_IS_RECURRING INTEGER DEFAULT 1
            )
            """.trimIndent()
        )

        createTodoTasksTable(db)
        createDisciplineDaysTable(db)
        // Clean slate: no default/sample missions or tasks pre-seeded
    }

    private fun createTodoTasksTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TODO_TASKS (
                $COL_TODO_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TODO_TITLE TEXT NOT NULL,
                $COL_TODO_IS_COMPLETED INTEGER DEFAULT 0,
                $COL_TODO_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_DAILY_TASKS ADD COLUMN $COL_TASK_CLOUD_ID TEXT")
                db.execSQL("ALTER TABLE $TABLE_DAILY_TASKS ADD COLUMN $COL_TASK_SYNC_STATUS INTEGER DEFAULT 0")
                Log.i(TAG, "Upgraded database to version 2: added cloud_id and sync_status")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate to version 2", e)
            }
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE $TABLE_DAILY_TASKS ADD COLUMN $COL_TASK_VERIFICATION_TYPE TEXT DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE $TABLE_DAILY_TASKS ADD COLUMN $COL_TASK_VERIFICATION_ENDPOINT TEXT")
                db.execSQL("ALTER TABLE $TABLE_DAILY_TASKS ADD COLUMN $COL_TASK_VERIFICATION_EXPECTED TEXT")
                db.execSQL("ALTER TABLE $TABLE_DAILY_TASKS ADD COLUMN $COL_TASK_VERIFICATION_STATUS TEXT")
                db.execSQL("ALTER TABLE $TABLE_DAILY_TASKS ADD COLUMN $COL_TASK_LAST_VERIFIED_AT INTEGER")
                db.execSQL("ALTER TABLE $TABLE_DAILY_TASKS ADD COLUMN $COL_TASK_IS_RECURRING INTEGER DEFAULT 1")

                db.execSQL("ALTER TABLE $TABLE_HABITS ADD COLUMN $COL_HABIT_VERIFICATION_TYPE TEXT DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE $TABLE_HABITS ADD COLUMN $COL_HABIT_VERIFICATION_ENDPOINT TEXT")
                db.execSQL("ALTER TABLE $TABLE_HABITS ADD COLUMN $COL_HABIT_VERIFICATION_EXPECTED TEXT")
                db.execSQL("ALTER TABLE $TABLE_HABITS ADD COLUMN $COL_HABIT_VERIFICATION_STATUS TEXT")
                Log.i(TAG, "Upgraded database to version 4: added mission verification columns and daily recurrence")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate to version 4", e)
            }
        }
        if (oldVersion < 5) {
            try {
                createTodoTasksTable(db)
                Log.i(TAG, "Upgraded database to version 5: added todo_tasks table")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate to version 5", e)
            }
        }
        if (oldVersion < 6) {
            try {
                createDisciplineDaysTable(db)
                Log.i(TAG, "Upgraded database to version 6: added discipline_day_history table")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate to version 6", e)
            }
        }
        if (oldVersion < 7) {
            try {
                db.execSQL("DELETE FROM $TABLE_HABITS")
                db.execSQL("DELETE FROM $TABLE_HABIT_COMPLETIONS")
                db.execSQL("DELETE FROM $TABLE_DAILY_TASKS")
                db.execSQL("DELETE FROM $TABLE_TODO_TASKS")
                db.execSQL("DELETE FROM $TABLE_DISCIPLINE_DAYS")
                Log.i(TAG, "Upgraded database to version 7: purged all sample data")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate to version 7", e)
            }
        }
    }

    fun purgeAllMissions() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $TABLE_HABITS")
            db.execSQL("DELETE FROM $TABLE_HABIT_COMPLETIONS")
            db.execSQL("DELETE FROM $TABLE_DAILY_TASKS")
            db.execSQL("DELETE FROM $TABLE_TODO_TASKS")
            db.execSQL("DELETE FROM $TABLE_DISCIPLINE_DAYS")
            db.setTransactionSuccessful()
            Log.i(TAG, "Successfully purged all missions and sample data")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to purge all missions", e)
        } finally {
            db.endTransaction()
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        createDisciplineDaysTable(db)
    }

    private fun createDisciplineDaysTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DISCIPLINE_DAYS (
                $COL_DISC_DATE TEXT PRIMARY KEY,
                $COL_DISC_COMPLETED_AT INTEGER NOT NULL,
                $COL_DISC_TOTAL_MISSIONS INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    // ==================== Habits Operations ====================

    fun insertHabit(title: String, category: String = "General"): Long {
        val cv = ContentValues().apply {
            put(COL_HABIT_TITLE, title.trim())
            put(COL_HABIT_CATEGORY, category.trim())
            put(COL_HABIT_IS_ACTIVE, 1)
            put(COL_HABIT_CREATED_AT, System.currentTimeMillis())
            put(COL_HABIT_VERIFICATION_TYPE, "WEBHOOK")
        }
        return writableDatabase.insert(TABLE_HABITS, null, cv)
    }

    fun deleteHabit(habitId: Long): Boolean {
        val db = writableDatabase
        db.delete(TABLE_HABIT_COMPLETIONS, "$COL_COMPLETION_HABIT_ID = ?", arrayOf(habitId.toString()))
        return db.delete(TABLE_HABITS, "$COL_HABIT_ID = ?", arrayOf(habitId.toString())) > 0
    }

    fun getAllActiveHabits(): List<HabitRecord> {
        val list = mutableListOf<HabitRecord>()
        val cursor = readableDatabase.query(
            TABLE_HABITS,
            arrayOf(
                COL_HABIT_ID,
                COL_HABIT_TITLE,
                COL_HABIT_CATEGORY,
                COL_HABIT_VERIFICATION_TYPE,
                COL_HABIT_VERIFICATION_ENDPOINT,
                COL_HABIT_VERIFICATION_EXPECTED,
                COL_HABIT_VERIFICATION_STATUS
            ),
            "$COL_HABIT_IS_ACTIVE = 1",
            null, null, null,
            "$COL_HABIT_ID ASC"
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_HABIT_ID)
            val titleIdx = it.getColumnIndexOrThrow(COL_HABIT_TITLE)
            val catIdx = it.getColumnIndexOrThrow(COL_HABIT_CATEGORY)
            val vtIdx = it.getColumnIndexOrThrow(COL_HABIT_VERIFICATION_TYPE)
            val epIdx = it.getColumnIndexOrThrow(COL_HABIT_VERIFICATION_ENDPOINT)
            val expIdx = it.getColumnIndexOrThrow(COL_HABIT_VERIFICATION_EXPECTED)
            val stIdx = it.getColumnIndexOrThrow(COL_HABIT_VERIFICATION_STATUS)

            while (it.moveToNext()) {
                val id = it.getLong(idIdx)
                val title = it.getString(titleIdx)
                val cat = it.getString(catIdx)
                val rawVType = it.getString(vtIdx)
                val vType = if (rawVType.isNullOrBlank() || rawVType == "MANUAL") "WEBHOOK" else rawVType
                val endpoint = it.getString(epIdx)
                val expected = it.getString(expIdx)
                val status = it.getString(stIdx)
                list.add(HabitRecord(id, title, cat, vType, endpoint, expected, status))
            }
        }
        return list
    }

    fun updateHabitVerification(
        habitId: Long,
        verificationType: String,
        endpoint: String?,
        expectedResponse: String?
    ): Boolean {
        val cv = ContentValues().apply {
            put(COL_HABIT_VERIFICATION_TYPE, verificationType)
            put(COL_HABIT_VERIFICATION_ENDPOINT, endpoint?.trim())
            put(COL_HABIT_VERIFICATION_EXPECTED, expectedResponse?.trim())
        }
        return writableDatabase.update(TABLE_HABITS, cv, "$COL_HABIT_ID = ?", arrayOf(habitId.toString())) > 0
    }

    fun setHabitVerificationStatus(
        habitId: Long,
        status: String?
    ): Boolean {
        val cv = ContentValues().apply {
            put(COL_HABIT_VERIFICATION_STATUS, status)
        }
        return writableDatabase.update(TABLE_HABITS, cv, "$COL_HABIT_ID = ?", arrayOf(habitId.toString())) > 0
    }

    fun isHabitCompletedOnDate(habitId: Long, dateStr: String): Boolean {
        val cursor = readableDatabase.query(
            TABLE_HABIT_COMPLETIONS,
            arrayOf(COL_COMPLETION_ID),
            "$COL_COMPLETION_HABIT_ID = ? AND $COL_COMPLETION_DATE = ?",
            arrayOf(habitId.toString(), dateStr),
            null, null, null
        )
        cursor.use {
            return it.count > 0
        }
    }

    fun setHabitCompletion(habitId: Long, dateStr: String, completed: Boolean) {
        val db = writableDatabase
        if (completed) {
            val cv = ContentValues().apply {
                put(COL_COMPLETION_HABIT_ID, habitId)
                put(COL_COMPLETION_DATE, dateStr)
                put(COL_COMPLETION_TIMESTAMP, System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_HABIT_COMPLETIONS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            db.delete(
                TABLE_HABIT_COMPLETIONS,
                "$COL_COMPLETION_HABIT_ID = ? AND $COL_COMPLETION_DATE = ?",
                arrayOf(habitId.toString(), dateStr)
            )
        }
    }

    fun calculateStreak(habitId: Long, todayStr: String): Int {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        try {
            cal.time = sdf.parse(todayStr) ?: Date()
        } catch (e: Exception) {
            return 0
        }

        var streak = 0
        // If completed today, count today; if not completed today, check if completed yesterday to keep streak alive
        val completedToday = isHabitCompletedOnDate(habitId, todayStr)
        if (completedToday) {
            streak++
        }

        // Walk backwards day by day
        cal.add(Calendar.DAY_OF_YEAR, -1)
        while (true) {
            val checkDate = sdf.format(cal.time)
            if (isHabitCompletedOnDate(habitId, checkDate)) {
                streak++
                cal.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }
        return streak
    }

    // ==================== Daily Tasks Operations ====================

    fun insertDailyTask(
        title: String,
        dateStr: String,
        priority: Int = 1,
        cloudId: String? = null,
        syncStatus: Int = 1, // 1 = pending create in cloud
        verificationType: String = "WEBHOOK",
        verificationEndpoint: String? = null,
        verificationExpected: String? = null,
        isRecurring: Boolean = true
    ): Long {
        val cv = ContentValues().apply {
            put(COL_TASK_TITLE, title.trim())
            put(COL_TASK_DATE, dateStr)
            put(COL_TASK_IS_COMPLETED, 0)
            put(COL_TASK_PRIORITY, priority)
            put(COL_TASK_CREATED_AT, System.currentTimeMillis())
            put(COL_TASK_CLOUD_ID, cloudId)
            put(COL_TASK_SYNC_STATUS, syncStatus)
            put(COL_TASK_VERIFICATION_TYPE, verificationType)
            put(COL_TASK_VERIFICATION_ENDPOINT, verificationEndpoint?.trim())
            put(COL_TASK_VERIFICATION_EXPECTED, verificationExpected?.trim())
            put(COL_TASK_IS_RECURRING, if (isRecurring) 1 else 0)
        }
        return writableDatabase.insert(TABLE_DAILY_TASKS, null, cv)
    }

    private fun ensureDailyTasksForDate(db: SQLiteDatabase, dateStr: String) {
        val existingTitles = mutableSetOf<String>()
        val cursor = db.query(
            TABLE_DAILY_TASKS,
            arrayOf(COL_TASK_TITLE),
            "$COL_TASK_DATE = ?",
            arrayOf(dateStr),
            null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                val t = it.getString(0)
                if (t != null) existingTitles.add(t.trim().lowercase())
            }
        }

        val recurringCursor = db.query(
            true,
            TABLE_DAILY_TASKS,
            arrayOf(
                COL_TASK_TITLE,
                COL_TASK_PRIORITY,
                COL_TASK_VERIFICATION_TYPE,
                COL_TASK_VERIFICATION_ENDPOINT,
                COL_TASK_VERIFICATION_EXPECTED
            ),
            "$COL_TASK_IS_RECURRING = 1",
            null, null, null,
            "$COL_TASK_ID DESC",
            null
        )
        val toInsert = mutableListOf<ContentValues>()
        recurringCursor.use {
            while (it.moveToNext()) {
                val title = it.getString(0) ?: continue
                val lower = title.trim().lowercase()
                if (!existingTitles.contains(lower)) {
                    existingTitles.add(lower)
                    val cv = ContentValues().apply {
                        put(COL_TASK_TITLE, title)
                        put(COL_TASK_DATE, dateStr)
                        put(COL_TASK_IS_COMPLETED, 0)
                        put(COL_TASK_PRIORITY, it.getInt(1))
                        put(COL_TASK_CREATED_AT, System.currentTimeMillis())
                        put(COL_TASK_VERIFICATION_TYPE, it.getString(2) ?: "MANUAL")
                        put(COL_TASK_VERIFICATION_ENDPOINT, it.getString(3))
                        put(COL_TASK_VERIFICATION_EXPECTED, it.getString(4))
                        put(COL_TASK_VERIFICATION_STATUS, null as String?)
                        put(COL_TASK_LAST_VERIFIED_AT, null as Long?)
                        put(COL_TASK_IS_RECURRING, 1)
                        put(COL_TASK_SYNC_STATUS, 0)
                    }
                    toInsert.add(cv)
                }
            }
        }
        for (cv in toInsert) {
            db.insert(TABLE_DAILY_TASKS, null, cv)
        }
    }

    fun getDailyTasksForDate(dateStr: String): List<DailyTaskItem> {
        val db = writableDatabase
        ensureDailyTasksForDate(db, dateStr)

        val list = mutableListOf<DailyTaskItem>()
        val cursor = db.query(
            TABLE_DAILY_TASKS,
            arrayOf(
                COL_TASK_ID,
                COL_TASK_TITLE,
                COL_TASK_IS_COMPLETED,
                COL_TASK_VERIFICATION_TYPE,
                COL_TASK_VERIFICATION_ENDPOINT,
                COL_TASK_VERIFICATION_EXPECTED,
                COL_TASK_VERIFICATION_STATUS,
                COL_TASK_LAST_VERIFIED_AT,
                COL_TASK_IS_RECURRING
            ),
            "$COL_TASK_DATE = ?",
            arrayOf(dateStr),
            null, null,
            "$COL_TASK_IS_COMPLETED ASC, $COL_TASK_PRIORITY DESC, $COL_TASK_ID ASC"
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_TASK_ID)
            val titleIdx = it.getColumnIndexOrThrow(COL_TASK_TITLE)
            val compIdx = it.getColumnIndexOrThrow(COL_TASK_IS_COMPLETED)
            val vTypeIdx = it.getColumnIndexOrThrow(COL_TASK_VERIFICATION_TYPE)
            val vEndpointIdx = it.getColumnIndexOrThrow(COL_TASK_VERIFICATION_ENDPOINT)
            val vExpectedIdx = it.getColumnIndexOrThrow(COL_TASK_VERIFICATION_EXPECTED)
            val vStatusIdx = it.getColumnIndexOrThrow(COL_TASK_VERIFICATION_STATUS)
            val vLastVerifiedIdx = it.getColumnIndexOrThrow(COL_TASK_LAST_VERIFIED_AT)
            val vRecurringIdx = it.getColumnIndexOrThrow(COL_TASK_IS_RECURRING)

            while (it.moveToNext()) {
                val id = it.getLong(idIdx)
                val title = it.getString(titleIdx)
                val isCompleted = it.getInt(compIdx) == 1
                val vTypeStr = it.getString(vTypeIdx)
                val vType = if (vTypeStr.isNullOrBlank() || vTypeStr == "MANUAL") {
                    MissionVerificationType.WEBHOOK
                } else {
                    try {
                        MissionVerificationType.valueOf(vTypeStr)
                    } catch (_: Exception) {
                        MissionVerificationType.WEBHOOK
                    }
                }
                val vEndpoint = it.getString(vEndpointIdx)
                val vExpected = it.getString(vExpectedIdx)
                val vStatus = it.getString(vStatusIdx)
                val lastVerifiedAt = if (it.isNull(vLastVerifiedIdx)) null else it.getLong(vLastVerifiedIdx)
                val isRecurring = it.getInt(vRecurringIdx) == 1

                list.add(
                    DailyTaskItem(
                        id = id,
                        title = title,
                        isCompleted = isCompleted,
                        date = dateStr,
                        verificationType = vType,
                        verificationEndpoint = vEndpoint,
                        verificationExpectedResponse = vExpected,
                        verificationStatus = vStatus,
                        lastVerifiedAt = lastVerifiedAt,
                        isRecurringEveryday = isRecurring
                    )
                )
            }
        }
        return list
    }

    fun updateDailyTaskVerification(
        taskId: Long,
        verificationType: String,
        endpoint: String?,
        expectedResponse: String?
    ): Boolean {
        val cv = ContentValues().apply {
            put(COL_TASK_VERIFICATION_TYPE, verificationType)
            put(COL_TASK_VERIFICATION_ENDPOINT, endpoint?.trim())
            put(COL_TASK_VERIFICATION_EXPECTED, expectedResponse?.trim())
        }
        return writableDatabase.update(TABLE_DAILY_TASKS, cv, "$COL_TASK_ID = ?", arrayOf(taskId.toString())) > 0
    }

    fun setDailyTaskVerificationStatus(
        taskId: Long,
        status: String?,
        verifiedAt: Long? = System.currentTimeMillis()
    ): Boolean {
        val cv = ContentValues().apply {
            put(COL_TASK_VERIFICATION_STATUS, status)
            if (verifiedAt != null) {
                put(COL_TASK_LAST_VERIFIED_AT, verifiedAt)
            }
        }
        return writableDatabase.update(TABLE_DAILY_TASKS, cv, "$COL_TASK_ID = ?", arrayOf(taskId.toString())) > 0
    }

    fun setDailyTaskCompletion(taskId: Long, completed: Boolean) {
        val cv = ContentValues().apply {
            put(COL_TASK_IS_COMPLETED, if (completed) 1 else 0)
            put(COL_TASK_SYNC_STATUS, 2) // 2 = pending update in cloud
        }
        writableDatabase.update(TABLE_DAILY_TASKS, cv, "$COL_TASK_ID = ?", arrayOf(taskId.toString()))
    }

    fun deleteDailyTask(taskId: Long): Boolean {
        return writableDatabase.delete(TABLE_DAILY_TASKS, "$COL_TASK_ID = ?", arrayOf(taskId.toString())) > 0
    }

    fun upsertTaskFromCloud(cloudId: String, title: String, isCompleted: Boolean, dateStr: String): Long {
        val db = writableDatabase
        val cursor = db.query(
            TABLE_DAILY_TASKS,
            arrayOf(COL_TASK_ID, COL_TASK_SYNC_STATUS),
            "$COL_TASK_CLOUD_ID = ?",
            arrayOf(cloudId),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                val localId = it.getLong(it.getColumnIndexOrThrow(COL_TASK_ID))
                val localSyncStatus = it.getInt(it.getColumnIndexOrThrow(COL_TASK_SYNC_STATUS))
                // Only overwrite from cloud if local task has no pending changes
                if (localSyncStatus == 0) {
                    val cv = ContentValues().apply {
                        put(COL_TASK_TITLE, title.trim())
                        put(COL_TASK_IS_COMPLETED, if (isCompleted) 1 else 0)
                        put(COL_TASK_DATE, dateStr)
                    }
                    db.update(TABLE_DAILY_TASKS, cv, "$COL_TASK_ID = ?", arrayOf(localId.toString()))
                }
                return localId
            }
        }

        // Insert new task from cloud (marked as in-sync: syncStatus = 0)
        return insertDailyTask(title, dateStr, priority = 1, cloudId = cloudId, syncStatus = 0)
    }

    fun markTaskSynced(localId: Long, cloudId: String) {
        val cv = ContentValues().apply {
            put(COL_TASK_CLOUD_ID, cloudId)
            put(COL_TASK_SYNC_STATUS, 0)
        }
        writableDatabase.update(TABLE_DAILY_TASKS, cv, "$COL_TASK_ID = ?", arrayOf(localId.toString()))
    }

    fun getPendingCloudTasks(): List<CloudSyncTaskItem> {
        val list = mutableListOf<CloudSyncTaskItem>()
        val cursor = readableDatabase.query(
            TABLE_DAILY_TASKS,
            arrayOf(COL_TASK_ID, COL_TASK_CLOUD_ID, COL_TASK_TITLE, COL_TASK_IS_COMPLETED, COL_TASK_DATE, COL_TASK_SYNC_STATUS),
            "$COL_TASK_SYNC_STATUS > 0",
            null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COL_TASK_ID))
                val cloudId = it.getString(it.getColumnIndexOrThrow(COL_TASK_CLOUD_ID))
                val title = it.getString(it.getColumnIndexOrThrow(COL_TASK_TITLE))
                val isCompleted = it.getInt(it.getColumnIndexOrThrow(COL_TASK_IS_COMPLETED)) == 1
                val dateStr = it.getString(it.getColumnIndexOrThrow(COL_TASK_DATE))
                val syncStatus = it.getInt(it.getColumnIndexOrThrow(COL_TASK_SYNC_STATUS))
                list.add(CloudSyncTaskItem(id, cloudId, title, isCompleted, dateStr, syncStatus))
            }
        }
        return list
    }


    // ==================== Casual To-Do Tasks Operations ====================

    fun insertTodoTask(title: String): Long {
        val cv = ContentValues().apply {
            put(COL_TODO_TITLE, title.trim())
            put(COL_TODO_IS_COMPLETED, 0)
            put(COL_TODO_CREATED_AT, System.currentTimeMillis())
        }
        return writableDatabase.insert(TABLE_TODO_TASKS, null, cv)
    }

    fun getAllTodoTasks(): List<com.personal.disciplinelock.domain.task.TodoTaskItem> {
        val list = mutableListOf<com.personal.disciplinelock.domain.task.TodoTaskItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TODO_TASKS,
            null,
            null,
            null,
            null,
            null,
            "$COL_TODO_IS_COMPLETED ASC, $COL_TODO_CREATED_AT DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(COL_TODO_ID))
                val title = c.getString(c.getColumnIndexOrThrow(COL_TODO_TITLE))
                val isCompleted = c.getInt(c.getColumnIndexOrThrow(COL_TODO_IS_COMPLETED)) == 1
                val createdAt = c.getLong(c.getColumnIndexOrThrow(COL_TODO_CREATED_AT))
                list.add(com.personal.disciplinelock.domain.task.TodoTaskItem(id, title, isCompleted, createdAt))
            }
        }
        return list
    }

    fun toggleTodoTask(id: Long) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE $TABLE_TODO_TASKS SET $COL_TODO_IS_COMPLETED = CASE WHEN $COL_TODO_IS_COMPLETED = 1 THEN 0 ELSE 1 END WHERE $COL_TODO_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun deleteTodoTask(id: Long): Boolean {
        return writableDatabase.delete(TABLE_TODO_TASKS, "$COL_TODO_ID = ?", arrayOf(id.toString())) > 0
    }

    fun clearCompletedTodoTasks(): Int {
        return writableDatabase.delete(TABLE_TODO_TASKS, "$COL_TODO_IS_COMPLETED = 1", null)
    }

    // ==================== Overall Discipline Days & Streak Operations ====================

    fun recordDayCompletion(date: String, totalMissions: Int = 0): Boolean {
        val cv = ContentValues().apply {
            put(COL_DISC_DATE, date)
            put(COL_DISC_COMPLETED_AT, System.currentTimeMillis())
            put(COL_DISC_TOTAL_MISSIONS, totalMissions)
        }
        val res = writableDatabase.insertWithOnConflict(
            TABLE_DISCIPLINE_DAYS,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return res != -1L
    }

    fun isDayFullyCompleted(date: String): Boolean {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DISCIPLINE_DAYS,
            arrayOf(COL_DISC_DATE),
            "$COL_DISC_DATE = ?",
            arrayOf(date),
            null,
            null,
            null
        )
        cursor.use {
            return it.count > 0
        }
    }

    fun calculateOverallStreak(todayStr: String): Int {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        try {
            cal.time = sdf.parse(todayStr) ?: Date()
        } catch (e: Exception) {
            return 0
        }

        var streak = 0
        if (isDayFullyCompleted(todayStr)) {
            streak++
        }

        cal.add(Calendar.DAY_OF_YEAR, -1)
        while (true) {
            val checkDate = sdf.format(cal.time)
            if (isDayFullyCompleted(checkDate)) {
                streak++
                cal.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }
        return streak
    }

    fun getLongestStreak(): Int {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DISCIPLINE_DAYS,
            arrayOf(COL_DISC_DATE),
            null,
            null,
            null,
            null,
            "$COL_DISC_DATE ASC"
        )
        val dates = mutableListOf<String>()
        cursor.use {
            val idx = it.getColumnIndexOrThrow(COL_DISC_DATE)
            while (it.moveToNext()) {
                dates.add(it.getString(idx))
            }
        }
        if (dates.isEmpty()) return 0

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        var maxStreak = 1
        var currentStreak = 1

        for (i in 1 until dates.size) {
            try {
                val prevCal = Calendar.getInstance().apply { time = sdf.parse(dates[i - 1]) ?: Date() }
                prevCal.add(Calendar.DAY_OF_YEAR, 1)
                val expectedNext = sdf.format(prevCal.time)
                if (expectedNext == dates[i]) {
                    currentStreak++
                    if (currentStreak > maxStreak) {
                        maxStreak = currentStreak
                    }
                } else if (dates[i - 1] != dates[i]) {
                    currentStreak = 1
                }
            } catch (_: Exception) {
                currentStreak = 1
            }
        }
        return maxStreak
    }

    fun getAllCompletedDates(): List<String> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DISCIPLINE_DAYS,
            arrayOf(COL_DISC_DATE),
            null,
            null,
            null,
            null,
            "$COL_DISC_DATE DESC"
        )
        val list = mutableListOf<String>()
        cursor.use {
            val idx = it.getColumnIndexOrThrow(COL_DISC_DATE)
            while (it.moveToNext()) {
                list.add(it.getString(idx))
            }
        }
        return list
    }
}

data class CloudSyncTaskItem(
    val localId: Long,
    val cloudId: String?,
    val title: String,
    val isCompleted: Boolean,
    val dateStr: String,
    val syncStatus: Int
)

data class HabitRecord(
    val id: Long,
    val title: String,
    val category: String,
    val verificationType: String = "MANUAL",
    val verificationEndpoint: String? = null,
    val verificationExpected: String? = null,
    val verificationStatus: String? = null
)

