package com.airos.pos.app

import com.airos.pos.core.database.dao.OpenSaleDao
import com.airos.pos.core.database.entity.OpenSaleEntity
import com.airos.pos.core.database.entity.OpenSaleLineEntity
import com.airos.pos.core.model.PersistedOpenSale
import com.airos.pos.core.model.PersistedOpenSaleLine
import com.airos.pos.domain.OpenSaleRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class RoomOpenSaleRepository(
    private val openSaleDao: OpenSaleDao,
) : OpenSaleRepository {
    override fun observeOpenSales(): Flow<List<PersistedOpenSale>> =
        openSaleDao.observeAllOpen().combine(openSaleDao.observeAllLines()) { sales, allLines ->
            val linesBySaleId = allLines.groupBy { it.saleId }
            sales.map { sale ->
                sale.toModel(linesBySaleId[sale.saleId].orEmpty().map(OpenSaleLineEntity::toModel))
            }
        }

    override suspend fun loadOpenSale(): PersistedOpenSale? {
        val entity = openSaleDao.loadOpenSale() ?: return null
        val lines = openSaleDao.loadLines(entity.saleId).map(OpenSaleLineEntity::toModel)
        return entity.toModel(lines)
    }

    override suspend fun createOpenSale(serviceSpotId: String?, serviceSpotLabel: String?): PersistedOpenSale {
        val now = System.currentTimeMillis()
        val saleId = UUID.randomUUID().toString()
        val entity = OpenSaleEntity(
            saleId = saleId,
            serviceSpotId = serviceSpotId,
            serviceSpotLabel = serviceSpotLabel,
            status = "OPEN",
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        openSaleDao.upsertSale(entity)
        return entity.toModel(emptyList())
    }

    override suspend fun saveLines(saleId: String, lines: List<PersistedOpenSaleLine>) {
        openSaleDao.deleteLinesForSale(saleId)
        if (lines.isEmpty()) return
        openSaleDao.upsertLines(lines.map(PersistedOpenSaleLine::toEntity))
    }

    override suspend fun assignServiceSpot(saleId: String, serviceSpotId: String?, serviceSpotLabel: String?) {
        openSaleDao.updateServiceSpot(
            saleId = saleId,
            serviceSpotId = serviceSpotId,
            serviceSpotLabel = serviceSpotLabel,
            updated = System.currentTimeMillis(),
        )
    }

    override suspend fun clearOpenSale(saleId: String) {
        openSaleDao.deleteLinesForSale(saleId)
        openSaleDao.deleteSale(saleId)
    }

    override suspend fun closeOpenSale(saleId: String) {
        openSaleDao.closeSale(saleId, System.currentTimeMillis())
    }
}

private fun OpenSaleEntity.toModel(lines: List<PersistedOpenSaleLine>): PersistedOpenSale {
    return PersistedOpenSale(
        saleId = saleId,
        serviceSpotId = serviceSpotId,
        serviceSpotLabel = serviceSpotLabel,
        status = status,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        lines = lines,
    )
}

private fun OpenSaleLineEntity.toModel(): PersistedOpenSaleLine {
    return PersistedOpenSaleLine(
        saleId = saleId,
        itemId = itemId,
        name = name,
        quantity = quantity,
        unitPriceCents = unitPriceCents,
        taxRatePercent = taxRatePercent,
        discountPercent = discountPercent,
        discountAmountCents = discountAmountCents,
    )
}

private fun PersistedOpenSaleLine.toEntity(): OpenSaleLineEntity {
    return OpenSaleLineEntity(
        saleId = saleId,
        itemId = itemId,
        name = name,
        quantity = quantity,
        unitPriceCents = unitPriceCents,
        taxRatePercent = taxRatePercent,
        discountPercent = discountPercent,
        discountAmountCents = discountAmountCents,
    )
}
