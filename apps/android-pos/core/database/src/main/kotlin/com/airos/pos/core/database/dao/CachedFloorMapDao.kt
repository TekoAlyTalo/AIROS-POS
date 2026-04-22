package com.airos.pos.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.airos.pos.core.database.entity.CachedFloorMapTableEntity

@Dao
interface CachedFloorMapDao {
    @Query("SELECT * FROM cached_floor_map_tables ORDER BY sortOrder ASC, id ASC")
    suspend fun loadAll(): List<CachedFloorMapTableEntity>

    @Query("DELETE FROM cached_floor_map_tables")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedFloorMapTableEntity>)

    @Transaction
    suspend fun replaceAll(items: List<CachedFloorMapTableEntity>) {
        clear()
        if (items.isNotEmpty()) {
            insertAll(items)
        }
    }
}
