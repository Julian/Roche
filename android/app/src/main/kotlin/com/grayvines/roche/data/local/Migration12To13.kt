@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room v12 → v13 schema upgrade: Drops the redundant `mode` and `showInLists` columns from
 * `collection_specs` so dynamic collections are uniquely identified by a non-blank `ruleJson` and
 * list visibility is derived directly from `role == 'list'`.
 */
internal fun migrateSchemaV12ToV13(connection: SQLiteConnection) {
    connection.execSQL("DROP TABLE IF EXISTS `collection_specs_v13`")
    connection.execSQL(
        "CREATE TABLE `collection_specs_v13` (" +
            "`collectionId` TEXT NOT NULL, " +
            "`slug` TEXT, " +
            "`role` TEXT NOT NULL, " +
            "`ruleJson` TEXT, " +
            "`color` TEXT, " +
            "PRIMARY KEY(`collectionId`), " +
            "FOREIGN KEY(`collectionId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "INSERT INTO `collection_specs_v13` (`collectionId`, `slug`, `role`, `ruleJson`, `color`) " +
            "SELECT `collectionId`, `slug`, `role`, " +
            "CASE WHEN `mode` = 'dynamic' THEN NULLIF(TRIM(`ruleJson`), '') ELSE NULL END, " +
            "`color` " +
            "FROM `collection_specs` WHERE `collectionId` IN (SELECT `id` FROM `entries`)"
    )
    connection.execSQL("DROP TABLE `collection_specs`")
    connection.execSQL("ALTER TABLE `collection_specs_v13` RENAME TO `collection_specs`")
    connection.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_collection_specs_slug` " +
            "ON `collection_specs` (`slug`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_collection_specs_role` " +
            "ON `collection_specs` (`role`)"
    )
    // Dropping `collection_specs` removes its `tr_rev_collection_specs_*` triggers; restore them.
    installTableRevisionTriggers(connection)
}
