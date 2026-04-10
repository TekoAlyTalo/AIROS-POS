package com.airos.pos.app

import com.airos.pos.core.model.NfcLinkedEntityType
import com.airos.pos.domain.NfcIdentityRepository

/**
 * A resolved NFC tag → staff member mapping result.
 *
 * [staffId] matches the ids used in the auth repository so that both direct-login
 * and auth-screen preselect can keep using the existing sign-in path.
 */
data class NfcStaffMatch(
    /** Canonical UID that produced this match (uppercase colon-separated hex). */
    val uid: String,
    val staffId: String,
    val displayName: String,
    val role: String,
    val matchedAtEpochMillis: Long,
)

/**
 * Result of an NFC UID resolution attempt after a tag is read.
 *
 * [Matched] → UID is known and mapped to a staff member.
 * [Unknown] → UID was read but has no mapping. Log/show for operator onboarding.
 */
sealed class NfcStaffResolution {
    data class Matched(val match: NfcStaffMatch) : NfcStaffResolution()
    data class Unknown(val uid: String, val detectedAtEpochMillis: Long) : NfcStaffResolution()
}

/**
 * Canonicalizes a raw NFC UID into uppercase colon-separated hex bytes.
 *
 * Accepted delimiters are ":" / "-" / whitespace. Each byte is padded to two hex
 * characters so both manual input and runtime values converge to the same form.
 */
fun canonicalizeNfcUid(rawValue: String): String {
    val normalizedParts = rawValue
        .trim()
        .uppercase()
        .replace('-', ':')
        .split(':')
        .flatMap { part ->
            part.split(Regex("\\s+")).filter { it.isNotBlank() }
        }
        .map { token ->
            require(token.length in 1..2 && token.all { it in '0'..'9' || it in 'A'..'F' }) {
                "NFC UID must contain 1-2 digit hex tokens."
            }
            token.padStart(2, '0')
        }

    require(normalizedParts.isNotEmpty()) { "NFC UID is empty." }
    return normalizedParts.joinToString(":")
}

/**
 * Resolves a canonical NFC UID to an enrolled staff identity.
 */
interface NfcStaffResolver {
    suspend fun resolve(canonicalUid: String): NfcStaffMatch?
}

class RepositoryNfcStaffResolver(
    private val nfcIdentityRepository: NfcIdentityRepository,
) : NfcStaffResolver {
    override suspend fun resolve(canonicalUid: String): NfcStaffMatch? {
        val record = nfcIdentityRepository.resolveEnabledIdentity(canonicalizeNfcUid(canonicalUid))
            ?.takeIf { it.entityType == NfcLinkedEntityType.STAFF }
            ?: return null
        return NfcStaffMatch(
            uid = record.canonicalUid,
            staffId = record.entityId,
            displayName = record.entityDisplayLabel,
            role = record.entityRoleLabel ?: "STAFF",
            matchedAtEpochMillis = System.currentTimeMillis(),
        )
    }
}
