package com.airos.pos.app

import com.airos.pos.core.model.StaffAuthRecord
import com.airos.pos.core.model.StaffRole

/**
 * Terminal-local staff auth directory.
 *
 * Temporary POS-local credential list used by [FakeAuthRepository] for offline PIN/NFC sign-in
 * until the backend publishes an authoritative staff contract.
 *
 * Important: these staffId values intentionally mirror the current backend staff/profile and
 * planned-shift seed ids so VUORO / Omat vuorot can match the active POS user to backend data.
 * Delete this file when backend-authoritative staff auth is wired.
 */
object LocalAuthSeed {
    fun localAuthStaffRecords(): List<StaffAuthRecord> = listOf(
        StaffAuthRecord(
            staffId = "staff-aino-korhonen",
            displayName = "Aino Korhonen",
            role = StaffRole.SERVER,
            pin = "2480",
            isManager = false,
            isEnabled = true,
            quickColorHex = "#6E96AA",
        ),
        StaffAuthRecord(
            staffId = "staff-lauri-niemi",
            displayName = "Lauri Niemi",
            role = StaffRole.CASHIER,
            pin = "2580",
            isManager = false,
            isEnabled = true,
            quickColorHex = "#7F8FA6",
        ),
        StaffAuthRecord(
            staffId = "staff-salla-virtanen",
            displayName = "Salla Virtanen",
            role = StaffRole.MANAGER,
            pin = "9901",
            isManager = true,
            isEnabled = true,
            quickColorHex = "#7CA4B8",
        ),
        StaffAuthRecord(
            staffId = "staff-oona-lehtinen",
            displayName = "Oona Lehtinen",
            role = StaffRole.MANAGER,
            pin = "2468",
            isManager = true,
            isEnabled = true,
            quickColorHex = "#93A6B7",
        ),
        StaffAuthRecord(
            staffId = "demo-miikka-martsalo",
            displayName = "Miikka Martsalo",
            role = StaffRole.ADMIN,
            pin = "0000",
            isManager = true,
            isEnabled = true,
            quickColorHex = "#7C3AED",
        ),
        StaffAuthRecord(
            staffId = "test-guest",
            displayName = "Mr. Test Guest",
            role = StaffRole.ADMIN,
            pin = "9999",
            isManager = true,
            isEnabled = true,
            quickColorHex = "#64748B",
        ),
    )
}
