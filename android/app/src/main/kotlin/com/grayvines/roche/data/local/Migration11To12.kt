@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val REVISION_TRACKED_TABLES: List<String> =
    listOf(
        "entries",
        "entry_identifiers",
        "collection_specs",
        "collection_entries",
        "collection_generators",
        "consumption_log",
        "comparisons",
        "entry_state",
        "entry_content",
        "feed_sources",
    )

/**
 * Room v11 → v12 schema upgrade:
 * 1. Runs a defensive orphan-cleanup and duplicate-slug deduplication pass so pre-v12 sync drifts
 *    never fail foreign-key or uniqueness constraints.
 * 2. Rebuilds child tables (`entry_identifiers`, `feed_sources`, `collection_specs`,
 *    `collection_entries`, `collection_generators`, `consumption_log`, `comparisons`) with foreign
 *    keys referencing `entries(id) ON DELETE CASCADE` plus `UNIQUE(slug)` on `collection_specs`.
 * 3. Creates `table_revisions` and installs O(1) revision-bumping triggers for all 10 observed
 *    tables, replacing full-table SQL checksum stamps.
 */
internal fun migrateSchemaV11ToV12(connection: SQLiteConnection) {
    // Runs safely inside Room's migration transaction even with PRAGMA foreign_keys = ON:
    // `entries` is never dropped, no v11 table references these 7 child tables, and
    // cleanOrphansAndSlugsV12 prunes orphaned/colliding rows before rebuilding.
    cleanOrphansAndSlugsV12(connection)
    rebuildEntryIdentifiersV12(connection)
    rebuildFeedSourcesV12(connection)
    rebuildCollectionSpecsV12(connection)
    rebuildCollectionEntriesV12(connection)
    rebuildCollectionGeneratorsV12(connection)
    rebuildConsumptionLogV12(connection)
    rebuildComparisonsV12(connection)
    setupTableRevisions(connection)
}

internal fun setupTableRevisions(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `table_revisions` (" +
            "`tableName` TEXT NOT NULL, " +
            "`revision` INTEGER NOT NULL, " +
            "PRIMARY KEY(`tableName`))"
    )
    installTableRevisionTriggers(connection)
}

internal fun installTableRevisionTriggers(connection: SQLiteConnection) {
    if (hasTableRevisionSetup(connection)) return
    for (table in REVISION_TRACKED_TABLES) {
        connection.execSQL(
            "INSERT OR IGNORE INTO `table_revisions` (`tableName`, `revision`) " +
                "VALUES ('$table', 0)"
        )
        val bumpSql =
            "INSERT INTO `table_revisions` (`tableName`, `revision`) VALUES ('$table', 1) " +
                "ON CONFLICT(`tableName`) DO UPDATE SET `revision` = `revision` + 1"
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `tr_rev_${table}_ai` " +
                "AFTER INSERT ON `$table` BEGIN $bumpSql; END"
        )
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `tr_rev_${table}_au` " +
                "AFTER UPDATE ON `$table` BEGIN $bumpSql; END"
        )
        connection.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `tr_rev_${table}_ad` " +
                "AFTER DELETE ON `$table` BEGIN $bumpSql; END"
        )
    }
}

private fun hasTableRevisionSetup(connection: SQLiteConnection): Boolean {
    val expectedTriggers = REVISION_TRACKED_TABLES.size * 3L
    val expectedRows = REVISION_TRACKED_TABLES.size.toLong()
    val triggerCount =
        connection
            .prepare(
                "SELECT COUNT(*) FROM `sqlite_master` " +
                    "WHERE `type` = 'trigger' AND `name` LIKE 'tr_rev_%'"
            )
            .use { stmt -> if (stmt.step()) stmt.getLong(0) else 0L }
    if (triggerCount < expectedTriggers) return false
    val rowCount =
        connection.prepare("SELECT COUNT(*) FROM `table_revisions`").use { stmt ->
            if (stmt.step()) stmt.getLong(0) else 0L
        }
    return rowCount >= expectedRows
}

