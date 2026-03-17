package com.airos.pos.feature.kitchen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airos.pos.core.model.KitchenOrder
import com.airos.pos.domain.KitchenRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KitchenUiState(
    val isLoading: Boolean = true,
    val orders: List<KitchenOrder> = emptyList(),
    val errorMessage: String? = null,
)

class KitchenViewModel(
    private val repository: KitchenRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(KitchenUiState())
    val uiState: StateFlow<KitchenUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeKitchenOrders().collect { orders ->
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        orders = orders,
                        errorMessage = null,
                    )
                }
            }
        }
    }

    fun markReady(ticketId: String) {
        viewModelScope.launch {
            // Best-effort; errors just end up as an error message in the UI,
            // they should not crash the app.
            runCatching { repository.markReady(ticketId) }
                .onFailure { throwable ->
                    mutableState.update {
                        it.copy(errorMessage = throwable.message ?: "Failed to mark order ready")
                    }
                }
        }
    }

    companion object {
        fun factory(
            repository: KitchenRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { KitchenViewModel(repository) }
        }
    }
}

@Composable
fun KitchenScreen(
    state: KitchenUiState,
    onMarkReady: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Kitchen",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.orders) { order ->
                KitchenOrderCard(
                    order = order,
                    onMarkReady = { onMarkReady(order.ticketId) },
                )
            }
        }
    }
}

@Composable
private fun KitchenOrderCard(
    order: KitchenOrder,
    onMarkReady: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(
                text = "${order.tableLabel} • ${order.ticketId}",
                style = MaterialTheme.typography.titleMedium,
            )

            if (order.itemSummaries.isNotEmpty()) {
                Text(
                    text = order.itemSummaries.joinToString(separator = ", "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }

            Button(onClick = onMarkReady) {
                Text("Mark ready")
            }
        }
    }
}
