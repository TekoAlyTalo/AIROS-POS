package com.airos.pos.app

import android.util.Log
import androidx.room.withTransaction
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.database.AirosPosDatabase
import com.airos.pos.core.database.entity.NfcIdentityEnrollmentEntity
import com.airos.pos.core.database.entity.NfcIdentityEventEntity
import com.airos.pos.core.database.entity.NfcReceiptHandoffEntity
import com.airos.pos.core.model.NfcIdentityEvent
import com.airos.pos.core.model.NfcIdentityEventType
import com.airos.pos.core.model.NfcIdentityRecord
import com.airos.pos.core.model.NfcLinkedEntityType
import com.airos.pos.core.model.NfcReceiptHandoffRecord
import com.airos.pos.core.model.ReceiptHandoffPayload
import com.airos.pos.core.model.StaffAuthRecord
import com.airos.pos.core.model.StaffMember
import com.airos.pos.domain.NfcIdentityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val NfcIdentityLogTag = "AIROS_NFC"

private data class LegacyStaffTagSeed(
    val canonicalUid: String,
    val staffId: String,
)

private val legacyStaffTagSeeds = listOf(
    LegacyStaffTagSeed(canonicalUid = "08:7D:F6:83", staffId = "staff-1"),
    LegacyStaffTagSeed(canonicalUid = "02:5A:85:BE:C4:40:00", staffId = "staff-1"),
)

