package com.airos.pos.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.airos.pos.core.database.dao.MenuItemDao
import com.airos.pos.core.database.dao.ShiftDao
import com.airos.pos.core.database.dao.StaffDao
import com.airos.pos.core.database.dao.SyncQueueDao
import com.airos.pos.core.database.dao.TableDao
import com.airos.pos.core.database.dao.TicketDao
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
    ],
    version = 1,
    exportSchema = false,
)
abstract class AirosPosDatabase : RoomDatabase() {
    abstract fun staffDao(): StaffDao
    abstract fun tableDao(): TableDao
    abstract fun menuItemDao(): MenuItemDao
    abstract fun ticketDao(): TicketDao
    abstract fun shiftDao(): ShiftDao
    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        fun build(context: Context): AirosPosDatabase {
            return Room.databaseBuilder(
                context,
                AirosPosDatabase::class.java,
                "airos-pos.db",
            ).fallbackToDestructiveMigration().build()
        }
    }
}
