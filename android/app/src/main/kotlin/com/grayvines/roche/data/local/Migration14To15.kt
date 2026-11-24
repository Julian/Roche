@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room v14 → v15 schema upgrade: Adds composite indices on `consumption_log` to support
 * index-covered distinct status summaries, per-entry log history, and paginated Diary queries
 * without loading the full `consumption_log` table into memory.
 */
internal fun migrateSchemaV14ToV15(connection: SQLiteConnection) {
    connection.execSQL(
        "UPDATE `consumption_log` SET `status` = LOWER(`status`) WHERE `status` != LOWER(`status`)"
    )
    connection.execSQL("DROP INDEX IF EXISTS `index_consumption_log_entryId`")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_entryId_status` " +
            "ON `consumption_log` (`entryId`, `status`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_entryId_ts` " +
            "ON `consumption_log` (`entryId`, `ts`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_status_ts` " +
            "ON `consumption_log` (`status`, `ts`)"
    )
}
