@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room v15 → v16 schema upgrade: Replaces redundant single-column indices with composite covering
 * indices on `entries`, `entry_identifiers`, `collection_entries`, `comparisons`, and adds
 * `(status, entryId)` on `consumption_log` to support SQL-backed catalog overview queries,
 * on-demand collection pagination, indexed live inbox loading, and scoped board deduplication
 * without full-table scans.
 */
internal fun migrateSchemaV15ToV16(connection: SQLiteConnection) {
    connection.execSQL("DROP INDEX IF EXISTS `index_entries_kind`")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_entries_kind_mergedIntoId` " +
            "ON `entries` (`kind`, `mergedIntoId`)"
    )
    connection.execSQL("DROP INDEX IF EXISTS `index_entry_identifiers_entryId`")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_entry_identifiers_entryId_scheme_value` " +
            "ON `entry_identifiers` (`entryId`, `scheme`, `value`)"
    )
    connection.execSQL("DROP INDEX IF EXISTS `index_collection_entries_entryId`")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_collection_entries_entryId_addedAt_collectionId` " +
            "ON `collection_entries` (`entryId`, `addedAt`, `collectionId`)"
    )
    connection.execSQL("DROP INDEX IF EXISTS `index_comparisons_scopeCollectionId`")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_comparisons_scopeCollectionId_ts` " +
            "ON `comparisons` (`scopeCollectionId`, `ts`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_status_entryId` " +
            "ON `consumption_log` (`status`, `entryId`)"
    )
}
