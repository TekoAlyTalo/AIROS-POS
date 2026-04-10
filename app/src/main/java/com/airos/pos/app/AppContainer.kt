package com.airos.pos.app

import android.content.Context
import com.airos.pos.core.database.AirosPosDatabase
import com.airos.pos.core.datastore.TerminalPreferencesStore
import com.airos.pos.core.model.TerminalSettings
import com.airos.pos.device.camera.AndroidTorchService
import com.airos.pos.device.camera.CameraPreviewService
import com.airos.pos.device.camera.SunmiCameraPreviewService
import com.airos.pos.device.camera.TorchService
import com.airos.pos.device.cashdrawer.CashDrawerService
import com.airos.pos.device.cashdrawer.SunmiCashDrawerService
import com.airos.pos.device.platform.AndroidDeviceInfoService
import com.airos.pos.device.platform.CustomerDisplayService
import com.airos.pos.device.platform.DeviceInfoService
import com.airos.pos.device.platform.SunmiCustomerDisplayService
import com.airos.pos.device.printer.PrinterService
import com.airos.pos.device.printer.SunmiPrinterService
import com.airos.pos.device.scanner.ScannerService
import com.airos.pos.device.scanner.SunmiScannerService
import com.airos.pos.domain.AirosPosLedgerHttpClient
import com.airos.pos.domain.AuthRepository
import com.airos.pos.domain.DefaultAirosPosLedgerHttpClient
import com.airos.pos.domain.KitchenRepository
import com.airos.pos.domain.MenuRepository
import com.airos.pos.domain.PaymentRepository
import com.airos.pos.domain.SettingsRepository
import com.airos.pos.domain.ShiftRepository
import com.airos.pos.domain.SyncQueueRepository
import com.airos.pos.domain.TableRepository
import com.airos.pos.domain.TicketRepository
import com.airos.pos.sync.InMemorySyncQueueRepository
import com.airos.pos.sync.SyncCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

interface AppContainer {
    val database: AirosPosDatabase
    val terminalPreferencesStore: TerminalPreferencesStore
    val authRepository: AuthRepository
    val shiftRepository: ShiftRepository
    val tableRepository: TableRepository
    val menuRepository: MenuRepository
    val ticketRepository: TicketRepository
    val kitchenRepository: KitchenRepository
    val paymentRepository: PaymentRepository
    val settingsRepository: SettingsRepository
    val syncQueueRepository: SyncQueueRepository
    val syncCoordinator: SyncCoordinator
    val printerService: PrinterService
    val scannerService: ScannerService
    val cameraPreviewService: CameraPreviewService
    val torchService: TorchService
    val cashDrawerService: CashDrawerService
    val deviceInfoService: DeviceInfoService
    val customerDisplayService: CustomerDisplayService
}

class DefaultAppContainer(
    context: Context,
) : AppContainer {
    private val appContext = context.applicationContext
    private val store = FakePosStore()
    private val androidDeviceInfoService = AndroidDeviceInfoService()

    override val database: AirosPosDatabase = AirosPosDatabase.build(appContext)
    override val terminalPreferencesStore: TerminalPreferencesStore = TerminalPreferencesStore(appContext)
    override val syncQueueRepository: SyncQueueRepository = InMemorySyncQueueRepository()
    override val syncCoordinator: SyncCoordinator = SyncCoordinator(syncQueueRepository)
    override val authRepository: AuthRepository = FakeAuthRepository(SampleData.localAuthStaffRecords())
    override val shiftRepository: ShiftRepository = FakeShiftRepository(store, syncQueueRepository)
    override val tableRepository: TableRepository = FakeTableRepository(store, syncQueueRepository)
    override val menuRepository: MenuRepository = BackendMenuRepository(
        backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
        menuCacheDao = database.backendMenuCacheDao(),
    )
    override val ticketRepository: TicketRepository = FakeTicketRepository(store, authRepository, syncQueueRepository)
    override val kitchenRepository: KitchenRepository = FakeKitchenRepository(store)
    override val settingsRepository: SettingsRepository = DataStoreSettingsRepository(terminalPreferencesStore)
    override val printerService: PrinterService = SunmiPrinterService(appContext)
    override val scannerService: ScannerService = SunmiScannerService(appContext)
    override val cameraPreviewService: CameraPreviewService = SunmiCameraPreviewService(appContext)
    override val torchService: TorchService = AndroidTorchService(appContext)
    override val cashDrawerService: CashDrawerService = SunmiCashDrawerService(appContext)
    override val deviceInfoService: DeviceInfoService = androidDeviceInfoService
    override val customerDisplayService: CustomerDisplayService =
        SunmiCustomerDisplayService(
            context = appContext,
            deviceInfoService = androidDeviceInfoService,
        )

    /**
     * Best-effort current terminal settings lookup.
     *
     * We keep this local and synchronous on purpose so the payment finalization path can
     * ask for the latest configured backend URL / terminal name without refactoring the
     * surrounding app architecture right now.
     */
    private fun currentTerminalSettings(): TerminalSettings = runBlocking {
        terminalPreferencesStore.settings.first()
    }

    private fun currentLedgerBackendBaseUrl(): String? {
        val value = currentTerminalSettings().edgeBaseUrl.trim()
        return value.ifBlank { null }
    }

    private fun currentTerminalName(): String? {
        val value = currentTerminalSettings().terminalName.trim()
        return value.ifBlank { null }
    }

    private fun currentTerminalId(): String? {
        // For now we reuse terminal name as the stable terminal identifier until a dedicated
        // device/terminal id is introduced in settings or device registration.
        return currentTerminalName()
    }

    private fun currentCashierStaffId(): String? = authRepository.activeSession.value?.staffId

    private fun currentCashierName(): String? = authRepository.activeSession.value?.displayName


    private val restaurantReceiptSettingsClient: RestaurantReceiptSettingsClient by lazy {
        DefaultRestaurantReceiptSettingsClient(
            backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
        )
    }

    private val ledgerHttpClient: AirosPosLedgerHttpClient by lazy {
        DefaultAirosPosLedgerHttpClient(
            backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
        )
    }

    override val paymentRepository: PaymentRepository = FakePaymentRepository(
        store = store,
        syncQueueRepository = syncQueueRepository,
        ledgerHttpClient = ledgerHttpClient,
        ledgerBackendBaseUrlProvider = { currentLedgerBackendBaseUrl() },
        terminalIdProvider = { currentTerminalId() },
        terminalNameProvider = { currentTerminalName() },
        restaurantIdProvider = { null },
        cashierStaffIdProvider = { currentCashierStaffId() },
        cashierNameProvider = { currentCashierName() },
        restaurantReceiptSettingsClient = restaurantReceiptSettingsClient,
    )
}
