package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.repository.KeyValueStore

/** Opens the app database (same file and migrations as before the shared module). */
object AndroidDatabase {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    /** Adds the chat tables and keeps existing accounts and call history. */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_conversations` (" +
                    "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `isGroup` INTEGER NOT NULL, " +
                    "`members` TEXT NOT NULL, `lastMessage` TEXT NOT NULL, " +
                    "`lastTimestamp` INTEGER NOT NULL, `unreadCount` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` TEXT NOT NULL, " +
                    "`sender` TEXT NOT NULL, `senderName` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, `isOutgoing` INTEGER NOT NULL, `status` TEXT NOT NULL)"
            )
        }
    }

    fun getInstance(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                AppDatabase.FILE_NAME
            ).addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build().also {
                    INSTANCE = it
                }
        }
    }
}

/** [KeyValueStore] backed by SharedPreferences. */
class SharedPrefsStore(context: Context, name: String) : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}
