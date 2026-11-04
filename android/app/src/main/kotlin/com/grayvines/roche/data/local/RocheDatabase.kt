package com.grayvines.roche.data.local

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

@Database(
    entities = [FeedEntity::class, ItemEntity::class, TagEntity::class, ItemTagEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RocheDatabase : RoomDatabase() {
    abstract fun dao(): RocheDao

    companion object {
        private const val DATABASE_NAME = "roche.db"

        fun create(context: Context): RocheDatabase =
            Room.databaseBuilder<RocheDatabase>(context.applicationContext, DATABASE_NAME)
                .setDriver(AndroidSQLiteDriver())
                .build()
    }
}
