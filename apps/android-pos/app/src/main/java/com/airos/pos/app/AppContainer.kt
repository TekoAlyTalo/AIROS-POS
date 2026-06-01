package com.airos.pos.app

import android.content.Context
import com.airos.pos.core.database.AirosPosDatabase
import com.airos.pos.core.datastore.TerminalPreferencesStore
import com.airos.pos.core.datastore.StaffUiPreferencesStore
import com.airos.pos.core.model.FloorMap
import com.airos.pos.core.model.TerminalSettings
import com.airos.pos.device.camera.AndroidTorchService
import com.airos.pos.device.camera.CameraPreviewService
import com.airos.pos.device.camera.CameraScannerController
import com.airos.pos.device.camera.CameraXMlKitScannerController
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
import com.airos.pos.app.RoomOpenSaleRepository
import com.airos.pos.domain.AirosPosLedgerHttpClient
import com.airos.pos.domain.AuthRepository
import com.airos.pos.domain.CashLedgerRepository
import com.airos.pos.domain.DefaultAirosPosLedgerHttpClient
import com.airos.pos.domain.KitchenRepository
import com.airos.pos.domain.MenuRepository
import com.airos.pos.domain.NfcIdentityRepository
import com.airos.pos.domain.NfcIdentitySyncClient
import com.airos.pos.domain.DefaultNfcIdentitySyncClient
import com.airos.pos.domain.OpenSaleRepository
import com.airos.pos.domain.PaymentRepository
import com.airos.pos.domain.SalesDayReportRepository
import com.airos.pos.domain.SettingsRepository
import com.airos.pos.domain.StaffUiPreferencesRepository
import com.airos.pos.domain.ShiftRepository
import com.airos.pos.domain.ShiftScheduleRepository
import com.airos.pos.domain.SyncQueueRepository
import com.airos.pos.domain.TableRepository
import com.airos.pos.domain.TicketRepository
import com.airos.pos.sync.InMemorySyncQueueRepository
import com.airos.pos.sync.SyncCoordinator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

interface AppContainer {
    val database: AirosPosDatabase
    val terminalPreferencesStore: TerminalPreferencesStore
    val authRepository: AuthRepository
    val openSaleRepository: OpenSaleRepository
    val shiftRepository: ShiftRepository
    val cashLedgerRepository: CashLedgerRepository
    val shiftScheduleRepository: ShiftScheduleRepository
    val tableRepository: TableRepository
    val menuRepository: MenuRepository
    val ticketRepository: TicketRepository
    val kitchenRepository: KitchenRepository
    val paymentRepository: PaymentRepository
    val ledgerHttpClient: AirosPosLedgerHttpClient
    val salesDayReportRepository: SalesDayReportRepository
    val settingsRepository: SettingsRepository
    val staffUiPreferencesRepository: StaffUiPreferencesRepository
    val reservationsRepository: BackendReservationsRepository
    val nfcIdentityRepository: NfcIdentityRepository
    val nfcStaffResolver: NfcStaffResolver
    val syncQueueRepository: SyncQueueRepository
    val syncCoordinator: SyncCoordinator
    val printerService: PrinterService
    val scannerService: ScannerService
    val cameraPreviewService: CameraPreviewService
    val cameraScannerController: CameraScannerController
    val torchService: TorchService
    val cashDrawerService: CashDrawerService
    val deviceInfoService: DeviceInfoService
    val customerDisplayService: CustomerDisplayService
    val worktimeAttendanceClient: WorktimeAttendanceClient
    val worktimeAttendanceRepository: WorktimeAttendanceRepository
    val reportsBackendBaseUrlProvider: () -> String
}

