package com.airos.pos.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.airos.pos.core.database.dao.BackendMenuCacheDao
import com.airos.pos.core.database.dao.MenuItemDao
import com.airos.pos.core.database.dao.NfcIdentityDao
import com.airos.pos.core.database.dao.ShiftDao
import com.airos.pos.core.database.dao.StaffDao
import com.airos.pos.core.database.dao.SyncQueueDao
import com.airos.pos.core.database.dao.TableDao
import com.airos.pos.core.database.dao.TicketDao
import com.airos.pos.core.database.entity.BackendMenuItemEntity
import com.airos.pos.core.database.entity.MenuCacheMetadataEntity
import com.airos.pos.core.database.entity.MenuItemLocalEntity
import com.airos.pos.core.database.entity.NfcIdentityEnrollmentEntity
import com.airos.pos.core.database.entity.NfcIdentityEventEntity
import com.airos.pos.core.database.entity.NfcReceiptHandoffEntity
import com.airos.pos.core.database.entity.RestaurantTableLocalEntity
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
    ],
    version = 4,
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

        fun build(context: Context): AirosPosDatabase {
            return Room.databaseBuilder(
                context,
                AirosPosDatabase::class.java,
                "airos-pos.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        }
    }
}
