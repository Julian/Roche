package com.grayvines.roche.data.local

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL

@Database(
    entities =
        [
            FeedEntity::class,
            ItemEntity::class,
            ItemContentEntity::class,
            TagEntity::class,
            ItemTagEntity::class,
            PendingMutationEntity::class,
            TombstoneEntity::class,
        ],
    version = 6,
    exportSchema = true,
)
abstract class RocheDatabase : RoomDatabase() {
    abstract fun dao(): RocheDao

    companion object {
        private const val DATABASE_NAME = "roche.db"

        internal val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `pending_mutations` (" +
                            "`entityId` TEXT NOT NULL, " +
                            "`isLocalFeed` INTEGER NOT NULL, " +
                            "`isLocalItem` INTEGER NOT NULL, " +
                            "`titleOverride` TEXT, " +
                            "`dirtyStatus` TEXT, " +
                            "`dirtyFavorite` INTEGER, " +
                            "`dirtyReadProgress` REAL, " +
                            "`addedTagsCsv` TEXT NOT NULL, " +
                            "`removedTagsCsv` TEXT NOT NULL, " +
                            "PRIMARY KEY(`entityId`))"
                    )
                }
            }

        internal val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE `items` ADD COLUMN `readAt` TEXT")
                    connection.execSQL("ALTER TABLE `items` ADD COLUMN `thumbnailUrl` TEXT")
                    connection.execSQL(
                        "ALTER TABLE `pending_mutations` ADD COLUMN `dirtyReadAt` TEXT"
                    )
                }
            }

        internal val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `description` TEXT")
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `language` TEXT")
                    connection.execSQL(
                        "ALTER TABLE `feeds` ADD COLUMN `pollInterval` INTEGER NOT NULL DEFAULT 30"
                    )
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `lastPolled` TEXT")
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `lastPolledBy` TEXT")
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `lastError` TEXT")
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `lastErrorBy` TEXT")
                    connection.execSQL(
                        "ALTER TABLE `feeds` ADD COLUMN `isActive` INTEGER NOT NULL DEFAULT 1"
                    )
                    connection.execSQL("ALTER TABLE `items` ADD COLUMN `feedUrl` TEXT")
                    connection.execSQL("ALTER TABLE `items` ADD COLUMN `interactionUpdatedAt` TEXT")
                    connection.execSQL("ALTER TABLE `tags` ADD COLUMN `color` TEXT")
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_tombstones` (" +
                            "`entityType` TEXT NOT NULL, " +
                            "`naturalKey` TEXT NOT NULL, " +
                            "`deletedAt` TEXT NOT NULL, " +
                            "PRIMARY KEY(`entityType`, `naturalKey`))"
                    )
                }
            }

        internal val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `feeds` ADD COLUMN `autoDownloadEpisodes` INTEGER NOT NULL DEFAULT 0"
                    )
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `customPlaybackSpeed` REAL")
                    connection.execSQL("ALTER TABLE `items` ADD COLUMN `audioUrl` TEXT")
                }
            }

        internal val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `item_contents` (" +
                            "`itemId` TEXT NOT NULL, " +
                            "`summary` TEXT, " +
                            "`contentText` TEXT, " +
                            "`contentHtml` TEXT, " +
                            "PRIMARY KEY(`itemId`), " +
                            "FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE)"
                    )
                    connection.execSQL(
                        "INSERT OR REPLACE INTO `item_contents` " +
                            "(`itemId`, `summary`, `contentText`, `contentHtml`) " +
                            "SELECT `id`, `summary`, `contentText`, `contentHtml` FROM `items` " +
                            "WHERE LENGTH(`summary`) > $LIST_PREVIEW_SUMMARY_CHARS " +
                            "OR LENGTH(`contentText`) > $LIST_PREVIEW_TEXT_CHARS " +
                            "OR LENGTH(`contentHtml`) > $LIST_PREVIEW_HTML_CHARS"
                    )
                    connection.execSQL(
                        "UPDATE `items` SET " +
                            "`summary` = SUBSTR(`summary`, 1, $LIST_PREVIEW_SUMMARY_CHARS), " +
                            "`contentText` = SUBSTR(`contentText`, 1, $LIST_PREVIEW_TEXT_CHARS), " +
                            "`contentHtml` = SUBSTR(`contentHtml`, 1, $LIST_PREVIEW_HTML_CHARS) " +
                            "WHERE LENGTH(`summary`) > $LIST_PREVIEW_SUMMARY_CHARS " +
                            "OR LENGTH(`contentText`) > $LIST_PREVIEW_TEXT_CHARS " +
                            "OR LENGTH(`contentHtml`) > $LIST_PREVIEW_HTML_CHARS"
                    )
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_items_publishedAt_createdAt` " +
                            "ON `items` (`publishedAt`, `createdAt`)"
                    )
                }
            }

        fun create(context: Context): RocheDatabase =
            Room.databaseBuilder<RocheDatabase>(context.applicationContext, DATABASE_NAME)
                .setDriver(AndroidSQLiteDriver())
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
