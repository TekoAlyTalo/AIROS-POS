package com.airos.pos.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NFC tag event captured via Android reader mode.
 *
 * v1 — probe / diagnostics only. No staff mapping yet.
 * Next iteration: resolve [uid] to a StaffMember via a local lookup.
 */
data class NfcTagEvent(
    /** Colon-separated hex bytes, e.g. "04:AB:CD:12:34:56:78" */
    val uid: String,
    /** Short tech names (package prefix stripped), e.g. ["NfcA", "Ndef"] */
    val techList: List<String>,
    val action: String,
    val timestamp: Long,
)

/**
 * Aggregate NFC state held at the app level.
 *
 * [lastTagEvent]        — most recent raw tag read (uid + tech list).
 * [lastStaffResolution] — result of resolving that tag's UID to a staff member.
 *                         Null until the first tag is tapped.
 *                         [NfcStaffResolution.Matched] → known staff.
 *                         [NfcStaffResolution.Unknown] → unmapped tag (log UID for onboarding).
 */
data class NfcAdapterStatus(
    val adapterPresent: Boolean = false,
    val enabled: Boolean = false,
    val lastTagEvent: NfcTagEvent? = null,
    val lastStaffResolution: NfcStaffResolution? = null,
)

/**
 * Lightweight app-level NFC probe state holder.
 *
 * Written exclusively by [MainActivity]:
 *   - adapter presence / enabled state on onCreate / onResume
 *   - tag events + staff resolution via NfcAdapter.enableReaderMode callback
 *
 * Read by the Shift diagnostics composable in AirosPosApp.
 *
 * Design note: kept as a plain object at this probe stage. When staff-NFC
 * mapping is promoted to a full auth flow, move this state into a proper
 * NfcService injected via AppContainer.
 */
object NfcProbe {

    private val _status = MutableStateFlow(NfcAdapterStatus())
    val status: StateFlow<NfcAdapterStatus> = _status.asStateFlow()

    fun updateAdapterStatus(adapterPresent: Boolean, enabled: Boolean) {
        _status.value = _status.value.copy(
            adapterPresent = adapterPresent,
            enabled = enabled,
        )
    }

    fun onTagDetected(event: NfcTagEvent) {
        _status.value = _status.value.copy(lastTagEvent = event)
    }

    /** Called immediately after [onTagDetected] with the staff-resolver outcome. */
    fun onTagResolved(resolution: NfcStaffResolution) {
        _status.value = _status.value.copy(lastStaffResolution = resolution)
    }
}
