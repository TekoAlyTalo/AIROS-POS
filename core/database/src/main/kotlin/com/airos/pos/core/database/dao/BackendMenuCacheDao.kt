package com.airos.pos.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.airos.pos.core.database.entity.BackendMenuItemEntity
import com.airos.pos.core.database.entity.MenuCacheMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackendMenuCacheDao {

    @Query("SELECT * FROM backend_menu_items WHERE restaurantKey = :restaurantKey ORDER BY category, name")
    fun observeAll(restaurantKey: String): Flow<List<BackendMenuItemEntity>>

    @Query("SELECT COUNT(*) FROM backend_menu_items WHERE restaurantKey = :restaurantKey")
    suspend fun getCount(restaurantKey: String): Int

    @Upsert
    suspend fun upsertAll(items: List<BackendMenuItemEntity>)

    @Query("DELETE FROM backend_menu_items WHERE restaurantKey = :restaurantKey")
    suspend fun deleteAll(restaurantKey: String)

    @Upsert
    suspend fun upsertMetadata(metadata: MenuCacheMetadataEntity)

    @Query("SELECT * FROM menu_cache_metadata WHERE restaurantKey = :restaurantKey LIMIT 1")
    suspend fun getMetadata(restaurantKey: String): MenuCacheMetadataEntity?

    @Transaction
    suspend fun replaceAll(restaurantKey: String, items: List<BackendMenuItemEntity>, metadata: MenuCacheMetadataEntity) {
        deleteAll(restaurantKey)
        upsertAll(items)
        upsertMetadata(metadata)
    }
}
