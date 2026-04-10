package com.airos.pos.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.airos.pos.core.database.dao.BackendMenuCacheDao
import com.airos.pos.core.database.dao.MenuItemDao
import com.airos.pos.core.database.dao.ShiftDao
import com.airos.pos.core.database.dao.StaffDao
import com.airos.pos.core.database.dao.SyncQueueDao
import com.airos.pos.core.database.dao.TableDao
import com.airos.pos.core.database.dao.TicketDao
import com.airos.pos.core.database.entity.BackendMenuItemEntity
import com.airos.pos.core.database.entity.MenuCacheMetadataEntity
import com.airos.pos.core.database.entity.MenuItemLocalEntity
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
    ],
    version = 2,
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

        fun build(context: Context): AirosPosDatabase {
            return Room.databaseBuilder(
                context,
                AirosPosDatabase::class.java,
                "airos-pos.db",
            ).addMigrations(MIGRATION_1_2).build()
        }
    }
}
