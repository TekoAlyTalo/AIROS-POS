package com.airos.pos.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.airos.pos.core.database.entity.NfcIdentityEnrollmentEntity
import com.airos.pos.core.database.entity.NfcIdentityEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NfcIdentityDao {
    @Query(
        """
        SELECT * FROM nfc_identity_enrollments
        WHERE entityType = :entityType
        ORDER BY entityDisplayLabel ASC, updatedAtEpochMillis DESC
        """
    )
    fun observeEnrollmentsByEntityType(entityType: String): Flow<List<NfcIdentityEnrollmentEntity>>

    @Query("SELECT * FROM nfc_identity_enrollments WHERE canonicalUid = :canonicalUid LIMIT 1")
    suspend fun findEnrollmentByUid(canonicalUid: String): NfcIdentityEnrollmentEntity?

    @Query(
        """
        SELECT * FROM nfc_identity_enrollments
        WHERE canonicalUid = :canonicalUid AND enabled = 1
        LIMIT 1
        """
    )
    suspend fun findEnabledEnrollmentByUid(canonicalUid: String): NfcIdentityEnrollmentEntity?

    @Query(
        """
        SELECT * FROM nfc_identity_enrollments
        WHERE entityType = :entityType AND entityId = :entityId
        LIMIT 1
        """
    )
    suspend fun findEnrollmentForEntity(entityType: String, entityId: String): NfcIdentityEnrollmentEntity?

    @Query("SELECT COUNT(*) FROM nfc_identity_enrollments")
    suspend fun countEnrollments(): Int

    @Upsert
    suspend fun upsertEnrollment(item: NfcIdentityEnrollmentEntity)

    @Query("DELETE FROM nfc_identity_enrollments WHERE canonicalUid = :canonicalUid")
    suspend fun deleteEnrollmentByUid(canonicalUid: String)

    @Query("DELETE FROM nfc_identity_enrollments WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteEnrollmentForEntity(entityType: String, entityId: String)

    @Query(
        """
        SELECT * FROM nfc_identity_events
        ORDER BY occurredAtEpochMillis DESC, id DESC
        LIMIT :limit
        """
    )
    fun observeRecentEvents(limit: Int): Flow<List<NfcIdentityEventEntity>>

    @Insert
    suspend fun insertEvent(item: NfcIdentityEventEntity): Long
}