class RoomNfcIdentityRepository(
    private val database: AirosPosDatabase,
) : NfcIdentityRepository {
    private val dao
        get() = database.nfcIdentityDao()

    override fun observeStaffEnrollments(): Flow<List<NfcIdentityRecord>> {
        return dao.observeEnrollmentsByEntityType(NfcLinkedEntityType.STAFF.name).map { items ->
            items.map { it.toModel() }
        }
    }

    override fun observeCustomerEnrollments(): Flow<List<NfcIdentityRecord>> {
        return dao.observeEnrollmentsByEntityType(NfcLinkedEntityType.LOYALTY_MEMBER.name).map { items ->
            items.map { it.toModel() }
        }
    }

    override fun observeRecentEvents(limit: Int): Flow<List<NfcIdentityEvent>> {
        return dao.observeRecentEvents(limit).map { items ->
            items.mapNotNull { it.toModelOrNull() }
        }
    }

    override fun observeRecentReceiptHandoffs(limit: Int): Flow<List<NfcReceiptHandoffRecord>> {
        return dao.observeRecentReceiptHandoffs(limit).map { items ->
            items.map { it.toModel() }
        }
    }

    override suspend fun resolveEnabledIdentity(canonicalUid: String): NfcIdentityRecord? {
        val canonical = canonicalizeNfcUid(canonicalUid)
        val entity = dao.findEnabledEnrollmentByUid(canonical) ?: return null
        val record = entity.toModel()
        dao.insertEvent(
            NfcIdentityEventEntity(
                eventType = NfcIdentityEventType.MATCHED.name,
                canonicalUid = canonical,
                entityType = record.entityType.name,
                entityId = record.entityId,
                entityDisplayLabel = record.entityDisplayLabel,
                message = "Matched NFC tag $canonical to ${record.entityDisplayLabel}.",
                occurredAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        Log.i(
            NfcIdentityLogTag,
            "NFC identity matched | uid=$canonical entityType=${record.entityType} entityId=${record.entityId}",
        )
        return record
    }

    override suspend fun resolveCustomerIdentity(canonicalUid: String): NfcIdentityRecord? {
        val canonical = canonicalizeNfcUid(canonicalUid)
        val entity = dao.findEnabledEnrollmentByUid(canonical)
            ?.takeIf { it.entityType == NfcLinkedEntityType.LOYALTY_MEMBER.name }
            ?: return null
        val record = entity.toModel()
        dao.insertEvent(
            NfcIdentityEventEntity(
                eventType = NfcIdentityEventType.CUSTOMER_MATCHED.name,
                canonicalUid = canonical,
                entityType = record.entityType.name,
                entityId = record.entityId,
                entityDisplayLabel = record.entityDisplayLabel,
                message = "Matched customer NFC tag $canonical to ${record.entityDisplayLabel}.",
                occurredAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        Log.i(
            NfcIdentityLogTag,
            "NFC customer identity matched | uid=$canonical entityId=${record.entityId}",
        )
        return record
    }

    override suspend fun enrollStaffTag(staff: StaffMember, canonicalUid: String): PosResult<NfcIdentityRecord> {
        if (!staff.isEnabled) {
            return PosResult.Failure("Disabled staff profiles cannot receive NFC tags.")
        }

        return try {
            val record = database.withTransaction {
                val canonical = canonicalizeNfcUid(canonicalUid)
                val now = System.currentTimeMillis()
                val existingByUid = dao.findEnrollmentByUid(canonical)
                val existingByStaff = dao.findEnrollmentForEntity(
                    entityType = NfcLinkedEntityType.STAFF.name,
                    entityId = staff.id,
                )

                val isReplacement =
                    (existingByStaff != null && existingByStaff.canonicalUid != canonical) ||
                        (existingByUid != null &&
                            (existingByUid.entityType != NfcLinkedEntityType.STAFF.name || existingByUid.entityId != staff.id))

                if (existingByUid != null &&
                    (existingByUid.entityType != NfcLinkedEntityType.STAFF.name || existingByUid.entityId != staff.id)
                ) {
                    dao.deleteEnrollmentByUid(canonical)
                }
                if (existingByStaff != null && existingByStaff.canonicalUid != canonical) {
                    dao.deleteEnrollmentForEntity(
                        entityType = NfcLinkedEntityType.STAFF.name,
                        entityId = staff.id,
                    )
                }

                val createdAt = when {
                    existingByStaff?.canonicalUid == canonical -> existingByStaff.createdAtEpochMillis
                    existingByUid?.entityType == NfcLinkedEntityType.STAFF.name &&
                        existingByUid.entityId == staff.id -> existingByUid.createdAtEpochMillis
                    else -> now
                }

                val entity = NfcIdentityEnrollmentEntity(
                    canonicalUid = canonical,
                    entityType = NfcLinkedEntityType.STAFF.name,
                    entityId = staff.id,
                    entityDisplayLabel = staff.displayName,
                    entityRoleLabel = staff.role.name,
                    nickname = null,
                    enabled = true,
                    createdAtEpochMillis = createdAt,
                    updatedAtEpochMillis = now,
                )
                dao.upsertEnrollment(entity)
                dao.insertEvent(
                    NfcIdentityEventEntity(
                        eventType = if (isReplacement) {
                            NfcIdentityEventType.REPLACED.name
                        } else {
                            NfcIdentityEventType.ENROLLED.name
                        },
                        canonicalUid = canonical,
                        entityType = NfcLinkedEntityType.STAFF.name,
                        entityId = staff.id,
                        entityDisplayLabel = staff.displayName,
                        message = if (isReplacement) {
                            "Replaced NFC tag for ${staff.displayName} with $canonical."
                        } else {
                            "Enrolled NFC tag $canonical for ${staff.displayName}."
                        },
                        occurredAtEpochMillis = now,
                    ),
                )
                entity.toModel()
            }
            Log.i(
                NfcIdentityLogTag,
                "NFC tag enrolled | staffId=${staff.id} uid=${record.canonicalUid}",
            )
            PosResult.Success(record)
        } catch (error: IllegalArgumentException) {
            Log.w(NfcIdentityLogTag, "NFC enroll rejected | reason=${error.message}")
            PosResult.Failure(error.message ?: "NFC UID is invalid.")
        } catch (error: Throwable) {
            Log.e(NfcIdentityLogTag, "NFC enroll failed", error)
            PosResult.Failure("Failed to store the NFC enrollment locally.")
        }
    }

    override suspend fun removeStaffTag(staffId: String): PosResult<Unit> {
        return try {
            database.withTransaction {
                val existing = dao.findEnrollmentForEntity(
                    entityType = NfcLinkedEntityType.STAFF.name,
                    entityId = staffId,
                ) ?: return@withTransaction
                dao.deleteEnrollmentForEntity(
                    entityType = NfcLinkedEntityType.STAFF.name,
                    entityId = staffId,
                )
                dao.insertEvent(
                    NfcIdentityEventEntity(
                        eventType = NfcIdentityEventType.REMOVED.name,
                        canonicalUid = existing.canonicalUid,
                        entityType = existing.entityType,
                        entityId = existing.entityId,
                        entityDisplayLabel = existing.entityDisplayLabel,
                        message = "Removed NFC tag ${existing.canonicalUid} from ${existing.entityDisplayLabel}.",
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
            Log.i(NfcIdentityLogTag, "NFC tag removed | staffId=$staffId")
            PosResult.Success(Unit)
        } catch (error: Throwable) {
            Log.e(NfcIdentityLogTag, "NFC remove failed", error)
            PosResult.Failure("Failed to remove the NFC enrollment.")
        }
    }

    override suspend fun enrollCustomerTag(canonicalUid: String, displayLabel: String): PosResult<NfcIdentityRecord> {
        return try {
            val record = database.withTransaction {
                val canonical = canonicalizeNfcUid(canonicalUid)
                val now = System.currentTimeMillis()
                val existingByUid = dao.findEnrollmentByUid(canonical)
                if (existingByUid != null && existingByUid.entityType != NfcLinkedEntityType.LOYALTY_MEMBER.name) {
                    throw IllegalStateException("This NFC tag is already enrolled as ${existingByUid.entityType}.")
                }

                val label = displayLabel.trim().ifBlank { defaultCustomerLabel(canonical) }
                val entity = NfcIdentityEnrollmentEntity(
                    canonicalUid = canonical,
                    entityType = NfcLinkedEntityType.LOYALTY_MEMBER.name,
                    entityId = customerEntityIdForUid(canonical),
                    entityDisplayLabel = label,
                    entityRoleLabel = "CUSTOMER",
                    nickname = label,
                    enabled = true,
                    createdAtEpochMillis = existingByUid?.createdAtEpochMillis ?: now,
                    updatedAtEpochMillis = now,
                )
                dao.upsertEnrollment(entity)
                dao.insertEvent(
                    NfcIdentityEventEntity(
                        eventType = if (existingByUid == null) {
                            NfcIdentityEventType.ENROLLED.name
                        } else {
                            NfcIdentityEventType.REPLACED.name
                        },
                        canonicalUid = canonical,
                        entityType = NfcLinkedEntityType.LOYALTY_MEMBER.name,
                        entityId = entity.entityId,
                        entityDisplayLabel = label,
                        message = if (existingByUid == null) {
                            "Enrolled customer NFC tag $canonical for $label."
                        } else {
                            "Updated customer NFC tag $canonical for $label."
                        },
                        occurredAtEpochMillis = now,
                    ),
                )
                entity.toModel()
            }
            Log.i(
                NfcIdentityLogTag,
                "NFC customer tag enrolled | uid=${record.canonicalUid} entityId=${record.entityId}",
            )
            PosResult.Success(record)
        } catch (error: IllegalArgumentException) {
            Log.w(NfcIdentityLogTag, "NFC customer enroll rejected | reason=${error.message}")
            PosResult.Failure(error.message ?: "NFC UID is invalid.")
        } catch (error: IllegalStateException) {
            Log.w(NfcIdentityLogTag, "NFC customer enroll rejected | reason=${error.message}")
            PosResult.Failure(error.message ?: "NFC tag is already linked to another identity.")
        } catch (error: Throwable) {
            Log.e(NfcIdentityLogTag, "NFC customer enroll failed", error)
            PosResult.Failure("Failed to store the customer NFC enrollment locally.")
        }
    }

    override suspend fun removeCustomerTag(canonicalUid: String): PosResult<Unit> {
        return try {
            database.withTransaction {
                val canonical = canonicalizeNfcUid(canonicalUid)
                val existing = dao.findEnrollmentByUid(canonical)
                    ?: return@withTransaction
                if (existing.entityType != NfcLinkedEntityType.LOYALTY_MEMBER.name) {
                    throw IllegalStateException("This NFC tag is not a customer identity.")
                }
                dao.deleteEnrollmentByUid(canonical)
                dao.insertEvent(
                    NfcIdentityEventEntity(
                        eventType = NfcIdentityEventType.REMOVED.name,
                        canonicalUid = canonical,
                        entityType = existing.entityType,
                        entityId = existing.entityId,
                        entityDisplayLabel = existing.entityDisplayLabel,
                        message = "Removed customer NFC tag $canonical from ${existing.entityDisplayLabel}.",
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
            Log.i(NfcIdentityLogTag, "NFC customer tag removed | uid=${canonicalizeNfcUid(canonicalUid)}")
            PosResult.Success(Unit)
        } catch (error: IllegalArgumentException) {
            Log.w(NfcIdentityLogTag, "NFC customer remove rejected | reason=${error.message}")
            PosResult.Failure(error.message ?: "NFC UID is invalid.")
        } catch (error: IllegalStateException) {
            Log.w(NfcIdentityLogTag, "NFC customer remove rejected | reason=${error.message}")
            PosResult.Failure(error.message ?: "NFC tag is not a customer identity.")
        } catch (error: Throwable) {
            Log.e(NfcIdentityLogTag, "NFC customer remove failed", error)
            PosResult.Failure("Failed to remove the customer NFC enrollment.")
        }
    }

    override suspend fun recordReceiptHandoffStarted(payload: ReceiptHandoffPayload) {
        dao.insertEvent(
            NfcIdentityEventEntity(
                eventType = NfcIdentityEventType.RECEIPT_HANDOFF_STARTED.name,
                canonicalUid = null,
                entityType = NfcLinkedEntityType.RECEIPT_HANDOFF.name,
                entityId = payload.ticketId,
                entityDisplayLabel = payload.receiptNumber,
                message = "Receipt NFC handoff started for ${payload.receiptNumber}.",
                occurredAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        Log.i(NfcIdentityLogTag, "NFC receipt handoff started | receipt=${payload.receiptNumber} ticket=${payload.ticketId}")
    }

    override suspend fun recordReceiptHandoffFailed(payload: ReceiptHandoffPayload, reason: String) {
        dao.insertEvent(
            NfcIdentityEventEntity(
                eventType = NfcIdentityEventType.RECEIPT_HANDOFF_FAILED.name,
                canonicalUid = null,
                entityType = NfcLinkedEntityType.RECEIPT_HANDOFF.name,
                entityId = payload.ticketId,
                entityDisplayLabel = payload.receiptNumber,
                message = "Receipt NFC handoff failed for ${payload.receiptNumber}: $reason",
                occurredAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        Log.w(NfcIdentityLogTag, "NFC receipt handoff failed | receipt=${payload.receiptNumber} reason=$reason")
    }

    override suspend fun recordReceiptHandoff(
        canonicalUid: String,
        payload: ReceiptHandoffPayload,
    ): PosResult<NfcReceiptHandoffRecord> {
        return try {
            val record = database.withTransaction {
                val canonical = canonicalizeNfcUid(canonicalUid)
                val now = System.currentTimeMillis()
                val customer = dao.findEnabledEnrollmentByUid(canonical)
                    ?.takeIf { it.entityType == NfcLinkedEntityType.LOYALTY_MEMBER.name }

                if (customer == null) {
                    dao.insertEvent(
                        NfcIdentityEventEntity(
                            eventType = NfcIdentityEventType.UNKNOWN_CUSTOMER_TAG.name,
                            canonicalUid = canonical,
                            entityType = NfcLinkedEntityType.LOYALTY_MEMBER.name,
                            entityId = null,
                            entityDisplayLabel = null,
                            message = "Receipt NFC handoff used an unenrolled customer tag $canonical.",
                            occurredAtEpochMillis = now,
                        ),
                    )
                } else {
                    dao.insertEvent(
                        NfcIdentityEventEntity(
                            eventType = NfcIdentityEventType.CUSTOMER_MATCHED.name,
                            canonicalUid = canonical,
                            entityType = customer.entityType,
                            entityId = customer.entityId,
                            entityDisplayLabel = customer.entityDisplayLabel,
                            message = "Matched customer NFC tag $canonical to ${customer.entityDisplayLabel} for receipt handoff.",
                            occurredAtEpochMillis = now,
                        ),
                    )
                }

                val handoffEntity = NfcReceiptHandoffEntity(
                    canonicalUid = canonical,
                    receiptNumber = payload.receiptNumber,
                    ticketId = payload.ticketId,
                    saleId = payload.saleId,
                    receiptSnapshotId = payload.receiptSnapshotId,
                    publicReceiptUrl = payload.publicReceiptUrl,
                    publicUrlPath = payload.publicUrlPath,
                    rawPublicToken = payload.rawPublicToken,
                    deliveryTokenIdsCsv = payload.deliveryTokenIds.joinToString("|"),
                    linkedCustomerEntityId = customer?.entityId,
                    linkedCustomerDisplayLabel = customer?.entityDisplayLabel,
                    createdAtEpochMillis = now,
                )
                val id = dao.insertReceiptHandoff(handoffEntity)
                dao.insertEvent(
                    NfcIdentityEventEntity(
                        eventType = NfcIdentityEventType.RECEIPT_HANDOFF_LINKED.name,
                        canonicalUid = canonical,
                        entityType = NfcLinkedEntityType.RECEIPT_HANDOFF.name,
                        entityId = payload.ticketId,
                        entityDisplayLabel = payload.receiptNumber,
                        message = "Linked receipt ${payload.receiptNumber} to NFC tag $canonical.",
                        occurredAtEpochMillis = now,
                    ),
                )
                handoffEntity.copy(id = id).toModel()
            }
            Log.i(
                NfcIdentityLogTag,
                "NFC receipt handoff linked | uid=${record.canonicalUid} receipt=${record.receiptNumber} customer=${record.linkedCustomerDisplayLabel}",
            )
            PosResult.Success(record)
        } catch (error: IllegalArgumentException) {
            Log.w(NfcIdentityLogTag, "NFC receipt handoff rejected | reason=${error.message}")
            runCatching { recordReceiptHandoffFailed(payload, error.message ?: "NFC UID is invalid.") }
            PosResult.Failure(error.message ?: "NFC UID is invalid.")
        } catch (error: Throwable) {
            Log.e(NfcIdentityLogTag, "NFC receipt handoff failed", error)
            runCatching { recordReceiptHandoffFailed(payload, "Failed to store the receipt handoff.") }
            PosResult.Failure("Failed to store the receipt NFC handoff.")
        }
    }

    override suspend fun recordUnknownTag(canonicalUid: String) {
        val canonical = try {
            canonicalizeNfcUid(canonicalUid)
        } catch (_: IllegalArgumentException) {
            return
        }
        dao.insertEvent(
            NfcIdentityEventEntity(
                eventType = NfcIdentityEventType.UNKNOWN_TAG.name,
                canonicalUid = canonical,
                entityType = null,
                entityId = null,
                entityDisplayLabel = null,
                message = "Unknown NFC tag detected: $canonical.",
                occurredAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        Log.w(NfcIdentityLogTag, "Unknown NFC tag recorded | uid=$canonical")
    }

    suspend fun seedLegacyStaffEnrollmentsIfEmpty(defaults: List<NfcIdentityRecord>) {
        if (defaults.isEmpty()) {
            return
        }
        database.withTransaction {
            if (dao.countEnrollments() > 0) {
                return@withTransaction
            }
            defaults.forEach { record ->
                dao.upsertEnrollment(record.toEntity())
            }
            dao.insertEvent(
                NfcIdentityEventEntity(
                    eventType = NfcIdentityEventType.ENROLLED.name,
                    canonicalUid = null,
                    entityType = NfcLinkedEntityType.STAFF.name,
                    entityId = null,
                    entityDisplayLabel = null,
                    message = "Bootstrapped ${defaults.size} legacy NFC staff enrollment(s).",
                    occurredAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
        Log.i(NfcIdentityLogTag, "Bootstrapped legacy NFC staff enrollments | count=${defaults.size}")
    }
}

internal fun buildLegacyStaffEnrollmentDefaults(
    authRecords: List<StaffAuthRecord>,
    now: Long = System.currentTimeMillis(),
): List<NfcIdentityRecord> {
    val staffById = authRecords.associateBy { it.staffId }
    return legacyStaffTagSeeds.mapNotNull { seed ->
        val staff = staffById[seed.staffId] ?: return@mapNotNull null
        NfcIdentityRecord(
            canonicalUid = seed.canonicalUid,
            entityType = NfcLinkedEntityType.STAFF,
            entityId = staff.staffId,
            entityDisplayLabel = staff.displayName,
            entityRoleLabel = staff.role.name,
            nickname = null,
            enabled = true,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
    }
}

private fun NfcIdentityEnrollmentEntity.toModel(): NfcIdentityRecord {
    return NfcIdentityRecord(
        canonicalUid = canonicalUid,
        entityType = enumValueOrNull<NfcLinkedEntityType>(entityType) ?: NfcLinkedEntityType.GENERIC_TRIGGER,
        entityId = entityId,
        entityDisplayLabel = entityDisplayLabel,
        entityRoleLabel = entityRoleLabel,
        nickname = nickname,
        enabled = enabled,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private fun NfcIdentityRecord.toEntity(): NfcIdentityEnrollmentEntity {
    return NfcIdentityEnrollmentEntity(
        canonicalUid = canonicalUid,
        entityType = entityType.name,
        entityId = entityId,
        entityDisplayLabel = entityDisplayLabel,
        entityRoleLabel = entityRoleLabel,
        nickname = nickname,
        enabled = enabled,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private fun NfcIdentityEventEntity.toModelOrNull(): NfcIdentityEvent? {
    val eventType = enumValueOrNull<NfcIdentityEventType>(eventType) ?: return null
    return NfcIdentityEvent(
        id = id,
        type = eventType,
        canonicalUid = canonicalUid,
        entityType = enumValueOrNull<NfcLinkedEntityType>(entityType),
        entityId = entityId,
        entityDisplayLabel = entityDisplayLabel,
        message = message,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )
}

private fun NfcReceiptHandoffEntity.toModel(): NfcReceiptHandoffRecord {
    return NfcReceiptHandoffRecord(
        id = id,
        canonicalUid = canonicalUid,
        receiptNumber = receiptNumber,
        ticketId = ticketId,
        saleId = saleId,
        receiptSnapshotId = receiptSnapshotId,
        publicReceiptUrl = publicReceiptUrl,
        publicUrlPath = publicUrlPath,
        rawPublicToken = rawPublicToken,
        deliveryTokenIds = deliveryTokenIdsCsv
            .split("|")
            .filter { it.isNotBlank() },
        linkedCustomerEntityId = linkedCustomerEntityId,
        linkedCustomerDisplayLabel = linkedCustomerDisplayLabel,
        createdAtEpochMillis = createdAtEpochMillis,
    )
}

private fun customerEntityIdForUid(canonicalUid: String): String {
    return "customer-${canonicalUid.replace(":", "").lowercase()}"
}

private fun defaultCustomerLabel(canonicalUid: String): String {
    val suffix = canonicalUid
        .split(":")
        .takeLast(2)
        .joinToString("")
        .ifBlank { canonicalUid.replace(":", "").takeLast(4) }
    return "Customer $suffix"
}

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? {
    if (value.isNullOrBlank()) {
        return null
    }
    return runCatching { enumValueOf<T>(value) }.getOrNull()
}
