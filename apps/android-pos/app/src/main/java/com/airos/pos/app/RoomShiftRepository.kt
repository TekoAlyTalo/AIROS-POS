package com.airos.pos.app

import androidx.room.withTransaction
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.database.AirosPosDatabase
import com.airos.pos.core.database.entity.ShiftLocalEntity
import com.airos.pos.core.model.PosShift
import com.airos.pos.core.model.ShiftStatus
import com.airos.pos.domain.ShiftRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomShiftRepository(
    private val database: AirosPosDatabase,
) : ShiftRepository {
    private val dao = database.shiftDao()

    override fun observeCurrentShift(): Flow<PosShift?> {
        return dao.observeOpenShift().map { entity -> entity?.toModel() }
    }

    override suspend fun openShift(openingFloatCents: Int, staffId: String): PosResult<PosShift> {
        return try {
            database.withTransaction {
                val existing = dao.getOpenShiftOnce()
                if (existing != null) {
                    PosResult.Failure("Ravintola on jo avoinna.")
                } else {
                    val now = System.currentTimeMillis()
                    val entity = ShiftLocalEntity(
                        id = "shift-${UUID.randomUUID()}",
                        openedByStaffId = staffId,
                        openedAtEpochMillis = now,
                        status = ShiftStatus.OPEN.name,
                        openingFloatCents = openingFloatCents,
                        expectedCashCents = openingFloatCents,
                        countedCashCents = null,
                        closedAtEpochMillis = null,
                    )
                    dao.upsert(entity)
                    PosResult.Success(entity.toModel())
                }
            }
        } catch (t: Throwable) {
            PosResult.Failure("Restaurant shift open failed: ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
        }
    }

    override suspend fun closeShift(countedCashCents: Int, managerPin: String?): PosResult<PosShift> {
        return try {
            val closed = database.withTransaction {
                val current = dao.getOpenShiftOnce() ?: return@withTransaction null
                val updated = current.copy(
                    status = ShiftStatus.CLOSED.name,
                    countedCashCents = countedCashCents,
                    closedAtEpochMillis = System.currentTimeMillis(),
                )
                dao.upsert(updated)
                updated.toModel()
            }
            if (closed == null) {
                PosResult.Failure("Ei avointa ravintolavuoroa suljettavaksi.")
            } else {
                PosResult.Success(closed)
            }
        } catch (t: Throwable) {
            PosResult.Failure("Restaurant shift close failed: ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
        }
    }

    private fun ShiftLocalEntity.toModel(): PosShift {
        return PosShift(
            id = id,
            openedByStaffId = openedByStaffId,
            openedAtEpochMillis = openedAtEpochMillis,
            status = ShiftStatus.valueOf(status),
            openingFloatCents = openingFloatCents,
            expectedCashCents = expectedCashCents,
            countedCashCents = countedCashCents,
            closedAtEpochMillis = closedAtEpochMillis,
        )
    }
}
