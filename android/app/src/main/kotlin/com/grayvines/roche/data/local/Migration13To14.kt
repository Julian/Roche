@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room v13 → v14 schema upgrade: Creates the `boards`, `board_items`, and `board_item_states`
 * tables with cascading foreign keys, indices, and `table_revisions` triggers.
 */
internal fun migrateSchemaV13ToV14(connection: SQLiteConnection) {
    createBoardsTableV14(connection)
    createBoardItemsTableV14(connection)
    createBoardItemStatesTableV14(connection)
    installTableRevisionTriggers(connection)
}

private fun createBoardsTableV14(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `boards` (" +
            "`id` TEXT NOT NULL, " +
            "`adapterType` TEXT NOT NULL, " +
            "`sourceKey` TEXT NOT NULL, " +
            "`sourceUrl` TEXT NOT NULL, " +
            "`sourceConfigJson` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`orderMode` TEXT NOT NULL, " +
            "`pollInterval` INTEGER NOT NULL, " +
            "`isActive` INTEGER NOT NULL, " +
            "`lastPolledAt` TEXT, " +
            "`lastPolledBy` TEXT, " +
            "`lastError` TEXT, " +
            "`lastErrorBy` TEXT, " +
            "`consecutiveFailures` INTEGER NOT NULL, " +
            "`snapshotFetchedAt` TEXT, " +
            "`snapshotHlc` TEXT NOT NULL, " +
            "`createdAt` TEXT NOT NULL, " +
            "`updatedAt` TEXT NOT NULL, " +
            "`hlc` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "`isSnapshotDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"
    )
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_boards_adapterType_sourceKey` " +
            "ON `boards` (`adapterType`, `sourceKey`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_boards_isActive` ON `boards` (`isActive`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_boards_isLocalDirty` ON `boards` (`isLocalDirty`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_boards_isSnapshotDirty` ON `boards` (`isSnapshotDirty`)"
    )
}

private fun createBoardItemsTableV14(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `board_items` (" +
            "`boardId` TEXT NOT NULL, " +
            "`externalId` TEXT NOT NULL, " +
            "`rank` INTEGER NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`url` TEXT NOT NULL, " +
            "`discussionUrl` TEXT, " +
            "`author` TEXT, " +
            "`domain` TEXT, " +
            "`publishedAt` TEXT, " +
            "`score` INTEGER, " +
            "`commentCount` INTEGER, " +
            "`summary` TEXT, " +
            "`selfTextHtml` TEXT, " +
            "`metadataJson` TEXT NOT NULL, " +
            "`fetchedAt` TEXT NOT NULL, " +
            "PRIMARY KEY(`boardId`, `externalId`), " +
            "FOREIGN KEY(`boardId`) REFERENCES `boards`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_board_items_boardId_rank` " +
            "ON `board_items` (`boardId`, `rank`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_board_items_url` ON `board_items` (`url`)"
    )
}

private fun createBoardItemStatesTableV14(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `board_item_states` (" +
            "`boardId` TEXT NOT NULL, " +
            "`externalId` TEXT NOT NULL, " +
            "`seenAt` TEXT, " +
            "`dismissedAt` TEXT, " +
            "`lastSeenInSnapshotAt` TEXT NOT NULL, " +
            "`updatedAt` TEXT NOT NULL, " +
            "`hlc` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`boardId`, `externalId`), " +
            "FOREIGN KEY(`boardId`) REFERENCES `boards`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_board_item_states_lastSeenInSnapshotAt` " +
            "ON `board_item_states` (`lastSeenInSnapshotAt`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_board_item_states_isLocalDirty` " +
            "ON `board_item_states` (`isLocalDirty`)"
    )
}