private fun cleanOrphansAndSlugsV12(connection: SQLiteConnection) {
    connection.execSQL(
        "DELETE FROM `entry_identifiers` WHERE `entryId` NOT IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL(
        "DELETE FROM `feed_sources` WHERE `collectionId` NOT IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL(
        "DELETE FROM `collection_specs` WHERE `collectionId` NOT IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL(
        "UPDATE `collection_specs` SET `slug` = NULL " +
            "WHERE `slug` IS NOT NULL AND TRIM(`slug`) = ''"
    )
    // Preserve one canonical row per duplicate slug (preferring non-merged entries, then earliest
    // createdAt / collectionId) and clear colliding slugs to NULL rather than deleting collections.
    connection.execSQL(
        "UPDATE `collection_specs` SET `slug` = NULL " +
            "WHERE `slug` IS NOT NULL AND `collectionId` NOT IN (" +
            "  SELECT cs1.`collectionId` FROM `collection_specs` cs1 " +
            "  INNER JOIN `entries` e1 ON e1.`id` = cs1.`collectionId` " +
            "  WHERE cs1.`slug` IS NOT NULL AND NOT EXISTS (" +
            "    SELECT 1 FROM `collection_specs` cs2 " +
            "    INNER JOIN `entries` e2 ON e2.`id` = cs2.`collectionId` " +
            "    WHERE LOWER(TRIM(cs2.`slug`)) = LOWER(TRIM(cs1.`slug`)) AND (" +
            "      (e2.`mergedIntoId` IS NULL AND e1.`mergedIntoId` IS NOT NULL) " +
            "      OR ((e2.`mergedIntoId` IS NULL) = (e1.`mergedIntoId` IS NULL) " +
            "          AND (e2.`createdAt` < e1.`createdAt` " +
            "               OR (e2.`createdAt` = e1.`createdAt` " +
            "                   AND cs2.`collectionId` < cs1.`collectionId`)))" +
            "    )" +
            "  )" +
            ")"
    )
    connection.execSQL(
        "DELETE FROM `collection_entries` " +
            "WHERE `collectionId` NOT IN (SELECT `id` FROM `entries`) " +
            "OR `entryId` NOT IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL(
        "DELETE FROM `collection_generators` " +
            "WHERE `collectionId` NOT IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL(
        "DELETE FROM `consumption_log` WHERE `entryId` NOT IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL(
        "DELETE FROM `comparisons` " +
            "WHERE `scopeCollectionId` IS NOT NULL " +
            "AND `scopeCollectionId` NOT IN (SELECT `id` FROM `entries`)"
    )
}

private fun rebuildEntryIdentifiersV12(connection: SQLiteConnection) {
    connection.execSQL("DROP TABLE IF EXISTS `entry_identifiers_v12`")
    connection.execSQL(
        "CREATE TABLE `entry_identifiers_v12` (" +
            "`scheme` TEXT NOT NULL, " +
            "`value` TEXT NOT NULL, " +
            "`entryId` TEXT NOT NULL, " +
            "PRIMARY KEY(`scheme`, `value`), " +
            "FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "INSERT INTO `entry_identifiers_v12` (`scheme`, `value`, `entryId`) " +
            "SELECT `scheme`, `value`, `entryId` FROM `entry_identifiers` " +
            "WHERE `entryId` IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL("DROP TABLE `entry_identifiers`")
    connection.execSQL("ALTER TABLE `entry_identifiers_v12` RENAME TO `entry_identifiers`")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_entry_identifiers_entryId` " +
            "ON `entry_identifiers` (`entryId`)"
    )
}

private fun rebuildFeedSourcesV12(connection: SQLiteConnection) {
    connection.execSQL("DROP TABLE IF EXISTS `feed_sources_v12`")
    connection.execSQL(
        "CREATE TABLE `feed_sources_v12` (" +
            "`collectionId` TEXT NOT NULL, " +
            "`feedUrl` TEXT NOT NULL, " +
            "`siteUrl` TEXT, " +
            "`sourceType` TEXT NOT NULL, " +
            "`feedTitle` TEXT, " +
            "`language` TEXT, " +
            "`pollInterval` INTEGER NOT NULL, " +
            "`isActive` INTEGER NOT NULL, " +
            "`lastPolledAt` TEXT, " +
            "`lastPolledBy` TEXT, " +
            "`lastError` TEXT, " +
            "`lastErrorBy` TEXT, " +
            "`consecutiveFailures` INTEGER NOT NULL, " +
            "`autoDownloadEpisodes` INTEGER NOT NULL, " +
            "`customPlaybackSpeed` REAL, " +
            "`hlc` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`collectionId`), " +
            "FOREIGN KEY(`collectionId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "INSERT INTO `feed_sources_v12` (" +
            "`collectionId`, `feedUrl`, `siteUrl`, `sourceType`, `feedTitle`, `language`, " +
            "`pollInterval`, `isActive`, `lastPolledAt`, `lastPolledBy`, `lastError`, " +
            "`lastErrorBy`, `consecutiveFailures`, `autoDownloadEpisodes`, " +
            "`customPlaybackSpeed`, `hlc`, `isLocalDirty`) " +
            "SELECT `collectionId`, `feedUrl`, `siteUrl`, `sourceType`, `feedTitle`, `language`, " +
            "`pollInterval`, `isActive`, `lastPolledAt`, `lastPolledBy`, `lastError`, " +
            "`lastErrorBy`, `consecutiveFailures`, `autoDownloadEpisodes`, " +
            "`customPlaybackSpeed`, `hlc`, `isLocalDirty` FROM `feed_sources` " +
            "WHERE `collectionId` IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL("DROP TABLE `feed_sources`")
    connection.execSQL("ALTER TABLE `feed_sources_v12` RENAME TO `feed_sources`")
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_feed_sources_feedUrl` " +
            "ON `feed_sources` (`feedUrl`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_feed_sources_isLocalDirty` " +
            "ON `feed_sources` (`isLocalDirty`)"
    )
}

