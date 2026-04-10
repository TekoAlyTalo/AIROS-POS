package com.airos.pos.app

/**
 * A resolved NFC tag → staff member mapping result.
 *
 * [staffId] matches the ids used in [SampleData.localAuthStaffRecords] so that
 * a future tap-login path can look up the full staff record by id without extra
 * mapping work.
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
 * Resolves a canonical NFC UID to a staff member.
 *
 * **Swap point**: replace [LocalNfcStaffResolver] with a Room/backend-backed impl
 * when persistent tag management is ready. Callers ([MainActivity] and [NfcProbe])
 * only depend on this interface and do not need to change.
 */
interface NfcStaffResolver {
    /**
     * @param canonicalUid Uppercase colon-separated hex bytes, e.g. "08:7D:F6:83"
     * @return [NfcStaffMatch] if the UID is in the mapping, null if unmapped
     */
    fun resolve(canonicalUid: String): NfcStaffMatch?
}

/**
 * Local test implementation backed by a hardcoded map.
 *
 * Add known tag UIDs here during device onboarding / lab testing.
 * When an admin interface and backend tag registry exist, replace this class with
 * a `RepositoryNfcStaffResolver` that queries Room (synced from backend) — the
 * [NfcStaffResolver] interface is the only change surface.
 *
 * UIDs must be **uppercase colon-separated hex**, matching the canonical form
 * produced by `tag.id.joinToString(":") { "%02X".format(it) }` in
 * [MainActivity.onNfcTagDiscovered].
 */
class LocalNfcStaffResolver : NfcStaffResolver {

    /**
     * uid (canonical) → Triple(staffId, displayName, roleLabel)
     *
     * staffId values must match [SampleData.localAuthStaffRecords] staffIds
     * so a future tap-login path can do `authRepository.signInWithNfc(staffId)`.
     */
    private val mapping: Map<String, Triple<String, String, String>> = mapOf(
        // ─── Lab / Sunmi D3 Mini test tag ──────────────────────────────────
        "08:7D:F6:83" to Triple("staff-1", "Aino Korhonen", "Server"),
        "02:5A:85:BE:C4:40:00" to Triple("staff-1", "Aino Korhonen", "Server"),
        // ─── Add more enrolled tags below as they are onboarded ─────────────
    )

    override fun resolve(canonicalUid: String): NfcStaffMatch? {
        val (staffId, displayName, role) = mapping[canonicalUid] ?: return null
        return NfcStaffMatch(
            uid = canonicalUid,
            staffId = staffId,
            displayName = displayName,
            role = role,
            matchedAtEpochMillis = System.currentTimeMillis(),
        )
    }
}
