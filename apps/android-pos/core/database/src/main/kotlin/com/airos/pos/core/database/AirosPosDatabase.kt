package com.airos.pos.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.airos.pos.core.database.dao.AttendanceDao
import com.airos.pos.core.database.dao.BackendMenuCacheDao
import com.airos.pos.core.database.dao.CachedFloorMapDao
import com.airos.pos.core.database.dao.MenuItemDao
import com.airos.pos.core.database.dao.NfcIdentityDao
import com.airos.pos.core.database.dao.OpenSaleDao
import com.airos.pos.core.database.dao.SalesLedgerOutboxDao
import com.airos.pos.core.database.dao.ShiftDao
import com.airos.pos.core.database.dao.StaffDao
import com.airos.pos.core.database.dao.SyncQueueDao
import com.airos.pos.core.database.dao.TableDao
import com.airos.pos.core.database.dao.TicketDao
import com.airos.pos.core.database.entity.BackendMenuItemEntity
import com.airos.pos.core.database.entity.CachedFloorMapTableEntity
import com.airos.pos.core.database.entity.AttendanceActiveSessionLocalEntity
import com.airos.pos.core.database.entity.AttendanceEventLocalEntity
import com.airos.pos.core.database.entity.AttendanceSyncMetadataLocalEntity
import com.airos.pos.core.database.entity.MenuCacheMetadataEntity
import com.airos.pos.core.database.entity.MenuItemLocalEntity
import com.airos.pos.core.database.entity.NfcIdentityEnrollmentEntity
import com.airos.pos.core.database.entity.NfcIdentityEventEntity
import com.airos.pos.core.database.entity.NfcReceiptHandoffEntity
import com.airos.pos.core.database.entity.OpenSaleEntity
import com.airos.pos.core.database.entity.OpenSaleLineEntity
import com.airos.pos.core.database.entity.OpenSaleTransferEventEntity
import com.airos.pos.core.database.entity.RestaurantTableLocalEntity
import com.airos.pos.core.database.entity.SalesLedgerOutboxLocalEntity
import com.airos.pos.core.database.entity.ShiftLocalEntity
import com.airos.pos.core.database.entity.StaffLocalEntity
import com.airos.pos.core.database.entity.SyncQueueLocalEntity
import com.airos.pos.core.database.entity.TicketLineLocalEntity
import com.airos.pos.core.database.entity.TicketLocalEntity

