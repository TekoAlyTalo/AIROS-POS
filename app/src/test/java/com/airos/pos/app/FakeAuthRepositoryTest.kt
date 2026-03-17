package com.airos.pos.app

import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.ManagerOverrideReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class FakeAuthRepositoryTest {
    @Test
    fun signInWithPin_returnsSessionForValidPin() = runBlocking {
        val repository = FakeAuthRepository(SampleData.localAuthStaffRecords())
        val credential = SampleData.localAuthStaffRecords().first()

        val result = repository.signInWithPin(credential.staffId, credential.pin)

        assertThat(result is PosResult.Success).isTrue()
        assertThat(repository.activeSession.value?.staffId).isEqualTo(credential.staffId)
        assertThat(repository.activeSession.value?.displayName).isEqualTo(credential.displayName)
    }

    @Test
    fun signInWithPin_returnsFailureForInvalidPin() = runBlocking {
        val repository = FakeAuthRepository(SampleData.localAuthStaffRecords())
        val credential = SampleData.localAuthStaffRecords().first()

        val result = repository.signInWithPin(credential.staffId, "0000")

        assertThat(result is PosResult.Failure).isTrue()
        assertThat(repository.activeSession.value).isNull()
    }

    @Test
    fun verifyManagerOverride_returnsGrantForEnabledManager() = runBlocking {
        val repository = FakeAuthRepository(SampleData.localAuthStaffRecords())
        val manager = SampleData.localAuthStaffRecords().first { it.isManager }

        val result = repository.verifyManagerOverride(
            managerStaffId = manager.staffId,
            pin = manager.pin,
            reason = ManagerOverrideReason.SETTINGS_CHANGE,
        )

        assertThat(result is PosResult.Success).isTrue()
        val grant = (result as PosResult.Success).value
        assertThat(grant.managerStaffId).isEqualTo(manager.staffId)
        assertThat(grant.managerDisplayName).isEqualTo(manager.displayName)
    }

    @Test
    fun verifyManagerOverride_rejectsWrongManagerPin() = runBlocking {
        val repository = FakeAuthRepository(SampleData.localAuthStaffRecords())
        val manager = SampleData.localAuthStaffRecords().first { it.isManager }

        val result = repository.verifyManagerOverride(
            managerStaffId = manager.staffId,
            pin = "1111",
            reason = ManagerOverrideReason.REFUND,
        )

        assertThat(result is PosResult.Failure).isTrue()
    }
}
