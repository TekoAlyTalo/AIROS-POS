package com.airos.pos.app

import android.content.Context
import com.airos.pos.core.database.AirosPosDatabase
import com.airos.pos.core.datastore.TerminalPreferencesStore
import com.airos.pos.device.camera.CameraPreviewService
import com.airos.pos.device.camera.SunmiCameraPreviewService
import com.airos.pos.device.cashdrawer.CashDrawerService
import com.airos.pos.device.cashdrawer.SunmiCashDrawerService
import com.airos.pos.device.platform.AndroidDeviceInfoService
import com.airos.pos.device.platform.DeviceInfoService
import com.airos.pos.device.printer.PrinterService
import com.airos.pos.device.printer.SunmiPrinterService
import com.airos.pos.device.scanner.ScannerService
import com.airos.pos.device.scanner.SunmiScannerService
import com.airos.pos.domain.AuthRepository
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
    val cashDrawerService: CashDrawerService
    val deviceInfoService: DeviceInfoService
}

class DefaultAppContainer(
    context: Context,
) : AppContainer {
    private val appContext = context.applicationContext
    private val store = FakePosStore()

    override val database: AirosPosDatabase = AirosPosDatabase.build(appContext)
    override val terminalPreferencesStore: TerminalPreferencesStore = TerminalPreferencesStore(appContext)
    override val syncQueueRepository: SyncQueueRepository = InMemorySyncQueueRepository()
    override val syncCoordinator: SyncCoordinator = SyncCoordinator(syncQueueRepository)
    override val authRepository: AuthRepository = FakeAuthRepository(SampleData.localAuthStaffRecords())
    override val shiftRepository: ShiftRepository = FakeShiftRepository(store, syncQueueRepository)
    override val tableRepository: TableRepository = FakeTableRepository(store, syncQueueRepository)
    override val menuRepository: MenuRepository = FakeMenuRepository(store)
    override val ticketRepository: TicketRepository = FakeTicketRepository(store, authRepository, syncQueueRepository)
    override val kitchenRepository: KitchenRepository = FakeKitchenRepository(store)
    override val paymentRepository: PaymentRepository = FakePaymentRepository(store, syncQueueRepository)
    override val settingsRepository: SettingsRepository = DataStoreSettingsRepository(terminalPreferencesStore)
    override val printerService: PrinterService = SunmiPrinterService(appContext)
    override val scannerService: ScannerService = SunmiScannerService(appContext)
    override val cameraPreviewService: CameraPreviewService = SunmiCameraPreviewService(appContext)
    override val cashDrawerService: CashDrawerService = SunmiCashDrawerService(appContext)
    override val deviceInfoService: DeviceInfoService = AndroidDeviceInfoService()
}
