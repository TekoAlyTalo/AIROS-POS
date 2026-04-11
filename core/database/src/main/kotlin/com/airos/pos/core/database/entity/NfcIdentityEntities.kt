package com.airos.pos.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "nfc_identity_enrollments",
    indices = [
        Index(value = ["entityType", "entityId"], unique = true),
        Index(value = ["entityType"]),
        Index(value = ["enabled"]),
    ],
)
data class NfcIdentityEnrollmentEntity(
    @PrimaryKey val canonicalUid: String,
    val entityType: String,
    val entityId: String,
    val entityDisplayLabel: String,
    val entityRoleLabel: String?,
    val nickname: String?,
    val enabled: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "nfc_identity_events",
    indices = [
        Index(value = ["eventType"]),
        Index(value = ["occurredAtEpochMillis"]),
    ],
)
data class NfcIdentityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val canonicalUid: String?,
    val entityType: String?,
    val entityId: String?,
    val entityDisplayLabel: String?,
    val message: String,
    val occurredAtEpochMillis: Long,
)

@Entity(
    tableName = "nfc_receipt_handoffs",
    indices = [
        Index(value = ["canonicalUid"]),
        Index(value = ["receiptNumber"]),
        Index(value = ["createdAtEpochMillis"]),
    ],
)
data class NfcReceiptHandoffEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canonicalUid: String,
    val receiptNumber: String,
    val ticketId: String,
    val saleId: String?,
    val receiptSnapshotId: String?,
    val publicReceiptUrl: String?,
    val publicUrlPath: String?,
    val rawPublicToken: String?,
    val deliveryTokenIdsCsv: String,
    val linkedCustomerEntityId: String?,
    val linkedCustomerDisplayLabel: String?,
    val createdAtEpochMillis: Long,
)
