package com.airos.pos.feature.ticket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.common.CentsFormatter
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.MenuItem
import com.airos.pos.core.model.Ticket
import com.airos.pos.core.ui.KeyValueRow
import com.airos.pos.core.ui.PosPane
import com.airos.pos.core.ui.StatusBanner
import com.airos.pos.domain.MenuRepository
import com.airos.pos.domain.TicketRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TicketUiState(
    val tableId: String,
    val ticket: Ticket? = null,
    val menuItems: List<MenuItem> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

class TicketViewModel(
    private val tableId: String,
    private val ticketRepository: TicketRepository,
    private val menuRepository: MenuRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TicketUiState(tableId = tableId))
    val uiState: StateFlow<TicketUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            ticketRepository.observeTicketForTable(tableId).collect { ticket ->
                mutableState.update { it.copy(ticket = ticket, busy = false) }
                updateCustomerDisplayTotal(ticket?.totalCents)
            }
        }
        viewModelScope.launch {
            menuRepository.observeMenuItems().collect { menuItems ->
                mutableState.update { it.copy(menuItems = menuItems) }
            }
        }
    }

    fun addItem(menuItemId: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            when (val result = ticketRepository.addItem(tableId, menuItemId)) {
                is PosResult.Success -> mutableState.update { it.copy(ticket = result.value, busy = false) }
                is PosResult.Failure -> mutableState.update { it.copy(message = result.message, busy = false) }
            }
        }
    }

    fun sendToKitchen() {
        val ticketId = mutableState.value.ticket?.id ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            when (val result = ticketRepository.sendToKitchen(ticketId)) {
                is PosResult.Success -> mutableState.update {
                    it.copy(
                        ticket = result.value,
                        busy = false,
                        message = "Sent to kitchen.",
                    )
                }
                is PosResult.Failure -> mutableState.update { it.copy(message = result.message, busy = false) }
            }
        }
    }

    private fun updateCustomerDisplayTotal(totalCents: Int?) {
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val application = activityThreadClass.getMethod("currentApplication").invoke(null) ?: return
            val appContainer = application.javaClass.getMethod("getAppContainer").invoke(application) ?: return
            val customerDisplayService =
                appContainer.javaClass.getMethod("getCustomerDisplayService").invoke(appContainer) ?: return
            customerDisplayService.javaClass.methods
                .firstOrNull { method ->
                    method.name == "updateCustomerTotalDisplay" && method.parameterCount == 1
                }
                ?.invoke(customerDisplayService, totalCents)
        }.onFailure { error ->
            Log.w(
                "TicketCustomerDisplay",
                "ticketCustomerDisplayUpdate failed totalCents=${totalCents ?: "-"} " +
                    "class=${error.javaClass.name} message=${error.message ?: "-"}",
            )
        }
    }

    companion object {
        fun factory(
            tableId: String,
            ticketRepository: TicketRepository,
            menuRepository: MenuRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { TicketViewModel(tableId, ticketRepository, menuRepository) }
        }
    }
}

@Composable
fun TicketScreen(
    state: TicketUiState,
    onAddItem: (String) -> Unit,
    onSendToKitchen: () -> Unit,
    onGoToPayment: (String) -> Unit,
    onGoToScanner: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PosPane(
            title = "Ticket • ${state.tableId}",
            supportingText = "Business rules stay in the ViewModel and repository layer, not in composables.",
            modifier = Modifier.weight(1f),
        ) {
            state.message?.let { StatusBanner(text = it, tint = MaterialTheme.colorScheme.primary) }
            if (state.ticket == null) {
                Text("No active ticket yet. Tap a menu item to start.")
            } else {
                KeyValueRow("Status", state.ticket.status.name)
                KeyValueRow("Subtotal", CentsFormatter.format(state.ticket.subtotalCents))
                KeyValueRow("Tax", CentsFormatter.format(state.ticket.taxCents))
                KeyValueRow("Total", CentsFormatter.format(state.ticket.totalCents))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.ticket.lines) { line ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("${line.quantity}x ${line.name}")
                                Text(CentsFormatter.format(line.totalPriceCents))
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSendToKitchen, enabled = !state.busy) { Text("Send to kitchen") }
                    Button(onClick = { onGoToPayment(state.ticket.id) }) { Text("Payment") }
                    Button(onClick = onGoToScanner) { Text("Scanner") }
                }
            }
        }

        PosPane(
            title = "Menu",
            supportingText = "Large touch targets optimized for tablet ordering.",
            modifier = Modifier.weight(1.2f),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.menuItems) { item ->
                    Surface(
                        modifier = Modifier
                            .height(130.dp)
                            .clickable { onAddItem(item.id) },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = item.name, fontWeight = FontWeight.Bold)
                            Text(text = item.category)
                            Text(text = CentsFormatter.format(item.priceCents))
                        }
                    }
                }
            }
        }
    }
}