private fun rebuildCollectionSpecsV12(connection: SQLiteConnection) {
    connection.execSQL("DROP TABLE IF EXISTS `collection_specs_v12`")
    connection.execSQL(
        "CREATE TABLE `collection_specs_v12` (" +
            "`collectionId` TEXT NOT NULL, " +
            "`slug` TEXT, " +
            "`mode` TEXT NOT NULL, " +
            "`role` TEXT NOT NULL, " +
            "`ruleJson` TEXT, " +
            "`showInLists` INTEGER NOT NULL, " +
            "`color` TEXT, " +
            "PRIMARY KEY(`collectionId`), " +
            "FOREIGN KEY(`collectionId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "INSERT INTO `collection_specs_v12` (" +
            "`collectionId`, `slug`, `mode`, `role`, `ruleJson`, `showInLists`, `color`) " +
            "SELECT `collectionId`, `slug`, `mode`, `role`, `ruleJson`, `showInLists`, `color` " +
            "FROM `collection_specs` WHERE `collectionId` IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL("DROP TABLE `collection_specs`")
    connection.execSQL("ALTER TABLE `collection_specs_v12` RENAME TO `collection_specs`")
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_collection_specs_slug` " +
            "ON `collection_specs` (`slug`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_collection_specs_role` " +
            "ON `collection_specs` (`role`)"
    )
}

private fun rebuildCollectionEntriesV12(connection: SQLiteConnection) {
    connection.execSQL("DROP TABLE IF EXISTS `collection_entries_v12`")
    connection.execSQL(
        "CREATE TABLE `collection_entries_v12` (" +
            "`collectionId` TEXT NOT NULL, " +
            "`entryId` TEXT NOT NULL, " +
            "`position` REAL, " +
            "`addedAt` TEXT NOT NULL, " +
            "`hlc` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`collectionId`, `entryId`), " +
            "FOREIGN KEY(`collectionId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE, " +
            "FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "INSERT INTO `collection_entries_v12` (" +
            "`collectionId`, `entryId`, `position`, `addedAt`, `hlc`, `isLocalDirty`) " +
            "SELECT `collectionId`, `entryId`, `position`, `addedAt`, `hlc`, `isLocalDirty` " +
            "FROM `collection_entries` " +
            "WHERE `collectionId` IN (SELECT `id` FROM `entries`) " +
            "AND `entryId` IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL("DROP TABLE `collection_entries`")
    connection.execSQL("ALTER TABLE `collection_entries_v12` RENAME TO `collection_entries`")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_collection_entries_entryId` " +
            "ON `collection_entries` (`entryId`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_collection_entries_addedAt` " +
            "ON `collection_entries` (`addedAt`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_collection_entries_isLocalDirty` " +
            "ON `collection_entries` (`isLocalDirty`)"
    )
}

private fun rebuildCollectionGeneratorsV12(connection: SQLiteConnection) {
    connection.execSQL("DROP TABLE IF EXISTS `collection_generators_v12`")
    connection.execSQL(
        "CREATE TABLE `collection_generators_v12` (" +
            "`collectionId` TEXT NOT NULL, " +
            "`enabled` INTEGER NOT NULL, " +
            "`currentEntryId` TEXT, " +
            "`drawnAt` TEXT, " +
            "`updatedAt` TEXT NOT NULL, " +
            "`hlc` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`collectionId`), " +
            "FOREIGN KEY(`collectionId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "INSERT INTO `collection_generators_v12` (" +
            "`collectionId`, `enabled`, `currentEntryId`, `drawnAt`, `updatedAt`, `hlc`, " +
            "`isLocalDirty`) " +
            "SELECT `collectionId`, `enabled`, `currentEntryId`, `drawnAt`, `updatedAt`, " +
            "`hlc`, `isLocalDirty` FROM `collection_generators` " +
            "WHERE `collectionId` IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL("DROP TABLE `collection_generators`")
    connection.execSQL("ALTER TABLE `collection_generators_v12` RENAME TO `collection_generators`")
}

private fun rebuildConsumptionLogV12(connection: SQLiteConnection) {
    connection.execSQL("DROP TABLE IF EXISTS `consumption_log_v12`")
    connection.execSQL(
        "CREATE TABLE `consumption_log_v12` (" +
            "`id` TEXT NOT NULL, " +
            "`entryId` TEXT NOT NULL, " +
            "`ts` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, " +
            "`notes` TEXT, " +
            "`createdAt` TEXT NOT NULL, " +
            "`hlc` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "INSERT INTO `consumption_log_v12` (" +
            "`id`, `entryId`, `ts`, `status`, `notes`, `createdAt`, `hlc`, `isLocalDirty`) " +
            "SELECT `id`, `entryId`, `ts`, `status`, `notes`, `createdAt`, `hlc`, `isLocalDirty` " +
            "FROM `consumption_log` WHERE `entryId` IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL("DROP TABLE `consumption_log`")
    connection.execSQL("ALTER TABLE `consumption_log_v12` RENAME TO `consumption_log`")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_entryId` " +
            "ON `consumption_log` (`entryId`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_ts` " + "ON `consumption_log` (`ts`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_createdAt` " +
            "ON `consumption_log` (`createdAt`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_isLocalDirty` " +
            "ON `consumption_log` (`isLocalDirty`)"
    )
}

private fun rebuildComparisonsV12(connection: SQLiteConnection) {
    connection.execSQL("DROP TABLE IF EXISTS `comparisons_v12`")
    connection.execSQL(
        "CREATE TABLE `comparisons_v12` (" +
            "`id` TEXT NOT NULL, " +
            "`ts` TEXT NOT NULL, " +
            "`scopeCollectionId` TEXT, " +
            "`orderingJson` TEXT NOT NULL, " +
            "`createdAt` TEXT NOT NULL, " +
            "`hlc` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`scopeCollectionId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "INSERT INTO `comparisons_v12` (" +
            "`id`, `ts`, `scopeCollectionId`, `orderingJson`, `createdAt`, `hlc`, " +
            "`isLocalDirty`) " +
            "SELECT `id`, `ts`, `scopeCollectionId`, `orderingJson`, `createdAt`, `hlc`, " +
            "`isLocalDirty` FROM `comparisons` " +
            "WHERE `scopeCollectionId` IS NULL " +
            "OR `scopeCollectionId` IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL("DROP TABLE `comparisons`")
    connection.execSQL("ALTER TABLE `comparisons_v12` RENAME TO `comparisons`")
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_comparisons_scopeCollectionId` " +
            "ON `comparisons` (`scopeCollectionId`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_comparisons_ts` " + "ON `comparisons` (`ts`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_comparisons_createdAt` " +
            "ON `comparisons` (`createdAt`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_comparisons_isLocalDirty` " +
            "ON `comparisons` (`isLocalDirty`)"
    )
}