@Database(
    entities = [
        StaffLocalEntity::class,
        RestaurantTableLocalEntity::class,
        MenuItemLocalEntity::class,
        TicketLocalEntity::class,
        TicketLineLocalEntity::class,
        ShiftLocalEntity::class,
        SyncQueueLocalEntity::class,
        BackendMenuItemEntity::class,
        MenuCacheMetadataEntity::class,
        NfcIdentityEnrollmentEntity::class,
        NfcIdentityEventEntity::class,
        NfcReceiptHandoffEntity::class,
        OpenSaleEntity::class,
        OpenSaleLineEntity::class,
        OpenSaleTransferEventEntity::class,
        AttendanceEventLocalEntity::class,
        AttendanceActiveSessionLocalEntity::class,
        AttendanceSyncMetadataLocalEntity::class,
        SalesLedgerOutboxLocalEntity::class,
        CachedFloorMapTableEntity::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class AirosPosDatabase : RoomDatabase() {
    abstract fun staffDao(): StaffDao
    abstract fun tableDao(): TableDao
    abstract fun menuItemDao(): MenuItemDao
    abstract fun ticketDao(): TicketDao
    abstract fun shiftDao(): ShiftDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun backendMenuCacheDao(): BackendMenuCacheDao
    abstract fun nfcIdentityDao(): NfcIdentityDao
    abstract fun openSaleDao(): OpenSaleDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun salesLedgerOutboxDao(): SalesLedgerOutboxDao
    abstract fun cachedFloorMapDao(): CachedFloorMapDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `backend_menu_items` (
                        `restaurantKey` TEXT NOT NULL,
                        `id` TEXT NOT NULL,
                        `sku` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `priceCents` INTEGER NOT NULL,
                        `taxRatePercent` REAL NOT NULL,
                        `subcategory` TEXT,
                        `barcode` TEXT,
                        `imageUrl` TEXT,
                        `cachedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`restaurantKey`, `id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `menu_cache_metadata` (
                        `restaurantKey` TEXT NOT NULL,
                        `lastSyncedAt` INTEGER NOT NULL,
                        `itemCount` INTEGER NOT NULL,
                        PRIMARY KEY(`restaurantKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `nfc_identity_enrollments` (
                        `canonicalUid` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `entityDisplayLabel` TEXT NOT NULL,
                        `entityRoleLabel` TEXT,
                        `nickname` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`canonicalUid`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_nfc_identity_enrollments_entityType_entityId`
                    ON `nfc_identity_enrollments` (`entityType`, `entityId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_nfc_identity_enrollments_entityType`
                    ON `nfc_identity_enrollments` (`entityType`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_nfc_identity_enrollments_enabled`
                    ON `nfc_identity_enrollments` (`enabled`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `nfc_identity_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `canonicalUid` TEXT,
                        `entityType` TEXT,
                        `entityId` TEXT,
                        `entityDisplayLabel` TEXT,
                        `message` TEXT NOT NULL,
                        `occurredAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_nfc_identity_events_eventType`
                    ON `nfc_identity_events` (`eventType`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_nfc_identity_events_occurredAtEpochMillis`
                    ON `nfc_identity_events` (`occurredAtEpochMillis`)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `nfc_receipt_handoffs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `canonicalUid` TEXT NOT NULL,
                        `receiptNumber` TEXT NOT NULL,
                        `ticketId` TEXT NOT NULL,
                        `saleId` TEXT,
                        `receiptSnapshotId` TEXT,
                        `publicReceiptUrl` TEXT,
                        `publicUrlPath` TEXT,
                        `rawPublicToken` TEXT,
                        `deliveryTokenIdsCsv` TEXT NOT NULL,
                        `linkedCustomerEntityId` TEXT,
                        `linkedCustomerDisplayLabel` TEXT,
                        `createdAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_nfc_receipt_handoffs_canonicalUid`
                    ON `nfc_receipt_handoffs` (`canonicalUid`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_nfc_receipt_handoffs_receiptNumber`
                    ON `nfc_receipt_handoffs` (`receiptNumber`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_nfc_receipt_handoffs_createdAtEpochMillis`
                    ON `nfc_receipt_handoffs` (`createdAtEpochMillis`)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `open_sales` (
                        `saleId` TEXT PRIMARY KEY NOT NULL,
                        `serviceSpotId` TEXT,
                        `serviceSpotLabel` TEXT,
                        `status` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `open_sale_lines` (
                        `saleId` TEXT NOT NULL,
                        `itemId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `quantity` INTEGER NOT NULL,
                        `unitPriceCents` INTEGER NOT NULL,
                        `taxRatePercent` REAL NOT NULL,
                        `discountPercent` INTEGER,
                        `discountAmountCents` INTEGER,
                        PRIMARY KEY(`saleId`, `itemId`),
                        FOREIGN KEY(`saleId`) REFERENCES `open_sales`(`saleId`) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_open_sale_lines_saleId`
                    ON `open_sale_lines` (`saleId`)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `backend_menu_items`
                    ADD COLUMN `subcategoryImageUrl` TEXT
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `backend_menu_items`
                    ADD COLUMN `cachedImagePath` TEXT
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    ALTER TABLE `backend_menu_items`
                    ADD COLUMN `cachedSubcategoryImagePath` TEXT
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attendance_events` (
                        `eventId` TEXT NOT NULL,
                        `metadataKey` TEXT NOT NULL,
                        `ownerAccountId` TEXT,
                        `restaurantKey` TEXT NOT NULL,
                        `terminalId` TEXT NOT NULL,
                        `staffId` TEXT NOT NULL,
                        `staffName` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `occurredAtEpochMillis` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `syncStatus` TEXT NOT NULL,
                        `terminalSequenceNumber` INTEGER NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        `syncBatchId` TEXT,
                        `lastError` TEXT,
                        PRIMARY KEY(`eventId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_attendance_events_metadataKey_terminalSequenceNumber`
                    ON `attendance_events` (`metadataKey`, `terminalSequenceNumber`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_attendance_events_metadataKey_syncStatus_terminalSequenceNumber`
                    ON `attendance_events` (`metadataKey`, `syncStatus`, `terminalSequenceNumber`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_attendance_events_restaurantKey_staffId_occurredAtEpochMillis`
                    ON `attendance_events` (`restaurantKey`, `staffId`, `occurredAtEpochMillis`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attendance_active_sessions` (
                        `sessionKey` TEXT NOT NULL,
                        `ownerAccountId` TEXT,
                        `restaurantKey` TEXT NOT NULL,
                        `staffId` TEXT NOT NULL,
                        `staffName` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `startedAtIso` TEXT NOT NULL,
                        `startedAtEpochMillis` INTEGER NOT NULL,
                        `serverSessionId` INTEGER,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionKey`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_attendance_active_sessions_restaurantKey_staffId`
                    ON `attendance_active_sessions` (`restaurantKey`, `staffId`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attendance_sync_metadata` (
                        `metadataKey` TEXT NOT NULL,
                        `ownerAccountId` TEXT,
                        `restaurantKey` TEXT NOT NULL,
                        `terminalId` TEXT NOT NULL,
                        `lastSuccessfulSyncAtEpochMillis` INTEGER,
                        `lastSeenTerminalSequence` INTEGER NOT NULL,
                        `lastSyncBatchId` TEXT,
                        `lastError` TEXT,
                        `syncState` TEXT NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`metadataKey`)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sales_ledger_outbox` (
                        `sourcePosEventId` TEXT NOT NULL,
                        `receiptNumber` TEXT NOT NULL,
                        `ticketId` TEXT,
                        `tableId` TEXT,
                        `terminalId` TEXT,
                        `restaurantId` TEXT,
                        `cashierStaffId` TEXT,
                        `totalCents` INTEGER NOT NULL,
                        `requestJson` TEXT NOT NULL,
                        `syncStatus` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `serverSaleId` TEXT,
                        `receiptSnapshotId` TEXT,
                        `publicUrlPath` TEXT,
                        `deliveryTokenIdsCsv` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        `lastAttemptAtEpochMillis` INTEGER,
                        `syncedAtEpochMillis` INTEGER,
                        PRIMARY KEY(`sourcePosEventId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_sales_ledger_outbox_receiptNumber`
                    ON `sales_ledger_outbox` (`receiptNumber`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_sales_ledger_outbox_syncStatus_createdAtEpochMillis`
                    ON `sales_ledger_outbox` (`syncStatus`, `createdAtEpochMillis`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_sales_ledger_outbox_terminalId_createdAtEpochMillis`
                    ON `sales_ledger_outbox` (`terminalId`, `createdAtEpochMillis`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_sales_ledger_outbox_cashierStaffId_createdAtEpochMillis`
                    ON `sales_ledger_outbox` (`cashierStaffId`, `createdAtEpochMillis`)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_floor_map_tables` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `areaName` TEXT NOT NULL,
                        `seats` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `guestCount` INTEGER NOT NULL,
                        `activeTicketId` TEXT,
                        `positionX` INTEGER NOT NULL,
                        `positionY` INTEGER NOT NULL,
                        `positionWidth` INTEGER NOT NULL,
                        `positionHeight` INTEGER NOT NULL,
                        `cameraId` TEXT,
                        `cameraLabel` TEXT,
                        `attentionFlag` TEXT NOT NULL,
                        `reviewAnchorTime` TEXT,
                        `reviewFrom` TEXT,
                        `reviewTo` TEXT,
                        `spotType` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `cachedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `cached_floor_map_tables`
                    ADD COLUMN `backendTableId` INTEGER
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE `cached_floor_map_tables`
                    SET `backendTableId` = CAST(SUBSTR(`id`, 7) AS INTEGER)
                    WHERE `backendTableId` IS NULL
                      AND `id` GLOB 'table-[0-9]*'
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `open_sale_transfer_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `saleId` TEXT NOT NULL,
                        `fromServiceSpotId` TEXT,
                        `fromServiceSpotLabel` TEXT,
                        `toServiceSpotId` TEXT,
                        `toServiceSpotLabel` TEXT,
                        `actedByStaffId` TEXT NOT NULL,
                        `actedByDisplayName` TEXT NOT NULL,
                        `occurredAtEpochMillis` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_open_sale_transfer_events_saleId`
                    ON `open_sale_transfer_events` (`saleId`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_open_sale_transfer_events_occurredAtEpochMillis`
                    ON `open_sale_transfer_events` (`occurredAtEpochMillis`)
                    """.trimIndent(),
                )
            }
        }

        fun build(context: Context): AirosPosDatabase {
            return Room.databaseBuilder(
                context,
                AirosPosDatabase::class.java,
                "airos-pos.db",
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
            ).build()
        }
    }
}
