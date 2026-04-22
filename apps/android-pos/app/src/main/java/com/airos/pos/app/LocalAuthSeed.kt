package com.airos.pos.app

import com.airos.pos.core.model.StaffAuthRecord
import com.airos.pos.core.model.StaffRole

/**
 * Terminal-local staff auth directory.
 *
 * This is NOT restaurant truth and NOT demo data. It is the POS-local credential
 * list used by [FakeAuthRepository] for offline PIN/NFC sign-in until the backend
 * publishes an authoritative staff contract. The same records also seed the NFC
 * legacy enrollment table on first boot (see [buildLegacyStaffEnrollmentDefaults]
 * in NfcIdentityRepository.kt) so NFC badges keep working before the first sync.
 *
 * When a backend-authoritative staff directory contract exists, delete this file
 * and wire both call sites to the real source.
 */
object LocalAuthSeed {
    fun localAuthStaffRecords(): List<StaffAuthRecord> = listOf(
        StaffAuthRecord(
            staffId = "staff-1",
            displayName = "Aino Korhonen",
            role = StaffRole.SERVER,
            pin = "2480",
            isManager = false,
            isEnabled = true,
            quickColorHex = "#6E96AA",
        ),
        StaffAuthRecord(
            staffId = "staff-2",
            displayName = "Lauri Niemi",
            role = StaffRole.SERVER,
            pin = "2580",
            isManager = false,
            isEnabled = true,
            quickColorHex = "#7F8FA6",
        ),
        StaffAuthRecord(
            staffId = "staff-3",
            displayName = "Salla Virtanen",
            role = StaffRole.MANAGER,
            pin = "9901",
            isManager = true,
            isEnabled = true,
            quickColorHex = "#7CA4B8",
        ),
        StaffAuthRecord(
            staffId = "staff-4",
            displayName = "Oona Lehtinen",
            role = StaffRole.ADMIN,
            pin = "2468",
            isManager = true,
            isEnabled = true,
            quickColorHex = "#93A6B7",
        ),
    )
}
