package com.example.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.SharedFile
import com.example.model.SharedText

@Database(
    entities = [SharedFile::class, SharedText::class],
    version = 1,
    exportSchema = false
)
abstract class LocalShareDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun textDao(): TextDao

    companion object {
        @Volatile
        private var INSTANCE: LocalShareDatabase? = null

        fun getDatabase(context: Context): LocalShareDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocalShareDatabase::class.java,
                    "localshare.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