class DefaultAppContainer(
    context: Context,
) : AppContainer {
    private val appContext = context.applicationContext
    private val nfcIdentitySyncClient = DefaultNfcIdentitySyncClient(
        backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
    )
    private val androidDeviceInfoService = AndroidDeviceInfoService()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val database: AirosPosDatabase = AirosPosDatabase.build(appContext)
    override val terminalPreferencesStore: TerminalPreferencesStore = TerminalPreferencesStore(appContext)

    // Durable local floor-map cache. Populated only by the write-through sink below
    // whenever BackendTruthTableRepository publishes a fresh backend-authoritative
    // snapshot. Consumed on cold start so the Tables view can render the last
    // honest layout without backend reachability.
    private val floorMapCacheStore = FloorMapCacheStore(database.cachedFloorMapDao())
    private val hydratedFloorMap: FloorMap? = runBlocking { floorMapCacheStore.loadPersistedFloorMap() }
    private val store = if (hydratedFloorMap != null) FakePosStore(initialFloorMap = hydratedFloorMap) else FakePosStore()

    // Stable technical terminal identifier — persisted once per install. Drives
    // attendance sync metadata key + terminal sequence numbering. Intentionally
    // decoupled from the mutable user-facing terminal name.
    private val terminalInstallationId: String = runBlocking {
        terminalPreferencesStore.terminalInstallationId()
    }

    private val staffUiPreferencesStore: StaffUiPreferencesStore = StaffUiPreferencesStore(appContext)
    override val syncQueueRepository: SyncQueueRepository = InMemorySyncQueueRepository()
    override val syncCoordinator: SyncCoordinator = SyncCoordinator(syncQueueRepository)
    override val authRepository: AuthRepository = FakeAuthRepository(LocalAuthSeed.localAuthStaffRecords())
    private val roomNfcIdentityRepository = RoomNfcIdentityRepository(database, nfcIdentitySyncClient)
    override val nfcIdentityRepository: NfcIdentityRepository = roomNfcIdentityRepository
    override val nfcStaffResolver: NfcStaffResolver = RepositoryNfcStaffResolver(roomNfcIdentityRepository)
    override val openSaleRepository: OpenSaleRepository = RoomOpenSaleRepository(database.openSaleDao())
    override val shiftRepository: ShiftRepository = RoomShiftRepository(database)
    override val cashLedgerRepository: CashLedgerRepository = RoomCashLedgerRepository(database)
    override val shiftScheduleRepository: ShiftScheduleRepository = BackendShiftScheduleRepository(
        backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
        restaurantKeyProvider = { currentRestaurantKey() },
    )
    private val localTableRepository = FakeTableRepository(store, syncQueueRepository)
    // Write-through sink: apply backend truth to the in-memory delegate AND
    // persist it to the durable floor-map cache so the next cold start has a
    // real snapshot to bootstrap from without backend reachability.
    private val persistingFloorMapSink = object : BackendAuthoritativeFloorMapSink {
        override fun replaceBackendAuthoritativeFloorMap(floorMap: FloorMap) {
            localTableRepository.replaceBackendAuthoritativeFloorMap(floorMap)
            appScope.launch {
                try {
                    floorMapCacheStore.persist(floorMap)
                } catch (t: Throwable) {
                    Log.w(
                        "AIROS",
                        "[AppContainer] floor map cache persist failed: ${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            }
        }
    }
    override val tableRepository: TableRepository = BackendTruthTableRepository(
        delegate = localTableRepository,
        floorMapSink = persistingFloorMapSink,
        openSaleRepository = openSaleRepository,
        backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
    )
    private val productImageCache: ProductImageCache = ProductImageCache(appContext)
    override val menuRepository: MenuRepository = BackendMenuRepository(
        backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
        menuCacheDao = database.backendMenuCacheDao(),
        imageCache = productImageCache,
        restaurantKeyProvider = { currentRestaurantKey() },
    )
    override val ticketRepository: TicketRepository = FakeTicketRepository(store, authRepository, syncQueueRepository)
    override val kitchenRepository: KitchenRepository = FakeKitchenRepository(store)
    override val settingsRepository: SettingsRepository = DataStoreSettingsRepository(terminalPreferencesStore)
    override val staffUiPreferencesRepository: StaffUiPreferencesRepository =
        DataStoreStaffUiPreferencesRepository(staffUiPreferencesStore)
    override val reservationsRepository: BackendReservationsRepository = BackendReservationsRepository(
        backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
    )
    override val printerService: PrinterService = SunmiPrinterService(appContext)
    override val scannerService: ScannerService = SunmiScannerService(appContext)
    override val cameraPreviewService: CameraPreviewService = SunmiCameraPreviewService(appContext)
    override val cameraScannerController: CameraScannerController = CameraXMlKitScannerController(appContext)
    override val torchService: TorchService = AndroidTorchService(appContext)
    override val cashDrawerService: CashDrawerService = SunmiCashDrawerService(appContext)
    override val deviceInfoService: DeviceInfoService = androidDeviceInfoService
    override val customerDisplayService: CustomerDisplayService =
        SunmiCustomerDisplayService(
            context = appContext,
            deviceInfoService = androidDeviceInfoService,
        )
    override val worktimeAttendanceClient: WorktimeAttendanceClient = WorktimeAttendanceClient(
        backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
    )
    override val worktimeAttendanceRepository: WorktimeAttendanceRepository = WorktimeAttendanceRepository(
        database = database,
        client = worktimeAttendanceClient,
        ownerAccountIdProvider = { currentOwnerAccountId() },
        restaurantKeyProvider = { currentRestaurantKey() },
        terminalIdProvider = { currentTerminalId() },
    )
    override val reportsBackendBaseUrlProvider: () -> String = { currentLedgerBackendBaseUrl().orEmpty() }

    init {
        runBlocking {
            roomNfcIdentityRepository.seedLegacyStaffEnrollmentsIfEmpty(
                buildLegacyStaffEnrollmentDefaults(LocalAuthSeed.localAuthStaffRecords()),
            )
        }
        Log.i(
            "AIROS",
            "[AppContainer] Attendance scope wiring: terminalInstallationId=$terminalInstallationId " +
                "restaurantKey=${currentRestaurantKey()} restaurantKeySource=terminal_preferences " +
                "ownerAccountIdSourceAvailable=false",
        )
        if (currentOwnerAccountId() == null) {
            Log.w(
                "AIROS",
                "[AppContainer] Attendance scope BLOCKER: no owner_account_id source wired. " +
                    "Neither AuthRepository (Android) nor ravintola_backend currently model " +
                    "owner/account identity. Attendance sync will omit owner_account_id until " +
                    "a real source is introduced on both sides.",
            )
        }
    }

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

    private fun currentTerminalId(): String {
        // Stable technical id — see [terminalInstallationId]. Independent of the
        // user-facing terminal name so renames cannot break sync continuity.
        return terminalInstallationId
    }

    // Placeholder until AuthRepository exposes owner/account identity. Neither the
    // Android auth layer nor the ravintola_backend schema models owner/account yet,
    // so there is no honest source to wire here. The init block logs a blocker so
    // the missing source stays visible in logs rather than being silently treated
    // as a real null.
    private fun currentOwnerAccountId(): String? = null

    // Restaurant scope is now sourced from persisted terminal preferences (DataStore)
    // so attendance sync metadata and menu fetch use the same single source of truth.
    // Default preserved ("ravintola_default") so existing installs continue unchanged.
    private fun currentRestaurantKey(): String {
        val value = currentTerminalSettings().restaurantKey.trim()
        return value.ifBlank { "ravintola_default" }
    }

    private fun currentCashierStaffId(): String? = authRepository.activeSession.value?.staffId

    private fun currentCashierName(): String? = authRepository.activeSession.value?.displayName

    private fun currentCashierSessionId(): String? = authRepository.activeSession.value?.sessionId

    private fun currentCashierAuthMethodSnapshot(): String? = authRepository.activeSession.value?.authMethodSnapshot


    private val receiptSettingsDurableCache: ReceiptSettingsDurableCache by lazy {
        ReceiptSettingsDurableCache(appContext)
    }

    private val restaurantReceiptSettingsClient: RestaurantReceiptSettingsClient by lazy {
        CachingRestaurantReceiptSettingsClient(
            delegate = DefaultRestaurantReceiptSettingsClient(
                backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
            ),
            cache = receiptSettingsDurableCache,
        )
    }

    override val ledgerHttpClient: AirosPosLedgerHttpClient by lazy {
        DefaultAirosPosLedgerHttpClient(
            backendBaseUrlProvider = { currentLedgerBackendBaseUrl().orEmpty() },
        )
    }

    private val salesLedgerOutboxRepository: SalesLedgerOutboxRepository by lazy {
        SalesLedgerOutboxRepository(
            dao = database.salesLedgerOutboxDao(),
            ledgerHttpClient = ledgerHttpClient,
        )
    }

    override val salesDayReportRepository: SalesDayReportRepository by lazy {
        RoomSalesDayReportRepository(database.localFinalizedSalesReportDao())
    }

    override val paymentRepository: PaymentRepository = LocalPaymentRepository(
        store = store,
        syncQueueRepository = syncQueueRepository,
        ledgerHttpClient = ledgerHttpClient,
        ledgerBackendBaseUrlProvider = { currentLedgerBackendBaseUrl() },
        terminalIdProvider = { currentTerminalId() },
        terminalNameProvider = { currentTerminalName() },
        restaurantIdProvider = { null },
        cashierStaffIdProvider = { currentCashierStaffId() },
        cashierNameProvider = { currentCashierName() },
        cashierSessionIdProvider = { currentCashierSessionId() },
        cashierAuthMethodSnapshotProvider = { currentCashierAuthMethodSnapshot() },
        restaurantReceiptSettingsClient = restaurantReceiptSettingsClient,
        saleSyncOutboxRepository = salesLedgerOutboxRepository,
        cashLedgerRepository = cashLedgerRepository,
        salesDayReportRepository = salesDayReportRepository,
    )

    init {
        appScope.launch {
            while (true) {
                try {
                    salesLedgerOutboxRepository.syncPendingNow()
                } catch (t: Throwable) {
                    Log.e(
                        "AIROS_SALES_OUTBOX",
                        "background sync failed: ${t.javaClass.simpleName}: ${t.message}",
                        t,
                    )
                }
                delay(30_000L)
            }
        }
    }
}
