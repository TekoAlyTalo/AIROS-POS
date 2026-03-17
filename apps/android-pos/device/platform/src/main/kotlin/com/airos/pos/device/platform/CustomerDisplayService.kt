package com.airos.pos.device.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.DeviceConnectionState
import com.airos.pos.core.model.DeviceVendor
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.SunmiPrinterService
import com.sunmi.printerx.PrinterSdk
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class CustomerDisplayProbeReport(
    val trigger: String,
    val attemptedPath: String,
    val manufacturer: String,
    val model: String,
    val isSunmiDevice: Boolean,
    val availablePackages: List<String>,
    val serviceComponent: String? = null,
    val bindAttempted: Boolean = false,
    val bindSucceeded: Boolean = false,
    val binderClassName: String? = null,
    val binderDescriptor: String? = null,
    val resolvedClassName: String? = null,
    val managerClassPresent: Boolean,
    val managerAccessor: String?,
    val textMethod: String?,
    val sendAttempted: Boolean,
    val sendSucceeded: Boolean,
    val blocker: String? = null,
)

interface CustomerDisplayService {
    val availability: DeviceConnectionState

    fun scheduleDebugStartupProbe()

    fun probeCapability(trigger: String = "manual"): CustomerDisplayProbeReport

    fun updateCustomerTotalDisplay(totalCents: Int?)

    fun sendTestText(text: String): PosResult<Unit>
}

class SunmiCustomerDisplayService(
    context: Context,
    private val deviceInfoService: DeviceInfoService,
) : CustomerDisplayService {
    private val appContext = context.applicationContext
    private val startupProbeScheduled = AtomicBoolean(false)

    @Volatile
    private var availabilityState: DeviceConnectionState = DeviceConnectionState.UNAVAILABLE

    override val availability: DeviceConnectionState
        get() = availabilityState

    override fun scheduleDebugStartupProbe() {
        if (!startupProbeScheduled.compareAndSet(false, true)) {
            logDebug("Skipping duplicate debug startup probe request.")
            return
        }
        thread(start = true, isDaemon = true, name = "sunmi-customer-display-probe") {
            probeCapabilityInternal(trigger = "debug-startup", text = DEFAULT_TEST_TEXT)
        }
    }

    override fun probeCapability(trigger: String): CustomerDisplayProbeReport {
        return probeCapabilityInternal(trigger = trigger, text = DEFAULT_TEST_TEXT)
    }

    override fun updateCustomerTotalDisplay(totalCents: Int?) {
        val ticketPresent = totalCents != null
        val formattedValue = formatCustomerDisplayTotal(totalCents)
        logInfo(
            "customerDisplayUpdate start " +
                "ticketPresent=$ticketPresent totalCents=${totalCents ?: "-"} " +
                "formatted=$formattedValue nullFallback=$CUSTOMER_DISPLAY_EMPTY_TOTAL",
        )
        thread(start = true, isDaemon = true, name = "sunmi-customer-display-update") {
            val sdkInstance = runCatching { PrinterSdk.getInstance() }.getOrElse { error ->
                logWarn(
                    "customerDisplayUpdate exception class=${error.javaClass.name} " +
                        "message=${error.message ?: "-"} step=getInstance " +
                        "ticketPresent=$ticketPresent totalCents=${totalCents ?: "-"} formatted=$formattedValue",
                )
                return@thread
            }
            val printerRef = AtomicReference<PrinterSdk.Printer?>()
            val callbackState = AtomicReference("none")
            val latch = CountDownLatch(1)
            val listener = object : PrinterSdk.PrinterListen {
                override fun onDefPrinter(printer: PrinterSdk.Printer) {
                    callbackState.set("onDefPrinter")
                    printerRef.set(printer)
                    latch.countDown()
                }

                override fun onPrinters(printers: MutableList<PrinterSdk.Printer>) {
                    callbackState.set("onPrinters")
                    val firstPrinter = printers.firstOrNull()
                    if (firstPrinter != null) {
                        printerRef.set(firstPrinter)
                    }
                    latch.countDown()
                }
            }

            try {
                sdkInstance.getPrinter(appContext, listener)
            } catch (error: Throwable) {
                logWarn(
                    "customerDisplayUpdate exception class=${error.javaClass.name} " +
                        "message=${error.message ?: "-"} step=getPrinter " +
                        "ticketPresent=$ticketPresent totalCents=${totalCents ?: "-"} formatted=$formattedValue",
                )
                return@thread
            }

            if (!latch.await(PRINTER_X_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logWarn(
                    "customerDisplayUpdate callbackTimeout " +
                        "ticketPresent=$ticketPresent totalCents=${totalCents ?: "-"} " +
                        "formatted=$formattedValue timeoutMs=$PRINTER_X_CALLBACK_TIMEOUT_MS",
                )
                return@thread
            }

            val printer = printerRef.get()
            if (printer == null) {
                logWarn(
                    "customerDisplayUpdate printerMissing " +
                        "ticketPresent=$ticketPresent totalCents=${totalCents ?: "-"} " +
                        "formatted=$formattedValue callback=${callbackState.get()}",
                )
                return@thread
            }

            val lcdApi = runCatching { printer.lcdApi() }.getOrElse { error ->
                logWarn(
                    "customerDisplayUpdate exception class=${error.javaClass.name} " +
                        "message=${error.message ?: "-"} step=lcdApi " +
                        "ticketPresent=$ticketPresent totalCents=${totalCents ?: "-"} formatted=$formattedValue",
                )
                return@thread
            }
            logInfo(
                "customerDisplayUpdate lcdApiObtained=${lcdApi != null} " +
                    "ticketPresent=$ticketPresent totalCents=${totalCents ?: "-"} formatted=$formattedValue",
            )
            if (lcdApi == null) {
                return@thread
            }

            runCatching { lcdApi.showDigital(formattedValue) }
                .onSuccess {
                    logInfo(
                        "customerDisplayUpdate success " +
                            "ticketPresent=$ticketPresent totalCents=${totalCents ?: "-"} formatted=$formattedValue",
                    )
                }
                .onFailure { error ->
                    logWarn(
                        "customerDisplayUpdate exception class=${error.javaClass.name} " +
                            "message=${error.message ?: "-"} step=showDigital " +
                            "ticketPresent=$ticketPresent totalCents=${totalCents ?: "-"} formatted=$formattedValue",
                    )
                }
        }
    }

    override fun sendTestText(text: String): PosResult<Unit> {
        val report = probeCapabilityInternal(trigger = "manual-send", text = text)
        return if (report.sendSucceeded) {
            PosResult.Success(Unit)
        } else {
            PosResult.Failure(report.blocker ?: "Customer-display text send failed.")
        }
    }

    private fun probeCapabilityInternal(
        trigger: String,
        text: String,
    ): CustomerDisplayProbeReport {
        val profile = deviceInfoService.currentProfile()
        val manufacturer = profile.manufacturer.ifBlank { Build.MANUFACTURER.orEmpty() }
        val model = profile.model.ifBlank { Build.MODEL.orEmpty() }
        val isSunmiDevice = profile.vendor == DeviceVendor.SUNMI ||
            manufacturer.contains("sunmi", ignoreCase = true)
        val seedVisiblePackages = SUNMI_SUPPORT_PACKAGES.filter(::isPackageInstalled)
        val runtimeDiscoveredPackages = discoverRelevantRuntimePackages()
        val availablePackages = (seedVisiblePackages + runtimeDiscoveredPackages).distinct().sorted()

        availabilityState = DeviceConnectionState.BUSY
        logInfo(
            "Probe started. trigger=$trigger manufacturer=$manufacturer model=$model " +
                "sunmiDevice=$isSunmiDevice packages=${availablePackages.ifEmpty { listOf("none") }}",
        )
        logInfo(
            "Runtime SUNMI package discovery. visibleSeeds=${seedVisiblePackages.ifEmpty { listOf("none") }} " +
                "runtimeMatches=${runtimeDiscoveredPackages.ifEmpty { listOf("none") }}",
        )

        if (!isSunmiDevice) {
            availabilityState = DeviceConnectionState.UNAVAILABLE
            logWarn("printerLibraryProbe skipped reason=not_sunmi_device trigger=$trigger")
            return failure(
                trigger = trigger,
                attemptedPath = PATH_VENDOR_CHECK,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = false,
                availablePackages = availablePackages,
                blocker = "Device vendor is not SUNMI.",
            )
        }

        val printerAttempt = probePrinterDisplay(trigger, manufacturer, model, availablePackages)
        if (printerAttempt.sendSucceeded) {
            logInfo(
                "printerLibraryProbe skipped reason=printer_probe_ready " +
                    "gateSendSucceeded=${printerAttempt.sendSucceeded} " +
                    "attemptedPath=${printerAttempt.attemptedPath} " +
                    "blocker=${printerAttempt.blocker ?: "-"}",
            )
            availabilityState = DeviceConnectionState.READY
            return printerAttempt
        }

        logInfo(
            "printerLibraryProbe dispatch peripheral-printer start " +
                "trigger=$trigger gateSendSucceeded=${printerAttempt.sendSucceeded} " +
                "previousBlocker=${printerAttempt.blocker ?: "-"}",
        )
        val printerLibraryAttempt = probePrinterLibraryFacade(
            trigger = trigger,
            manufacturer = manufacturer,
            model = model,
            availablePackages = availablePackages,
        )
        logInfo(
            "printerLibraryProbe dispatch peripheral-printer end " +
                "sendSucceeded=${printerLibraryAttempt.sendSucceeded} " +
                "blocker=${printerLibraryAttempt.blocker ?: "-"} " +
                "resolvedClass=${printerLibraryAttempt.resolvedClassName ?: "-"}",
        )
        if (printerLibraryAttempt.sendSucceeded) {
            availabilityState = DeviceConnectionState.READY
            return printerLibraryAttempt
        }

        logInfo(
            "printerXProbe dispatch start " +
                "trigger=$trigger gateSendSucceeded=${printerLibraryAttempt.sendSucceeded} " +
                "previousBlocker=${printerLibraryAttempt.blocker ?: printerAttempt.blocker ?: "-"}",
        )
        val printerXAttempt = probePrinterXFacade(
            trigger = trigger,
            manufacturer = manufacturer,
            model = model,
            availablePackages = availablePackages,
        )
        logInfo(
            "printerXProbe dispatch end " +
                "sendSucceeded=${printerXAttempt.sendSucceeded} " +
                "blocker=${printerXAttempt.blocker ?: "-"} " +
                "resolvedClass=${printerXAttempt.resolvedClassName ?: "-"}",
        )
        if (printerXAttempt.sendSucceeded) {
            availabilityState = DeviceConnectionState.READY
            return printerXAttempt
        }

        availabilityState = DeviceConnectionState.UNAVAILABLE
        logWarn(
            "Customer-display printer probe failed. " +
                "${printerXAttempt.blocker ?: printerLibraryAttempt.blocker ?: printerAttempt.blocker ?: "Unknown blocker"}",
        )
        return printerXAttempt
    }

    private fun probePrinterDisplay(
        trigger: String,
        manufacturer: String,
        model: String,
        availablePackages: List<String>,
    ): CustomerDisplayProbeReport {
        logInfo("Starting printer customer-display discovery probe. trigger=$trigger")
        val failures = mutableListOf<String>()
        var bindAttempts = 0
        val packageCandidates = (PRINTER_CANDIDATE_PACKAGES + availablePackages)
            .distinct()
            .filter(::isRelevantPackageName)
            .sorted()
        if (packageCandidates.isEmpty()) {
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                blocker = "No SUNMI printer/display packages were visible to the app. Package visibility may be restricted.",
            )
        }

        packageCandidates.forEach { packageName ->
            val installed = isPackageInstalled(packageName)
            val services = if (installed) discoverPrinterServiceComponents(packageName) else emptyList()
            logInfo(
                "printerServiceCandidate package=$packageName installed=$installed " +
                    "services=${services.map { it.componentName }.ifEmpty { listOf("-") }}",
            )
            if (!installed) {
                failures += "$packageName: not visible/installed"
                return@forEach
            }
            if (services.isEmpty()) {
                failures += "$packageName: no services discoverable"
                return@forEach
            }

            services.forEach { serviceCandidate ->
                logServiceCandidateMetadata(packageName, serviceCandidate)
                val serviceClassName = serviceCandidate.componentName
                val unsafeReason = unsafeDiscoveryReason(packageName, serviceClassName)
                if (unsafeReason != null) {
                    logWarn(
                        "printerServiceCandidate package=$packageName service=$serviceClassName reason=unsafe_service detail=$unsafeReason",
                    )
                    failures += "$packageName/$serviceClassName: unsafe_service"
                    return@forEach
                }
                if (!serviceCandidate.enabled) {
                    logInfo(
                        "printerServiceCandidate package=$packageName service=$serviceClassName " +
                            "reason=passive_only detail=service_disabled",
                    )
                    failures += "$packageName/$serviceClassName: service_disabled"
                    return@forEach
                }
                if (!serviceCandidate.exported) {
                    logInfo(
                        "printerServiceCandidate package=$packageName service=$serviceClassName " +
                            "reason=passive_only detail=service_not_exported",
                    )
                    failures += "$packageName/$serviceClassName: service_not_exported"
                    return@forEach
                }
                if (!shouldAttemptDiscoveryBind(packageName, serviceClassName)) {
                    logInfo(
                        "printerServiceCandidate package=$packageName service=$serviceClassName " +
                            "reason=passive_only detail=non_priority_candidate",
                    )
                    failures += "$packageName/$serviceClassName: non_priority_candidate"
                    return@forEach
                }
                bindAttempts += 1
                val report = inspectPrinterServiceCandidate(
                    trigger = trigger,
                    manufacturer = manufacturer,
                    model = model,
                    availablePackages = availablePackages,
                    packageName = packageName,
                    serviceClassName = serviceClassName,
                )
                if (report.sendSucceeded) {
                    return report
                }
                failures += "$packageName/$serviceClassName: ${report.blocker ?: "Unknown blocker"}"
            }
        }

        logWarn(
            "Printer discovery finished without a safe bind target. bindAttempts=$bindAttempts " +
                "details=${failures.joinToString(" | ").ifBlank { "-" }}",
        )

        return failure(
            trigger = trigger,
            attemptedPath = PRINTER_PATH,
            manufacturer = manufacturer,
            model = model,
            isSunmiDevice = true,
            availablePackages = availablePackages,
            blocker = "No confirmed SUNMI display service bound. See SunmiCustomerDisplay logs.",
        )
    }

    private fun discoverPrinterServiceComponents(
        packageName: String,
    ): List<DiscoveredServiceCandidate> {
        val packageInfo = getPackageInfoCompat(packageName).getOrElse { error ->
            logWarn(
                "printerServiceCandidate package=$packageName serviceDiscoveryFailed${throwableSummary(error)}",
            )
            return emptyList()
        }
        val packageContext = createVendorPackageContext(packageName).getOrNull()
        return packageInfo.services
            .orEmpty()
            .sortedWith(
                compareByDescending<ServiceInfo> { isRelevantServiceName(serviceComponentName(it)) }
                    .thenBy { serviceComponentName(it) },
            )
            .map { serviceInfo ->
                toDiscoveredServiceCandidate(
                    serviceInfo = serviceInfo,
                    packageContext = packageContext,
                )
            }
            .distinct()
    }

    private fun serviceComponentName(
        serviceInfo: ServiceInfo,
    ): String {
        return if (serviceInfo.name.startsWith(".")) {
            serviceInfo.packageName + serviceInfo.name
        } else {
            serviceInfo.name
        }
    }

    private fun toDiscoveredServiceCandidate(
        serviceInfo: ServiceInfo,
        packageContext: Context?,
    ): DiscoveredServiceCandidate {
        val componentName = serviceComponentName(serviceInfo)
        val metaDataEntries = serviceInfo.metaData
            ?.keySet()
            ?.sorted()
            ?.map { key ->
                val value = serviceInfo.metaData?.get(key)
                "$key=${value ?: "-"}"
            }
            .orEmpty()
        val actionHints = collectServiceActionHints(
            packageContext = packageContext,
            serviceClassName = componentName,
            packageName = serviceInfo.packageName,
        )
        return DiscoveredServiceCandidate(
            componentName = componentName,
            exported = serviceInfo.exported,
            enabled = serviceInfo.enabled,
            permission = serviceInfo.permission,
            processName = serviceInfo.processName,
            metaDataEntries = metaDataEntries,
            actionHints = actionHints,
        )
    }

    private fun logServiceCandidateMetadata(
        packageName: String,
        serviceCandidate: DiscoveredServiceCandidate,
    ) {
        logInfo(
            "printerServiceMetadata package=$packageName service=${serviceCandidate.componentName} " +
                "exported=${serviceCandidate.exported} enabled=${serviceCandidate.enabled} " +
                "permission=${serviceCandidate.permission ?: "-"} process=${serviceCandidate.processName ?: "-"}",
        )
        if (serviceCandidate.metaDataEntries.isNotEmpty()) {
            logInfo(
                "printerServiceMetadata package=$packageName service=${serviceCandidate.componentName} " +
                    "metaData=${serviceCandidate.metaDataEntries.joinToString(" | ")}",
            )
        }
        if (serviceCandidate.actionHints.isNotEmpty()) {
            logInfo(
                "printerServiceMetadata package=$packageName service=${serviceCandidate.componentName} " +
                    "actionHints=${serviceCandidate.actionHints.joinToString(" | ")}",
            )
        } else {
            logInfo(
                "printerServiceMetadata package=$packageName service=${serviceCandidate.componentName} actionHints=-",
            )
        }
    }

    private fun inspectPrinterServiceCandidate(
        trigger: String,
        manufacturer: String,
        model: String,
        availablePackages: List<String>,
        packageName: String,
        serviceClassName: String,
    ): CustomerDisplayProbeReport {
        val isLcdAdapterService = isLcdAdapterService(packageName, serviceClassName)
        logInfo("printerServiceBindAttempt package=$packageName service=$serviceClassName")
        val session = bindVendorServiceSession(
            pathName = "$PRINTER_PATH/$packageName",
            intent = Intent().setComponent(ComponentName(packageName, serviceClassName)),
        )
        logInfo(
            "printerServiceBindResult package=$packageName service=$serviceClassName " +
                "bindSucceeded=${session.bindSucceeded} blocker=${session.blocker ?: "-"}",
        )
        if (isLcdAdapterService) {
            logInfo(
                "lcdAdapter.bindLifecycle package=$packageName service=$serviceClassName " +
                    "connected=${session.bindSucceeded} nullBinding=${session.nullBindingReceived} " +
                    "bindingDied=${session.bindingDiedReceived} disconnected=${session.serviceDisconnectedReceived} " +
                    "timedOut=${session.timedOut} timeoutMs=${session.timeoutMs}",
            )
            logInfo(
                "lcdAdapter.surfaceSummary descriptor=${session.binderDescriptor ?: "-"} " +
                    "bindSucceeded=${session.bindSucceeded} timedOut=${session.timedOut} " +
                    "methodCount=0 lcdLikeMethodCount=0",
            )
        }
        if (!session.bindSucceeded || session.binder == null) {
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = session.serviceComponent ?: "$packageName/$serviceClassName",
                bindAttempted = session.bindAttempted,
                bindSucceeded = false,
                binderClassName = session.binderClassName,
                binderDescriptor = session.binderDescriptor,
                blocker = session.blocker ?: "Printer service bind failed.",
            )
        }

        logInfo("printerBinderClass package=$packageName service=$serviceClassName class=${session.binderClassName ?: "-"}")
        logInfo("printerBinderDescriptor package=$packageName service=$serviceClassName descriptor=${session.binderDescriptor ?: "-"}")
        logInfo(
            "printerBinderHealth package=$packageName service=$serviceClassName " +
                "alive=${session.binderAlive} ping=${session.binderPing}",
        )

        return try {
            val packageContext = createVendorPackageContext(packageName).getOrElse { error ->
                return failure(
                    trigger = trigger,
                    attemptedPath = PRINTER_PATH,
                    manufacturer = manufacturer,
                    model = model,
                    isSunmiDevice = true,
                    availablePackages = availablePackages,
                    serviceComponent = session.serviceComponent ?: "$packageName/$serviceClassName",
                    bindAttempted = session.bindAttempted,
                    bindSucceeded = true,
                    binderClassName = session.binderClassName,
                    binderDescriptor = session.binderDescriptor,
                    blocker = "Printer package context could not be created for $packageName.",
                    error = error,
                )
            }

            val serviceClass = runCatching {
                packageContext.classLoader.loadClass(serviceClassName)
            }.getOrNull()
            val descriptorClass = session.binderDescriptor?.let { descriptorName ->
                runCatching { packageContext.classLoader.loadClass(descriptorName) }.getOrNull()
            }
            val stubClass = resolveStubClass(packageContext, serviceClassName, descriptorClass)
            val proxy = resolveServiceProxy(stubClass, session.binder)
            val interfaceClass = when {
                descriptorClass != null -> descriptorClass
                proxy != null -> proxy.javaClass.interfaces.firstOrNull() ?: proxy.javaClass
                serviceClass != null -> serviceClass.interfaces.firstOrNull() ?: serviceClass
                else -> null
            }

            val methodLogs = mutableListOf<String>()
            descriptorClass?.let {
                logPrinterInterfaceMethods(ownerLabel = "descriptor", ownerClass = it, sink = methodLogs)
            }
            serviceClass?.let {
                logPrinterInterfaceMethods(ownerLabel = "service", ownerClass = it, sink = methodLogs)
            }
            stubClass?.let {
                logPrinterStubFields(it)
                logPrinterInterfaceMethods(ownerLabel = "stub", ownerClass = it, sink = methodLogs)
            }
            proxy?.javaClass?.let {
                logPrinterInterfaceMethods(ownerLabel = "proxy", ownerClass = it, sink = methodLogs)
            }
            interfaceClass?.let {
                logInfo("printerBinderInterfaceName package=$packageName service=$serviceClassName interface=${it.name}")
            }
            if (isLcdAdapterService) {
                inspectLcdAdapterServiceSurface(
                    packageName = packageName,
                    serviceClassName = serviceClassName,
                    session = session,
                    serviceClass = serviceClass,
                    descriptorClass = descriptorClass,
                    stubClass = stubClass,
                    proxy = proxy,
                    interfaceClass = interfaceClass,
                    methodLogs = methodLogs,
                )
            }

            if (methodLogs.isEmpty()) {
                return failure(
                    trigger = trigger,
                    attemptedPath = PRINTER_PATH,
                    manufacturer = manufacturer,
                    model = model,
                    isSunmiDevice = true,
                    availablePackages = availablePackages,
                    serviceComponent = session.serviceComponent ?: "$packageName/$serviceClassName",
                    bindAttempted = session.bindAttempted,
                    bindSucceeded = true,
                    binderClassName = session.binderClassName,
                    binderDescriptor = session.binderDescriptor,
                    resolvedClassName = interfaceClass?.name ?: serviceClass?.name ?: stubClass?.name,
                    managerClassPresent = stubClass != null,
                    managerAccessor = stubClass?.let { "${it.simpleName}.asInterface()" },
                    blocker = "Service bound, but no public interface methods could be resolved.",
                )
            }

            val highlightedMethods = methodLogs.filter(::isPrinterDisplayMethodSignature)
            if (isUnifiedBrokerCandidate(packageName, serviceClassName, session.binderDescriptor, interfaceClass?.name)) {
                return inspectUnifiedBrokerPrinterBinder(
                    trigger = trigger,
                    manufacturer = manufacturer,
                    model = model,
                    availablePackages = availablePackages,
                    packageName = packageName,
                    serviceClassName = serviceClassName,
                    session = session,
                    packageContext = packageContext,
                    serviceClass = serviceClass,
                    descriptorClass = descriptorClass,
                    stubClass = stubClass,
                    proxy = proxy,
                    interfaceClass = interfaceClass,
                    methodLogs = methodLogs,
                    highlightedMethods = highlightedMethods,
                )
            }
            val displaySignals = collectDisplaySignals(
                packageName = packageName,
                serviceClassName = serviceClassName,
                binderDescriptor = session.binderDescriptor,
                resolvedClassName = interfaceClass?.name ?: serviceClass?.name ?: stubClass?.name ?: proxy?.javaClass?.name,
                methodLogs = methodLogs,
            )
            val nonDisplayReason = nonDisplayServiceReason(
                packageName = packageName,
                serviceClassName = serviceClassName,
                binderDescriptor = session.binderDescriptor,
                resolvedClassName = interfaceClass?.name ?: serviceClass?.name ?: stubClass?.name ?: proxy?.javaClass?.name,
            )
            if (nonDisplayReason != null || displaySignals.isEmpty()) {
                val reason = nonDisplayReason ?: "missing_display_signals"
                logWarn(
                    "printerServiceCandidate package=$packageName service=$serviceClassName " +
                        "reason=bound_non_display_service detail=$reason",
                )
                return failure(
                    trigger = trigger,
                    attemptedPath = PRINTER_PATH,
                    manufacturer = manufacturer,
                    model = model,
                    isSunmiDevice = true,
                    availablePackages = availablePackages,
                    serviceComponent = session.serviceComponent ?: "$packageName/$serviceClassName",
                    bindAttempted = session.bindAttempted,
                    bindSucceeded = true,
                    binderClassName = session.binderClassName,
                    binderDescriptor = session.binderDescriptor,
                    resolvedClassName = interfaceClass?.name ?: serviceClass?.name ?: stubClass?.name ?: proxy?.javaClass?.name,
                    managerClassPresent = stubClass != null,
                    managerAccessor = stubClass?.let { "${it.simpleName}.asInterface()" },
                    textMethod = highlightedMethods.ifEmpty { methodLogs }.joinToString(" | "),
                    blocker = "No confirmed SUNMI display service bound. See SunmiCustomerDisplay logs.",
                )
            }
            return CustomerDisplayProbeReport(
                trigger = trigger,
                attemptedPath = PRINTER_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = session.serviceComponent ?: "$packageName/$serviceClassName",
                bindAttempted = session.bindAttempted,
                bindSucceeded = true,
                binderClassName = session.binderClassName,
                binderDescriptor = session.binderDescriptor,
                resolvedClassName = interfaceClass?.name ?: serviceClass?.name ?: stubClass?.name ?: proxy?.javaClass?.name,
                managerClassPresent = stubClass != null,
                managerAccessor = stubClass?.let { "${it.simpleName}.asInterface()" },
                textMethod = displaySignals.joinToString(" | "),
                sendAttempted = false,
                sendSucceeded = true,
            )
        } finally {
            releaseVendorServiceSession("$PRINTER_PATH/$packageName", session)
        }
    }

    private fun inspectUnifiedBrokerPrinterBinder(
        trigger: String,
        manufacturer: String,
        model: String,
        availablePackages: List<String>,
        packageName: String,
        serviceClassName: String,
        session: VendorServiceSession,
        packageContext: Context,
        serviceClass: Class<*>?,
        descriptorClass: Class<*>?,
        stubClass: Class<*>?,
        proxy: Any?,
        interfaceClass: Class<*>?,
        methodLogs: List<String>,
        highlightedMethods: List<String>,
    ): CustomerDisplayProbeReport {
        val brokerMethodLogs = mutableListOf<String>()
        val brokerAccessorStates = inspectUnifiedBrokerSurface(
            packageContext = packageContext,
            descriptorClass = descriptorClass,
            stubClass = stubClass,
            proxy = proxy,
            interfaceClass = interfaceClass,
            sink = brokerMethodLogs,
        )
        val printerBinderAccessorState = brokerAccessorStates.firstOrNull { accessor ->
            accessor.methodName == UNIFIED_BROKER_PRINTER_BINDER_METHOD
        } ?: return failure(
            trigger = trigger,
            attemptedPath = PRINTER_PATH,
            manufacturer = manufacturer,
            model = model,
            isSunmiDevice = true,
            availablePackages = availablePackages,
            serviceComponent = session.serviceComponent ?: "$packageName/$serviceClassName",
            bindAttempted = session.bindAttempted,
            bindSucceeded = true,
            binderClassName = session.binderClassName,
            binderDescriptor = session.binderDescriptor,
            resolvedClassName = interfaceClass?.name ?: serviceClass?.name ?: stubClass?.name,
            managerClassPresent = stubClass != null,
            managerAccessor = stubClass?.let { "${it.simpleName}.asInterface()" },
            textMethod = brokerMethodLogs.ifEmpty { highlightedMethods.ifEmpty { methodLogs } }.joinToString(" | "),
            blocker = "Unified broker bound, but getPrinterBinder() was not exposed.",
        )
        val rawPrinterBinder = printerBinderAccessorState.returnedValue ?: run {
            if (printerBinderAccessorState.errorSummary != null) {
                logWarn(
                    "unifiedBrokerPrinterBinder invoke=getPrinterBinder state=exception " +
                        "summary=${printerBinderAccessorState.errorSummary}",
                )
            } else {
                logInfo("unifiedBrokerPrinterBinder invoke=getPrinterBinder state=returned_null")
            }
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = session.serviceComponent ?: "$packageName/$serviceClassName",
                bindAttempted = session.bindAttempted,
                bindSucceeded = true,
                binderClassName = session.binderClassName,
                binderDescriptor = session.binderDescriptor,
                resolvedClassName = interfaceClass?.name ?: serviceClass?.name ?: stubClass?.name,
                managerClassPresent = stubClass != null,
                managerAccessor = stubClass?.let { "${it.simpleName}.asInterface()" },
                textMethod = brokerMethodLogs.ifEmpty { highlightedMethods.ifEmpty { methodLogs } }.joinToString(" | "),
                blocker = if (printerBinderAccessorState.errorSummary != null) {
                    "Unified broker getPrinterBinder() threw. See SunmiCustomerDisplay logs."
                } else {
                    "Unified broker getPrinterBinder() returned null."
                },
            )
        }

        val printerBinderState = inspectUnifiedPrinterBinder(
            packageContext = packageContext,
            packageName = packageName,
            rawPrinterBinder = rawPrinterBinder,
        )
        logInfo(
            "unifiedBrokerPrinterBinderResult state=returned binderClass=${printerBinderState.rawClassName} " +
                "descriptor=${printerBinderState.binderDescriptor ?: "-"} alive=${printerBinderState.binderAlive} " +
                "ping=${printerBinderState.binderPing}",
        )

        if (printerBinderState.displaySignals.isEmpty()) {
            val detail = if (printerBinderState.infoSignals.isNotEmpty()) {
                "printer_info_only_interface"
            } else {
                "unified_broker_without_display_signals"
            }
            logWarn(
                "printerServiceCandidate package=$packageName service=$serviceClassName " +
                    "reason=bound_non_display_service detail=$detail",
            )
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = session.serviceComponent ?: "$packageName/$serviceClassName",
                bindAttempted = session.bindAttempted,
                bindSucceeded = true,
                binderClassName = session.binderClassName,
                binderDescriptor = session.binderDescriptor,
                resolvedClassName = printerBinderState.resolvedClassName
                    ?: interfaceClass?.name
                    ?: serviceClass?.name
                    ?: stubClass?.name,
                managerClassPresent = stubClass != null,
                managerAccessor = stubClass?.let { "${it.simpleName}.asInterface()" },
                textMethod = printerBinderState.methodLogs.ifEmpty { highlightedMethods.ifEmpty { methodLogs } }.joinToString(" | "),
                blocker = "Printer binder found, but no confirmed customer-display methods. See SunmiCustomerDisplay logs.",
            )
        }

        return CustomerDisplayProbeReport(
            trigger = trigger,
            attemptedPath = PRINTER_PATH,
            manufacturer = manufacturer,
            model = model,
            isSunmiDevice = true,
            availablePackages = availablePackages,
            serviceComponent = session.serviceComponent ?: "$packageName/$serviceClassName",
            bindAttempted = session.bindAttempted,
            bindSucceeded = true,
            binderClassName = printerBinderState.rawClassName,
            binderDescriptor = printerBinderState.binderDescriptor,
            resolvedClassName = printerBinderState.resolvedClassName,
            managerClassPresent = printerBinderState.stubClassPresent,
            managerAccessor = UNIFIED_BROKER_PRINTER_BINDER_METHOD,
            textMethod = printerBinderState.displaySignals.joinToString(" | "),
            sendAttempted = false,
            sendSucceeded = true,
        )
    }

    private fun discoverRelevantRuntimePackages(): List<String> {
        val installedPackages = getInstalledPackagesCompat().getOrElse { error ->
            logWarn("Runtime package enumeration failed${throwableSummary(error)}")
            return emptyList()
        }
        val matches = installedPackages.mapNotNull { info ->
            val packageName = info.packageName ?: return@mapNotNull null
            val serviceNames = info.services.orEmpty().map(::serviceComponentName)
            val relevant = isRelevantPackageName(packageName) || serviceNames.any(::isRelevantServiceName)
            if (!relevant) {
                return@mapNotNull null
            }
            logInfo(
                "printerServiceCandidate runtimeVisible package=$packageName " +
                    "services=${serviceNames.ifEmpty { listOf("-") }}",
            )
            packageName
        }
        if (matches.isEmpty()) {
            logWarn(
                "Runtime package enumeration returned no relevant SUNMI printer/display packages. " +
                    "Android package visibility may be restricted.",
            )
        }
        return matches.distinct().sorted()
    }

    private fun probeLcdAdapter(
        trigger: String,
        manufacturer: String,
        model: String,
        availablePackages: List<String>,
        text: String,
    ): CustomerDisplayProbeReport {
        logInfo("Starting LCD adapter probe. trigger=$trigger component=$LCD_ADAPTER_SERVICE")
        if (!availablePackages.contains(LCD_ADAPTER_PACKAGE)) {
            return failure(
                trigger = trigger,
                attemptedPath = LCD_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = LCD_ADAPTER_SERVICE,
                blocker = "Package $LCD_ADAPTER_PACKAGE is not installed.",
            )
        }

        val binding = bindVendorService(
            pathName = LCD_PATH,
            intent = Intent(LCD_ADAPTER_ACTION).setComponent(
                ComponentName(LCD_ADAPTER_PACKAGE, LCD_ADAPTER_SERVICE),
            ),
        )
        if (!binding.bindSucceeded) {
            return failure(
                trigger = trigger,
                attemptedPath = LCD_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = binding.serviceComponent ?: LCD_ADAPTER_SERVICE,
                bindAttempted = binding.bindAttempted,
                bindSucceeded = false,
                binderClassName = binding.binderClassName,
                binderDescriptor = binding.binderDescriptor,
                blocker = binding.blocker ?: "AdapterService bind failed.",
            )
        }

        val packageContext = createVendorPackageContext(LCD_ADAPTER_PACKAGE).getOrElse { error ->
            return failure(
                trigger = trigger,
                attemptedPath = LCD_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = binding.serviceComponent ?: LCD_ADAPTER_SERVICE,
                bindAttempted = binding.bindAttempted,
                bindSucceeded = true,
                binderClassName = binding.binderClassName,
                binderDescriptor = binding.binderDescriptor,
                blocker = "LCD adapter package context could not be created.",
                error = error,
            )
        }

        val adapterClass = runCatching { packageContext.classLoader.loadClass(LCD_ADAPTER_CLASS) }.getOrElse { error ->
            return failure(
                trigger = trigger,
                attemptedPath = LCD_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = binding.serviceComponent ?: LCD_ADAPTER_SERVICE,
                bindAttempted = binding.bindAttempted,
                bindSucceeded = true,
                binderClassName = binding.binderClassName,
                binderDescriptor = binding.binderDescriptor,
                resolvedClassName = LCD_ADAPTER_CLASS,
                blocker = "LCD adapter class $LCD_ADAPTER_CLASS was not reachable.",
                error = error,
            )
        }
        val constructor = runCatching { adapterClass.getConstructor(Context::class.java) }.getOrElse { error ->
            return failure(
                trigger = trigger,
                attemptedPath = LCD_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = binding.serviceComponent ?: LCD_ADAPTER_SERVICE,
                bindAttempted = binding.bindAttempted,
                bindSucceeded = true,
                binderClassName = binding.binderClassName,
                binderDescriptor = binding.binderDescriptor,
                resolvedClassName = adapterClass.name,
                blocker = "LCD adapter constructor LcdAdapter(Context) was not found.",
                error = error,
            )
        }
        val executeMethod = adapterClass.methods.firstOrNull { method ->
            method.name == "executeAdapter" &&
                method.parameterTypes.contentEquals(
                    arrayOf(String::class.java, String::class.java, String::class.java),
                ) &&
                (method.returnType == Boolean::class.javaPrimitiveType ||
                    method.returnType == java.lang.Boolean::class.java)
        } ?: return failure(
            trigger = trigger,
            attemptedPath = LCD_PATH,
            manufacturer = manufacturer,
            model = model,
            isSunmiDevice = true,
            availablePackages = availablePackages,
            serviceComponent = binding.serviceComponent ?: LCD_ADAPTER_SERVICE,
            bindAttempted = binding.bindAttempted,
            bindSucceeded = true,
            binderClassName = binding.binderClassName,
            binderDescriptor = binding.binderDescriptor,
            resolvedClassName = adapterClass.name,
            blocker = "LCD adapter executeAdapter(String,String,String) was not found.",
        )

        val adapter = runCatching { constructor.newInstance(packageContext) }.getOrElse { error ->
            return failure(
                trigger = trigger,
                attemptedPath = LCD_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = binding.serviceComponent ?: LCD_ADAPTER_SERVICE,
                bindAttempted = binding.bindAttempted,
                bindSucceeded = true,
                binderClassName = binding.binderClassName,
                binderDescriptor = binding.binderDescriptor,
                resolvedClassName = adapterClass.name,
                blocker = "LCD adapter instance could not be created.",
                error = error,
            )
        }

        logAdapterPublicMethods(adapterClass)
        logAdapterFields(adapterClass, adapter)

        invokeNoArgIfPresent(adapterClass, adapter, "init")
        invokeNoArgIfPresent(adapterClass, adapter, "connect")

        return try {
            logInfo(
                "LCD adapter executeAdapter candidate resolved. class=${adapterClass.name} " +
                    "method=${formatMethodSignature(executeMethod)}",
            )
            val commandAttempts = buildList {
                add(LcdCommandAttempt("set", "show_price", "123456"))
                add(LcdCommandAttempt("set", "show_number", "123456"))
                add(LcdCommandAttempt("set", "price", "123456"))
                add(LcdCommandAttempt("set", "digit", "123456"))
                add(LcdCommandAttempt("set", "amount", "123456"))
                add(LcdCommandAttempt("set", "display", "123456"))
                add(LcdCommandAttempt("set", "text", "123456"))
                add(LcdCommandAttempt("set", "show_price", "12.50"))
                add(LcdCommandAttempt("set", "show_price", "12,50"))
            }
            val results = commandAttempts.map { attempt ->
                executeLcdCommand(
                    executeMethod = executeMethod,
                    adapter = adapter,
                    category = attempt.category,
                    command = attempt.command,
                    payload = attempt.payload,
                )
            }
            val successfulResults = results.filter { it.succeeded }
            val sendSucceeded = successfulResults.isNotEmpty()
            if (sendSucceeded) {
                logInfo("LCD adapter text send succeeded.")
                CustomerDisplayProbeReport(
                    trigger = trigger,
                    attemptedPath = LCD_PATH,
                    manufacturer = manufacturer,
                    model = model,
                    isSunmiDevice = true,
                    availablePackages = availablePackages,
                    serviceComponent = binding.serviceComponent ?: LCD_ADAPTER_SERVICE,
                    bindAttempted = binding.bindAttempted,
                    bindSucceeded = true,
                    binderClassName = binding.binderClassName,
                    binderDescriptor = binding.binderDescriptor,
                    resolvedClassName = adapterClass.name,
                    managerClassPresent = false,
                    managerAccessor = "LcdAdapter(Context)",
                    textMethod = successfulResults.joinToString(", ") { "${it.category}/${it.command}/${it.payload}" },
                    sendAttempted = true,
                    sendSucceeded = true,
                )
            } else {
                failure(
                    trigger = trigger,
                    attemptedPath = LCD_PATH,
                    manufacturer = manufacturer,
                    model = model,
                    isSunmiDevice = true,
                    availablePackages = availablePackages,
                    serviceComponent = binding.serviceComponent ?: LCD_ADAPTER_SERVICE,
                    bindAttempted = binding.bindAttempted,
                    bindSucceeded = true,
                    binderClassName = binding.binderClassName,
                    binderDescriptor = binding.binderDescriptor,
                    resolvedClassName = adapterClass.name,
                    managerClassPresent = false,
                    managerAccessor = "LcdAdapter(Context)",
                    textMethod = formatMethodSignature(executeMethod),
                    sendAttempted = true,
                    blocker = "LCD adapter executeAdapter returned false for all numeric test commands.",
                )
            }
        } catch (error: Throwable) {
            failure(
                trigger = trigger,
                attemptedPath = LCD_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = binding.serviceComponent ?: LCD_ADAPTER_SERVICE,
                bindAttempted = binding.bindAttempted,
                bindSucceeded = true,
                binderClassName = binding.binderClassName,
                binderDescriptor = binding.binderDescriptor,
                resolvedClassName = adapterClass.name,
                managerClassPresent = false,
                managerAccessor = "LcdAdapter(Context)",
                textMethod = formatMethodSignature(executeMethod),
                sendAttempted = true,
                blocker = "LCD adapter numeric command probe threw an exception.",
                error = error,
            )
        } finally {
            invokeNoArgIfPresent(adapterClass, adapter, "disconnect")
            invokeNoArgIfPresent(adapterClass, adapter, "unInit")
        }
    }

    private fun probeUsbScreen(
        trigger: String,
        manufacturer: String,
        model: String,
        availablePackages: List<String>,
    ): CustomerDisplayProbeReport {
        logInfo("Starting USB screen probe. trigger=$trigger component=$USB_SCREEN_SERVICE")
        if (!availablePackages.contains(USB_SCREEN_PACKAGE)) {
            return failure(
                trigger = trigger,
                attemptedPath = USB_SCREEN_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = USB_SCREEN_SERVICE,
                blocker = "Package $USB_SCREEN_PACKAGE is not installed.",
            )
        }

        val binding = bindVendorService(
            pathName = USB_SCREEN_PATH,
            intent = Intent().setComponent(
                ComponentName(USB_SCREEN_PACKAGE, USB_SCREEN_SERVICE),
            ),
        )
        if (!binding.bindSucceeded) {
            return failure(
                trigger = trigger,
                attemptedPath = USB_SCREEN_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = binding.serviceComponent ?: USB_SCREEN_SERVICE,
                bindAttempted = binding.bindAttempted,
                bindSucceeded = false,
                binderClassName = binding.binderClassName,
                binderDescriptor = binding.binderDescriptor,
                blocker = binding.blocker ?: "SubScreenService bind failed.",
            )
        }

        val packageContext = createVendorPackageContext(USB_SCREEN_PACKAGE).getOrElse { error ->
            return failure(
                trigger = trigger,
                attemptedPath = USB_SCREEN_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = binding.serviceComponent ?: USB_SCREEN_SERVICE,
                bindAttempted = binding.bindAttempted,
                bindSucceeded = true,
                binderClassName = binding.binderClassName,
                binderDescriptor = binding.binderDescriptor,
                blocker = "USB screen package context could not be created.",
                error = error,
            )
        }

        val serviceClass = runCatching { packageContext.classLoader.loadClass(USB_SCREEN_CLASS) }.getOrElse { error ->
            return failure(
                trigger = trigger,
                attemptedPath = USB_SCREEN_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = binding.serviceComponent ?: USB_SCREEN_SERVICE,
                bindAttempted = binding.bindAttempted,
                bindSucceeded = true,
                binderClassName = binding.binderClassName,
                binderDescriptor = binding.binderDescriptor,
                resolvedClassName = USB_SCREEN_CLASS,
                blocker = "SubScreenService class $USB_SCREEN_CLASS was not reachable.",
                error = error,
            )
        }
        val helperClass = runCatching { packageContext.classLoader.loadClass(USB_SCREEN_HELPER_CLASS) }.getOrElse { error ->
            return failure(
                trigger = trigger,
                attemptedPath = USB_SCREEN_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                serviceComponent = binding.serviceComponent ?: USB_SCREEN_SERVICE,
                bindAttempted = binding.bindAttempted,
                bindSucceeded = true,
                binderClassName = binding.binderClassName,
                binderDescriptor = binding.binderDescriptor,
                resolvedClassName = serviceClass.name,
                blocker = "USB screen helper class $USB_SCREEN_HELPER_CLASS was not reachable.",
                error = error,
            )
        }

        val accessor = serviceClass.methods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                method.returnType.name == helperClass.name
        }
        val helperCandidates = helperClass.methods
            .filter { Modifier.isPublic(it.modifiers) }
            .sortedBy { it.name }
            .joinToString(", ") { formatMethodSignature(it) }
        val serviceCandidates = serviceClass.methods
            .filter { Modifier.isPublic(it.modifiers) && it.declaringClass == serviceClass }
            .sortedBy { it.name }
            .joinToString(", ") { formatMethodSignature(it) }

        logInfo(
            "USB screen classes resolved. accessor=${accessor?.let(::formatMethodSignature) ?: "-"} " +
                "helperCandidates=${helperCandidates.ifBlank { "-" }} " +
                "serviceCandidates=${serviceCandidates.ifBlank { "-" }}",
        )

        return failure(
            trigger = trigger,
            attemptedPath = USB_SCREEN_PATH,
            manufacturer = manufacturer,
            model = model,
            isSunmiDevice = true,
            availablePackages = availablePackages,
            serviceComponent = binding.serviceComponent ?: USB_SCREEN_SERVICE,
            bindAttempted = binding.bindAttempted,
            bindSucceeded = true,
            binderClassName = binding.binderClassName,
            binderDescriptor = binding.binderDescriptor,
            resolvedClassName = helperClass.name,
            managerClassPresent = true,
            managerAccessor = accessor?.let(::formatMethodSignature),
            textMethod = buildString {
                append("helper=[")
                append(helperCandidates.ifBlank { "-" })
                append("] service=[")
                append(serviceCandidates.ifBlank { "-" })
                append("]")
            },
            blocker = "SubScreenService classes resolved, but no safe plain-text method signature was identified.",
        )
    }

    private fun probeFrameworkFallback(
        trigger: String,
        manufacturer: String,
        model: String,
        availablePackages: List<String>,
        text: String,
    ): CustomerDisplayProbeReport {
        logInfo("Starting framework reflection fallback. trigger=$trigger class=$SUNMI_MANAGER_CLASS")
        val managerClass = runCatching { Class.forName(SUNMI_MANAGER_CLASS) }.getOrElse { error ->
            return failure(
                trigger = trigger,
                attemptedPath = FRAMEWORK_FALLBACK_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                managerClassPresent = false,
                blocker = "SUNMI framework class $SUNMI_MANAGER_CLASS was not reachable.",
                error = error,
            )
        }

        val accessor = resolveFrameworkAccessor(managerClass) ?: return failure(
            trigger = trigger,
            attemptedPath = FRAMEWORK_FALLBACK_PATH,
            manufacturer = manufacturer,
            model = model,
            isSunmiDevice = true,
            availablePackages = availablePackages,
            managerClassPresent = true,
            resolvedClassName = managerClass.name,
            blocker = "No safe framework manager accessor was found via reflection.",
        )

        val textCandidates = managerClass.methods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java &&
                (method.returnType == Void.TYPE ||
                    method.returnType == Boolean::class.javaPrimitiveType ||
                    method.returnType == java.lang.Boolean::class.java) &&
                TEXT_METHOD_KEYWORDS.any { keyword -> method.name.contains(keyword, ignoreCase = true) }
        }
        if (textCandidates.size != 1) {
            val blocker = "No unique safe string-based customer display text method was found via reflection."
            logWarn(
                "$blocker candidates=${textCandidates.joinToString { formatMethodSignature(it) }.ifBlank { "-" }}",
            )
            return failure(
                trigger = trigger,
                attemptedPath = FRAMEWORK_FALLBACK_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = managerClass.name,
                managerClassPresent = true,
                managerAccessor = accessor.label,
                blocker = blocker,
            )
        }

        val textMethod = textCandidates.single()
        return try {
            logInfo(
                "Framework fallback method resolved. accessor=${accessor.label} " +
                    "method=${formatMethodSignature(textMethod)}",
            )
            val result = textMethod.invoke(accessor.instance, text)
            val sendSucceeded = when (result) {
                null -> true
                is Boolean -> result
                else -> true
            }
            if (sendSucceeded) {
                logInfo("Framework fallback text send succeeded.")
                CustomerDisplayProbeReport(
                    trigger = trigger,
                    attemptedPath = FRAMEWORK_FALLBACK_PATH,
                    manufacturer = manufacturer,
                    model = model,
                    isSunmiDevice = true,
                    availablePackages = availablePackages,
                    resolvedClassName = managerClass.name,
                    managerClassPresent = true,
                    managerAccessor = accessor.label,
                    textMethod = formatMethodSignature(textMethod),
                    sendAttempted = true,
                    sendSucceeded = true,
                )
            } else {
                failure(
                    trigger = trigger,
                    attemptedPath = FRAMEWORK_FALLBACK_PATH,
                    manufacturer = manufacturer,
                    model = model,
                    isSunmiDevice = true,
                    availablePackages = availablePackages,
                    resolvedClassName = managerClass.name,
                    managerClassPresent = true,
                    managerAccessor = accessor.label,
                    textMethod = formatMethodSignature(textMethod),
                    sendAttempted = true,
                    blocker = "Framework fallback text method returned false.",
                )
            }
        } catch (error: Throwable) {
            failure(
                trigger = trigger,
                attemptedPath = FRAMEWORK_FALLBACK_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = managerClass.name,
                managerClassPresent = true,
                managerAccessor = accessor.label,
                textMethod = formatMethodSignature(textMethod),
                sendAttempted = true,
                blocker = "Framework fallback text method threw an exception.",
                error = error,
            )
        }
    }

    private fun bindVendorService(
        pathName: String,
        intent: Intent,
    ): VendorServiceBindingResult {
        val componentLabel = serviceLabelFor(intent)
        logInfo(
            "Attempting vendor-service bind. path=$pathName component=${componentLabel ?: "-"} " +
                "action=${intent.action ?: "-"}",
        )

        val latch = CountDownLatch(1)
        var bindSucceeded = false
        var binderClassName: String? = null
        var binderDescriptor: String? = null
        var blocker: String? = null
        var bound = false

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                bindSucceeded = true
                binderClassName = service.javaClass.name
                binderDescriptor = runCatching { service.interfaceDescriptor }.getOrNull()
                logInfo(
                    "Vendor service connected. path=$pathName component=${name.flattenToShortString()} " +
                        "binderClass=${binderClassName ?: "-"} descriptor=${binderDescriptor ?: "-"}",
                )
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                logDebug("Vendor service disconnected. path=$pathName component=${name.flattenToShortString()}")
            }
        }
        logInfo("Vendor service start skipped for safety. path=$pathName component=${componentLabel ?: "-"}")

        val bindAttempted = true
        bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            blocker = "bindService failed: ${error.javaClass.simpleName}: ${error.message ?: "No message"}"
            logWarn("Vendor service bind threw. path=$pathName blocker=$blocker")
            false
        }

        if (bound) {
            val connected = latch.await(SERVICE_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!connected && !bindSucceeded) {
                blocker = blocker ?: "Timed out waiting for service connection."
                logWarn("Vendor service bind timed out. path=$pathName")
            }
        } else {
            blocker = blocker ?: "bindService returned false."
            logWarn("Vendor service bind returned false. path=$pathName")
        }

        if (bound) {
            runCatching { appContext.unbindService(connection) }
                .onSuccess { logDebug("Vendor service unbound. path=$pathName") }
                .onFailure { error ->
                    logDebug(
                        "Vendor service unbind failed. path=$pathName reason=${error.javaClass.simpleName}: ${error.message}",
                    )
                }
        }

        return VendorServiceBindingResult(
            serviceComponent = componentLabel,
            bindAttempted = bindAttempted,
            bindSucceeded = bindSucceeded,
            binderClassName = binderClassName,
            binderDescriptor = binderDescriptor,
            blocker = blocker,
        )
    }

    private fun bindVendorServiceSession(
        pathName: String,
        intent: Intent,
    ): VendorServiceSession {
        val componentLabel = serviceLabelFor(intent)
        logInfo(
            "Attempting live vendor-service bind. path=$pathName component=${componentLabel ?: "-"} " +
                "action=${intent.action ?: "-"}",
        )

        val latch = CountDownLatch(1)
        var bindSucceeded = false
        var binder: IBinder? = null
        var binderClassName: String? = null
        var binderDescriptor: String? = null
        var binderAlive = false
        var binderPing = false
        var blocker: String? = null
        var bound = false
        var nullBindingReceived = false
        var bindingDiedReceived = false
        var serviceDisconnectedReceived = false
        var timedOut = false

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                bindSucceeded = true
                binder = service
                binderClassName = service.javaClass.name
                binderDescriptor = runCatching { service.interfaceDescriptor }.getOrNull()
                binderAlive = runCatching { service.isBinderAlive }.getOrDefault(false)
                binderPing = runCatching { service.pingBinder() }.getOrDefault(false)
                logInfo(
                    "Live vendor service connected. path=$pathName component=${name.flattenToShortString()} " +
                        "binderClass=${binderClassName ?: "-"} descriptor=${binderDescriptor ?: "-"} " +
                        "alive=$binderAlive ping=$binderPing",
                )
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                serviceDisconnectedReceived = true
                logDebug("Live vendor service disconnected. path=$pathName component=${name.flattenToShortString()}")
            }

            override fun onNullBinding(name: ComponentName) {
                nullBindingReceived = true
                blocker = blocker ?: "onNullBinding received."
                logWarn("Live vendor service null binding. path=$pathName component=${name.flattenToShortString()}")
                latch.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                bindingDiedReceived = true
                blocker = blocker ?: "onBindingDied received."
                logWarn("Live vendor service binding died. path=$pathName component=${name.flattenToShortString()}")
                latch.countDown()
            }
        }
        logInfo("Live vendor service start skipped for safety. path=$pathName component=${componentLabel ?: "-"}")

        val bindAttempted = true
        bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            blocker = "bindService failed: ${error.javaClass.simpleName}: ${error.message ?: "No message"}"
            logWarn("Live vendor service bind threw. path=$pathName blocker=$blocker")
            false
        }

        if (bound) {
            val connected = latch.await(SERVICE_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!connected && !bindSucceeded) {
                timedOut = true
                blocker = blocker ?: "Timed out waiting for service connection."
                logWarn("Live vendor service bind timed out. path=$pathName")
            }
        } else {
            blocker = blocker ?: "bindService returned false."
            logWarn("Live vendor service bind returned false. path=$pathName")
        }

        return VendorServiceSession(
            serviceComponent = componentLabel,
            bindAttempted = bindAttempted,
            bindSucceeded = bindSucceeded,
            binder = binder,
            binderClassName = binderClassName,
            binderDescriptor = binderDescriptor,
            binderAlive = binderAlive,
            binderPing = binderPing,
            blocker = blocker,
            bound = bound,
            connection = connection,
            nullBindingReceived = nullBindingReceived,
            bindingDiedReceived = bindingDiedReceived,
            serviceDisconnectedReceived = serviceDisconnectedReceived,
            timedOut = timedOut,
            timeoutMs = SERVICE_BIND_TIMEOUT_MS,
        )
    }

    private fun releaseVendorServiceSession(
        pathName: String,
        session: VendorServiceSession,
    ) {
        if (!session.bound || session.connection == null) {
            return
        }
        runCatching { appContext.unbindService(session.connection) }
            .onSuccess { logDebug("Live vendor service unbound. path=$pathName") }
            .onFailure { error ->
                logDebug(
                    "Live vendor service unbind failed. path=$pathName reason=${error.javaClass.simpleName}: ${error.message}",
                )
            }
    }

    private fun serviceLabelFor(intent: Intent): String? {
        intent.component?.flattenToShortString()?.let { return it }
        val packageName = intent.`package`
        val action = intent.action
        return when {
            !packageName.isNullOrBlank() && !action.isNullOrBlank() -> "$packageName $action"
            !packageName.isNullOrBlank() -> packageName
            !action.isNullOrBlank() -> action
            else -> null
        }
    }

    private fun resolveFrameworkAccessor(managerClass: Class<*>): ReflectionAccessor? {
        FRAMEWORK_ACCESSOR_METHODS.forEach { methodName ->
            val method = managerClass.methods.firstOrNull { candidate ->
                Modifier.isStatic(candidate.modifiers) &&
                    candidate.name == methodName &&
                    candidate.parameterCount == 0 &&
                    managerClass.isAssignableFrom(candidate.returnType)
            } ?: return@forEach

            val instance = runCatching { method.invoke(null) }.getOrNull() ?: return@forEach
            return ReflectionAccessor(
                label = "${managerClass.simpleName}.${method.name}()",
                instance = instance,
            )
        }

        FRAMEWORK_ACCESSOR_FIELDS.forEach { fieldName ->
            val field = runCatching { managerClass.getDeclaredField(fieldName) }.getOrNull() ?: return@forEach
            field.isAccessible = true
            val instance = runCatching { field.get(null) }.getOrNull() ?: return@forEach
            return ReflectionAccessor(
                label = "${managerClass.simpleName}.$fieldName",
                instance = instance,
            )
        }

        return null
    }

    private fun invokeNoArgIfPresent(
        targetClass: Class<*>,
        target: Any,
        methodName: String,
    ) {
        val method = targetClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: return
        runCatching { method.invoke(target) }
            .onFailure { error ->
                logDebug(
                    "Optional method invocation failed. class=${targetClass.name} method=$methodName " +
                        "reason=${error.javaClass.simpleName}: ${error.message}",
                )
            }
    }

    private fun readStaticStringField(
        targetClass: Class<*>,
        fieldName: String,
    ): String? {
        val field = runCatching { targetClass.getDeclaredField(fieldName) }.getOrNull() ?: return null
        if (!Modifier.isStatic(field.modifiers)) {
            return null
        }
        field.isAccessible = true
        return runCatching { field.get(null) as? String }.getOrNull()
    }

    private fun createVendorPackageContext(packageName: String): Result<Context> {
        return runCatching {
            @Suppress("DEPRECATION")
            appContext.createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
        }
    }

    private fun getPackageInfoCompat(packageName: String): Result<PackageInfo> {
        return runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PACKAGE_QUERY_FLAGS.toLong()),
                )
            } else {
                appContext.packageManager.getPackageInfo(packageName, PACKAGE_QUERY_FLAGS)
            }
        }
    }

    private fun getInstalledPackagesCompat(): Result<List<PackageInfo>> {
        return runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PACKAGE_QUERY_FLAGS.toLong()),
                )
            } else {
                appContext.packageManager.getInstalledPackages(PACKAGE_QUERY_FLAGS)
            }
        }
    }

    private fun formatMethodSignature(method: Method): String {
        val parameters = method.parameterTypes.joinToString(",") { it.simpleName }
        return "${method.name}($parameters):${method.returnType.simpleName}"
    }

    private fun logAdapterPublicMethods(adapterClass: Class<*>) {
        adapterClass.methods
            .sortedWith(compareBy<Method> { it.name }.thenBy { it.parameterTypes.joinToString(",") { type -> type.name } })
            .forEach { method ->
                logInfo(
                    "LcdAdapter publicMethod name=${method.name} " +
                        "params=${method.parameterTypes.joinToString(",") { it.name }.ifBlank { "-" }}",
                )
            }
    }

    private fun logAdapterFields(
        adapterClass: Class<*>,
        adapter: Any,
    ) {
        adapterClass.declaredFields
            .sortedBy { it.name }
            .forEach { field ->
                field.isAccessible = true
                val stringValue = if (field.type == String::class.java) {
                    runCatching {
                        if (Modifier.isStatic(field.modifiers)) {
                            field.get(null) as? String
                        } else {
                            field.get(adapter) as? String
                        }
                    }.getOrNull()
                } else {
                    null
                }
                logInfo(
                    "LcdAdapter field name=${field.name} type=${field.type.name} " +
                        "value=${stringValue ?: "-"}",
                )
            }
    }

    private fun logPrinterStubFields(
        stubClass: Class<*>,
    ) {
        stubClass.declaredFields
            .filter { field ->
                field.name == "DESCRIPTOR" || field.name.contains("sendLCD", ignoreCase = true)
            }
            .sortedBy { it.name }
            .forEach { field ->
                field.isAccessible = true
                val value = runCatching {
                    if (Modifier.isStatic(field.modifiers)) {
                        field.get(null)
                    } else {
                        null
                    }
                }.getOrNull()
                logInfo(
                    "Printer display field discovered. owner=${stubClass.name} " +
                        "name=${field.name} type=${field.type.name} value=${value ?: "-"}",
                )
            }
    }

    private fun resolveStubClass(
        packageContext: Context,
        serviceClassName: String,
        descriptorClass: Class<*>?,
    ): Class<*>? {
        val candidates = buildList {
            add("$serviceClassName\$Stub")
            descriptorClass?.name?.let { add("$it\$Stub") }
            add(PRINTER_SERVICE_STUB_CLASS)
        }.distinct()
        return candidates.firstNotNullOfOrNull { className ->
            runCatching { packageContext.classLoader.loadClass(className) }.getOrNull()
        }
    }

    private fun resolveServiceProxy(
        stubClass: Class<*>?,
        binder: IBinder,
    ): Any? {
        val asInterfaceMethod = stubClass?.methods?.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "asInterface" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == IBinder::class.java
        } ?: return null
        return runCatching { asInterfaceMethod.invoke(null, binder) }.getOrNull()
    }

    private fun logPrinterInterfaceMethods(
        ownerLabel: String,
        ownerClass: Class<*>,
        sink: MutableList<String>,
    ) {
        ownerClass.methods
            .sortedWith(compareBy<Method> { it.name }.thenBy { it.parameterTypes.joinToString(",") { type -> type.name } })
            .forEach { method ->
                val signature =
                    "${ownerClass.name}.${method.name}(${method.parameterTypes.joinToString(",") { it.name }}):${method.returnType.name}"
                sink += signature
                logInfo("printerInterfaceMethods owner=$ownerLabel signature=$signature")
                if (isPrinterDisplayMethodSignature(signature)) {
                    logInfo("printerInterfaceMethods owner=$ownerLabel relevant=$signature")
                }
            }
    }

    private fun logPrinterDeclaredMethods(
        ownerLabel: String,
        ownerClass: Class<*>,
        sink: MutableList<String>,
    ) {
        ownerClass.declaredMethods
            .sortedWith(compareBy<Method> { it.name }.thenBy { it.parameterTypes.joinToString(",") { type -> type.name } })
            .forEach { method ->
                val signature =
                    "${ownerClass.name}.${method.name}(${method.parameterTypes.joinToString(",") { it.name }}):${method.returnType.name}"
                sink += signature
                logInfo("printerInterfaceMethods owner=$ownerLabel declared=$signature")
                if (isUnifiedPrinterDisplayMethodSignature(signature)) {
                    logInfo("printerInterfaceMethods owner=$ownerLabel relevant=$signature")
                }
            }
    }

    private fun inspectLcdAdapterServiceSurface(
        packageName: String,
        serviceClassName: String,
        session: VendorServiceSession,
        serviceClass: Class<*>?,
        descriptorClass: Class<*>?,
        stubClass: Class<*>?,
        proxy: Any?,
        interfaceClass: Class<*>?,
        methodLogs: List<String>,
    ): LcdAdapterSurfaceState {
        val localInterface = session.binderDescriptor?.let { descriptor ->
            session.binder?.let { binder ->
                runCatching { binder.queryLocalInterface(descriptor) }.getOrNull()
            }
        }
        logInfo(
            "lcdAdapter.surfaceClasses package=$packageName service=$serviceClassName " +
                "serviceClass=${serviceClass?.name ?: "-"} descriptorClass=${descriptorClass?.name ?: "-"} " +
                "stubClass=${stubClass?.name ?: "-"} proxyClass=${proxy?.javaClass?.name ?: "-"} " +
                "interfaceClass=${interfaceClass?.name ?: "-"} localInterface=${localInterface?.javaClass?.name ?: "-"}",
        )

        val dedupedMethods = methodLogs.distinct()
        dedupedMethods.forEach { signature ->
            when {
                isLcdAdapterLifecycleLikeSignature(signature) -> {
                    logInfo("lcdAdapter.lifecycleLike method=$signature")
                }
                isLcdAdapterTextLikeSignature(signature) -> {
                    logInfo("lcdAdapter.textLike method=$signature")
                }
                isLcdAdapterBitmapLikeSignature(signature) -> {
                    logInfo("lcdAdapter.bitmapLike method=$signature")
                }
                isLcdAdapterCommandLikeSignature(signature) -> {
                    logInfo("lcdAdapter.commandLike method=$signature")
                }
                isLcdAdapterPriceLikeSignature(signature) -> {
                    logInfo("lcdAdapter.priceLike method=$signature")
                }
                else -> {
                    logInfo("lcdAdapter.unknownLike method=$signature")
                }
            }
        }

        val lcdLikeMethods = dedupedMethods.filter(::isLcdAdapterLikeSignature)
        val state = LcdAdapterSurfaceState(
            methodCount = dedupedMethods.size,
            lcdLikeMethodCount = lcdLikeMethods.size,
        )
        logInfo(
            "lcdAdapter.surfaceSummary descriptor=${session.binderDescriptor ?: "-"} " +
                "bindSucceeded=${session.bindSucceeded} timedOut=${session.timedOut} " +
                "methodCount=${state.methodCount} lcdLikeMethodCount=${state.lcdLikeMethodCount}",
        )
        return state
    }

    private fun inspectUnifiedBrokerSurface(
        packageContext: Context,
        descriptorClass: Class<*>?,
        stubClass: Class<*>?,
        proxy: Any?,
        interfaceClass: Class<*>?,
        sink: MutableList<String>,
    ): List<UnifiedBrokerAccessorState> {
        proxy?.javaClass?.let { proxyClass ->
            logInfo(
                "unifiedBrokerSurface runtimeClass=${proxyClass.name} " +
                    "superclassChain=${superclassChain(proxyClass)} " +
                    "interfaces=${proxyClass.interfaces.map { it.name }.ifEmpty { listOf("-") }.joinToString(" | ")}",
            )
        } ?: logInfo("unifiedBrokerSurface runtimeClass=- superclassChain=- interfaces=-")

        descriptorClass?.let {
            logMethodCatalog(
                ownerLabel = "unified-broker-descriptor",
                ownerClass = it,
                sink = sink,
                logPrefix = "brokerMethod",
                includeDeclaredMethods = true,
            )
        }
        stubClass?.let {
            logPrinterStubFields(it)
            logMethodCatalog(
                ownerLabel = "unified-broker-stub",
                ownerClass = it,
                sink = sink,
                logPrefix = "brokerMethod",
                includeDeclaredMethods = true,
            )
        }
        interfaceClass?.let {
            logMethodCatalog(
                ownerLabel = "unified-broker-interface",
                ownerClass = it,
                sink = sink,
                logPrefix = "brokerMethod",
                includeDeclaredMethods = true,
            )
        }
        proxy?.javaClass?.let { proxyClass ->
            logMethodCatalog(
                ownerLabel = "unified-broker-proxy",
                ownerClass = proxyClass,
                sink = sink,
                logPrefix = "brokerMethod",
                includeDeclaredMethods = true,
            )
            proxyClass.interfaces.forEach { iface ->
                logMethodCatalog(
                    ownerLabel = "unified-broker-proxy-interface",
                    ownerClass = iface,
                    sink = sink,
                    logPrefix = "brokerMethod",
                    includeDeclaredMethods = true,
                )
            }
            sequenceOf(
                descriptorClass?.enclosingClass,
                stubClass?.enclosingClass,
                interfaceClass?.enclosingClass,
                proxyClass.enclosingClass,
            )
                .filterNotNull()
                .distinctBy(Class<*>::getName)
                .forEach { relatedClass ->
                    logInfo("unifiedBrokerSurface relatedClass=${relatedClass.name}")
                    logMethodCatalog(
                        ownerLabel = "unified-broker-related",
                        ownerClass = relatedClass,
                        sink = sink,
                        logPrefix = "brokerMethod",
                        includeDeclaredMethods = true,
                    )
                }
            sequenceOf(
                descriptorClass?.declaredClasses.orEmpty().asSequence(),
                stubClass?.declaredClasses.orEmpty().asSequence(),
                interfaceClass?.declaredClasses.orEmpty().asSequence(),
                proxyClass.declaredClasses.asSequence(),
            )
                .flatten()
                .distinctBy(Class<*>::getName)
                .forEach { nestedClass ->
                    logInfo("unifiedBrokerSurface nestedClass=${nestedClass.name}")
                    logMethodCatalog(
                        ownerLabel = "unified-broker-nested",
                        ownerClass = nestedClass,
                        sink = sink,
                        logPrefix = "brokerMethod",
                        includeDeclaredMethods = true,
                    )
                }
        } ?: logInfo("unifiedBrokerCandidateEntrypoint state=no_proxy")

        val accessorMethods = proxy?.javaClass?.methods
            ?.filter(::isSafeZeroArgAccessor)
            ?.distinctBy(::formatMethodSignature)
            ?.sortedWith(
                compareBy<Method> { it.name }.thenBy { method ->
                    method.parameterTypes.joinToString(",") { type -> type.name }
                },
            )
            .orEmpty()
        if (accessorMethods.isEmpty()) {
            logInfo("unifiedBrokerCandidateEntrypoint state=none")
            return emptyList()
        }

        val states = accessorMethods.map { method ->
            val signature = formatMethodSignature(method)
            logInfo(
                "unifiedBrokerCandidateEntrypoint method=$signature " +
                    "interestFlags=${methodInterestFlags(method).joinToString(",").ifBlank { "-" }}",
            )
            invokeSafeAccessorForIntrospection(
                packageContext = packageContext,
                proxy = proxy,
                method = method,
                sink = sink,
            )
        }
        val additionalCandidates = states.filter { it.methodName != UNIFIED_BROKER_PRINTER_BINDER_METHOD }
        if (additionalCandidates.isEmpty()) {
            logInfo(
                "unifiedBrokerCandidateEntrypoint state=no_additional_safe_accessors " +
                    "baseMethod=$UNIFIED_BROKER_PRINTER_BINDER_METHOD",
            )
        }
        return states
    }

    private fun logMethodCatalog(
        ownerLabel: String,
        ownerClass: Class<*>,
        sink: MutableList<String>,
        logPrefix: String,
        includeDeclaredMethods: Boolean,
    ) {
        buildList {
            addAll(ownerClass.methods.asList())
            if (includeDeclaredMethods) {
                addAll(ownerClass.declaredMethods.asList())
            }
        }
            .distinctBy { method -> formatMethodSignature(method, ownerClass) }
            .sortedWith(compareBy<Method> { it.name }.thenBy { it.parameterTypes.joinToString(",") { type -> type.name } })
            .forEach { method ->
                val signature = formatMethodSignature(method, ownerClass)
                val interestFlags = methodInterestFlags(method)
                val parameterTypes = method.parameterTypes.joinToString(",") { it.name }.ifBlank { "-" }
                sink += signature
                logInfo(
                    "$logPrefix.catalog owner=$ownerLabel " +
                        "name=${method.name} declaringClass=${method.declaringClass.name} " +
                        "returnType=${method.returnType.name} parameterCount=${method.parameterCount} " +
                        "parameterTypes=$parameterTypes interestFlags=${interestFlags.joinToString(",").ifBlank { "-" }}",
                )
                if ("printerOnly" in interestFlags) {
                    logInfo("$logPrefix.printerOnly owner=$ownerLabel method=$signature")
                }
                if ("customerDisplayCandidate" in interestFlags) {
                    logInfo("$logPrefix.customerDisplayCandidate owner=$ownerLabel method=$signature")
                }
                if ("returnsBinder" in interestFlags) {
                    logInfo("$logPrefix.returnsBinder owner=$ownerLabel method=$signature")
                }
                if ("returnsInterface" in interestFlags) {
                    logInfo("$logPrefix.returnsInterface owner=$ownerLabel method=$signature")
                }
                if ("safeAccessorCandidate" in interestFlags) {
                    logInfo("$logPrefix.safeAccessorCandidate owner=$ownerLabel method=$signature")
                }
                if ("rejectedUnsafe" in interestFlags) {
                    logInfo("$logPrefix.rejectedUnsafe owner=$ownerLabel method=$signature")
                }
            }
    }

    private fun isPrinterDisplayMethodSignature(
        signature: String,
    ): Boolean {
        if (IGNORED_GENERIC_DISPLAY_METHODS.any { ignored ->
                signature.contains(ignored, ignoreCase = true)
            }
        ) {
            return false
        }
        return PRINTER_METHOD_KEYWORDS.any { keyword ->
            signature.contains(keyword, ignoreCase = true)
        }
    }

    private fun isUnifiedPrinterDisplayMethodSignature(
        signature: String,
    ): Boolean {
        if (IGNORED_GENERIC_DISPLAY_METHODS.any { ignored ->
                signature.contains(ignored, ignoreCase = true)
            }
        ) {
            return false
        }
        return isIPrinterLcdBridgeSignature(signature)
    }

    private fun isUnifiedPrinterConfirmedDisplayMethodSignature(
        signature: String,
    ): Boolean {
        if (IGNORED_GENERIC_DISPLAY_METHODS.any { ignored ->
                signature.contains(ignored, ignoreCase = true)
            }
        ) {
            return false
        }
        return isIPrinterLcdBridgeSignature(signature)
    }

    private fun isUnifiedPrinterInfoMethodSignature(
        signature: String,
    ): Boolean {
        return UNIFIED_PRINTER_INFO_KEYWORDS.any { keyword ->
            signature.contains(keyword, ignoreCase = true)
        }
    }

    private fun isSafeZeroArgAccessor(
        method: Method,
    ): Boolean {
        if (method.parameterCount != 0 || method.returnType == Void.TYPE) {
            return false
        }
        val name = method.name
        val lowercaseName = name.lowercase()
        if (name == "getClass" ||
            name == "hashCode" ||
            name == "toString" ||
            name == "asBinder"
        ) {
            return false
        }
        val accessorShape =
            name.startsWith("get") ||
                SAFE_ZERO_ARG_ACCESSOR_SUFFIXES.any { suffix ->
                    name.endsWith(suffix)
                }
        if (!accessorShape) {
            return false
        }
        if (name == UNIFIED_BROKER_PRINTER_BINDER_METHOD) {
            return true
        }
        if (isUnsafeZeroArgAccessorName(lowercaseName)) {
            return false
        }
        return true
    }

    private fun invokeSafeAccessorForIntrospection(
        packageContext: Context,
        proxy: Any?,
        method: Method,
        sink: MutableList<String>,
    ): UnifiedBrokerAccessorState {
        val signature = formatMethodSignature(method)
        logInfo("unifiedBrokerAccessor method=$signature state=attempt")
        if (proxy == null) {
            logWarn("unifiedBrokerAccessor method=$signature state=proxy_missing")
            return UnifiedBrokerAccessorState(
                methodName = method.name,
                signature = signature,
                resultState = "proxy_missing",
                errorSummary = "proxy_missing",
            )
        }
        return try {
            val returnedValue = method.invoke(proxy)
            if (returnedValue == null) {
                logInfo("unifiedBrokerAccessor method=$signature state=returned_null")
                UnifiedBrokerAccessorState(
                    methodName = method.name,
                    signature = signature,
                    resultState = "returned_null",
                )
            } else {
                val returnedClassName = returnedValue.javaClass.name
                val binder = extractBinder(returnedValue)
                val binderDescriptor = binder?.let { runCatching { it.interfaceDescriptor }.getOrNull() }
                val binderAlive = binder?.let { runCatching { it.isBinderAlive }.getOrDefault(false) } ?: false
                val binderPing = binder?.let { runCatching { it.pingBinder() }.getOrDefault(false) } ?: false
                val resultState = when {
                    binder != null -> "returned_binder"
                    method.returnType.isInterface -> "returned_interface"
                    else -> "returned_object"
                }
                logInfo(
                    "unifiedBrokerAccessorResult method=$signature state=$resultState " +
                        "returnedClass=$returnedClassName descriptor=${binderDescriptor ?: "-"} " +
                        "alive=$binderAlive ping=$binderPing",
                )
                if (method.name == UNIFIED_BROKER_PRINTER_BINDER_METHOD) {
                    inspectReturnedObjectSurface(
                        ownerLabel = "broker-return:${method.name}",
                        packageContext = packageContext,
                        returnedValue = returnedValue,
                        sink = sink,
                    )
                } else {
                    logInfo(
                        "unifiedBrokerAccessor method=$signature " +
                            "state=returned_surface_deep_dive_skipped_non_printer",
                    )
                }
                UnifiedBrokerAccessorState(
                    methodName = method.name,
                    signature = signature,
                    resultState = resultState,
                    returnedClassName = returnedClassName,
                    binderDescriptor = binderDescriptor,
                    binderAlive = binderAlive,
                    binderPing = binderPing,
                    returnedValue = returnedValue,
                )
            }
        } catch (error: Throwable) {
            logWarn("unifiedBrokerAccessor method=$signature state=exception${throwableSummary(error)}")
            UnifiedBrokerAccessorState(
                methodName = method.name,
                signature = signature,
                resultState = "exception",
                errorSummary = throwableSummary(error).removePrefix(" ").trim(),
            )
        }
    }

    private fun inspectReturnedObjectSurface(
        ownerLabel: String,
        packageContext: Context,
        returnedValue: Any,
        sink: MutableList<String>,
    ) {
        val rawClass = returnedValue.javaClass
        logInfo(
            "returnedObjectSurface owner=$ownerLabel class=${rawClass.name} " +
                "superclassChain=${superclassChain(rawClass)} " +
                "interfaces=${rawClass.interfaces.map { it.name }.ifEmpty { listOf("-") }.joinToString(" | ")}",
        )
        logMethodCatalog(
            ownerLabel = "$ownerLabel.raw",
            ownerClass = rawClass,
            sink = sink,
            logPrefix = "returnedSurfaceMethod",
            includeDeclaredMethods = true,
        )
        rawClass.interfaces.forEach { iface ->
            logMethodCatalog(
                ownerLabel = "$ownerLabel.interface",
                ownerClass = iface,
                sink = sink,
                logPrefix = "returnedSurfaceMethod",
                includeDeclaredMethods = true,
            )
        }
        val binder = extractBinder(returnedValue)
        if (binder != null) {
            val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
            val alive = runCatching { binder.isBinderAlive }.getOrDefault(false)
            val ping = runCatching { binder.pingBinder() }.getOrDefault(false)
            logInfo(
                "returnedObjectSurface owner=$ownerLabel binderDescriptor=${descriptor ?: "-"} " +
                    "alive=$alive ping=$ping",
            )
            val descriptorClass = descriptor?.let { descriptorName ->
                runCatching { packageContext.classLoader.loadClass(descriptorName) }.getOrNull()
            }
            val stubClass = descriptorClass?.let { descriptorOwner ->
                runCatching { packageContext.classLoader.loadClass("${descriptorOwner.name}\$Stub") }.getOrNull()
            }
            val proxy = when {
                stubClass != null -> resolveServiceProxy(stubClass, binder)
                else -> null
            }
            descriptorClass?.let {
                logMethodCatalog(
                    ownerLabel = "$ownerLabel.descriptor",
                    ownerClass = it,
                    sink = sink,
                    logPrefix = "returnedSurfaceMethod",
                    includeDeclaredMethods = true,
                )
            }
            stubClass?.let {
                logMethodCatalog(
                    ownerLabel = "$ownerLabel.stub",
                    ownerClass = it,
                    sink = sink,
                    logPrefix = "returnedSurfaceMethod",
                    includeDeclaredMethods = true,
                )
            }
            proxy?.javaClass?.let { proxyClass ->
                logMethodCatalog(
                    ownerLabel = "$ownerLabel.proxy",
                    ownerClass = proxyClass,
                    sink = sink,
                    logPrefix = "returnedSurfaceMethod",
                    includeDeclaredMethods = true,
                )
                proxyClass.interfaces.forEach { iface ->
                    logMethodCatalog(
                        ownerLabel = "$ownerLabel.proxy-interface",
                        ownerClass = iface,
                        sink = sink,
                        logPrefix = "returnedSurfaceMethod",
                        includeDeclaredMethods = true,
                    )
                }
            }
        }
    }

    private fun methodInterestFlags(
        method: Method,
    ): List<String> {
        val surfaceSearchSpace = buildString {
            append(method.name)
            append(' ')
            append(method.declaringClass.name)
            append(' ')
            append(method.returnType.name)
        }.lowercase()
        val flags = mutableListOf<String>()
        METHOD_INTEREST_KEYWORDS.forEach { keyword ->
            if (surfaceSearchSpace.contains(keyword)) {
                flags += "kw:$keyword"
            }
        }
        SPECIAL_METHOD_TOKENS.forEach { token ->
            if (surfaceSearchSpace.contains(token)) {
                flags += "token:$token"
            }
        }
        if (IBinder::class.java.isAssignableFrom(method.returnType)) {
            flags += "returnsBinder"
        } else if (
            method.returnType.isInterface ||
            method.returnType.name.contains("aidl", ignoreCase = true) ||
            method.returnType.name.contains("api", ignoreCase = true) ||
            method.returnType.name.contains("manager", ignoreCase = true) ||
            method.returnType.name.contains("service", ignoreCase = true)
        ) {
            flags += "returnsInterface"
        }
        if (BROKER_DISPLAY_CANDIDATE_KEYWORDS.any { keyword ->
                surfaceSearchSpace.contains(keyword)
            }
        ) {
            flags += "customerDisplayCandidate"
        } else if (BROKER_PRINTER_ONLY_KEYWORDS.any { keyword ->
                surfaceSearchSpace.contains(keyword)
            }
        ) {
            flags += "printerOnly"
        }
        if (isSafeZeroArgAccessor(method)) {
            flags += "safeAccessorCandidate"
        } else {
            val lowercaseName = method.name.lowercase()
            val accessorShape =
                method.parameterCount == 0 &&
                    method.returnType != Void.TYPE &&
                    (
                        method.name.startsWith("get") ||
                            SAFE_ZERO_ARG_ACCESSOR_SUFFIXES.any { suffix ->
                                method.name.endsWith(suffix)
                            }
                        )
            if (accessorShape &&
                method.name != UNIFIED_BROKER_PRINTER_BINDER_METHOD &&
                isUnsafeZeroArgAccessorName(lowercaseName)
            ) {
                flags += "rejectedUnsafe"
            }
        }
        return flags.distinct()
    }

    private fun isUnsafeZeroArgAccessorName(
        lowercaseName: String,
    ): Boolean {
        if (lowercaseName == UNIFIED_BROKER_PRINTER_BINDER_METHOD.lowercase()) {
            return false
        }
        return UNSAFE_ZERO_ARG_ACCESSOR_KEYWORDS.any { keyword ->
            when (keyword) {
                "print" -> lowercaseName.contains(keyword) && !lowercaseName.contains("printer")
                else -> lowercaseName.contains(keyword)
            }
        }
    }

    private fun extractBinder(
        instance: Any,
    ): IBinder? {
        return when (instance) {
            is IBinder -> instance
            else -> {
                val asBinderMethod = instance.javaClass.methods.firstOrNull { method ->
                    method.name == "asBinder" &&
                        method.parameterCount == 0 &&
                        IBinder::class.java.isAssignableFrom(method.returnType)
                }
                runCatching { asBinderMethod?.invoke(instance) as? IBinder }.getOrNull()
            }
        }
    }

    private fun formatMethodSignature(
        method: Method,
        ownerClass: Class<*> = method.declaringClass,
    ): String {
        return "${ownerClass.name}.${method.name}(" +
            method.parameterTypes.joinToString(",") { it.name } +
            "):${method.returnType.name}"
    }

    private fun signatureSurfaceSearch(
        signature: String,
    ): String {
        val methodOwner = signature.substringBefore("(")
        val returnType = signature.substringAfter("):", missingDelimiterValue = "")
        return "$methodOwner $returnType".lowercase()
    }

    private fun isLcdAdapterService(
        packageName: String,
        serviceClassName: String,
    ): Boolean {
        return packageName == LCD_ADAPTER_PACKAGE && serviceClassName == LCD_ADAPTER_SERVICE
    }

    private fun methodNameFromSignature(
        signature: String,
    ): String {
        return signature.substringBefore("(").substringAfterLast(".")
    }

    private fun superclassChain(
        ownerClass: Class<*>,
    ): String {
        return generateSequence(ownerClass.superclass) { current -> current.superclass }
            .map { it.name }
            .toList()
            .ifEmpty { listOf("-") }
            .joinToString(" -> ")
    }

    private fun isIPrinterStatusInfoLikeSignature(
        signature: String,
    ): Boolean {
        val surfaceSearch = signatureSurfaceSearch(signature)
        return IPRINTER_STATUS_INFO_TOKENS.any { token ->
            surfaceSearch.contains(token)
        }
    }

    private fun isIPrinterDisplayLikeSignature(
        signature: String,
    ): Boolean {
        val surfaceSearch = signatureSurfaceSearch(signature)
        return IPRINTER_DISPLAY_TOKENS.any { token ->
            surfaceSearch.contains(token)
        }
    }

    private fun isIPrinterCommandLikeSignature(
        signature: String,
    ): Boolean {
        val surfaceSearch = signatureSurfaceSearch(signature)
        return IPRINTER_COMMAND_TOKENS.any { token ->
            surfaceSearch.contains(token)
        }
    }

    private fun isIPrinterLcdBridgeSignature(
        signature: String,
    ): Boolean {
        return IPRINTER_LCD_BRIDGE_METHOD_NAMES.contains(methodNameFromSignature(signature))
    }

    private fun isLcdAdapterLifecycleLikeSignature(
        signature: String,
    ): Boolean {
        val surfaceSearch = signatureSurfaceSearch(signature)
        return LCD_ADAPTER_LIFECYCLE_TOKENS.any { token ->
            surfaceSearch.contains(token)
        }
    }

    private fun isLcdAdapterTextLikeSignature(
        signature: String,
    ): Boolean {
        val surfaceSearch = signatureSurfaceSearch(signature)
        return LCD_ADAPTER_TEXT_TOKENS.any { token ->
            surfaceSearch.contains(token)
        }
    }

    private fun isLcdAdapterBitmapLikeSignature(
        signature: String,
    ): Boolean {
        val surfaceSearch = signatureSurfaceSearch(signature)
        return LCD_ADAPTER_BITMAP_TOKENS.any { token ->
            surfaceSearch.contains(token)
        }
    }

    private fun isLcdAdapterCommandLikeSignature(
        signature: String,
    ): Boolean {
        val surfaceSearch = signatureSurfaceSearch(signature)
        return LCD_ADAPTER_COMMAND_TOKENS.any { token ->
            surfaceSearch.contains(token)
        }
    }

    private fun isLcdAdapterPriceLikeSignature(
        signature: String,
    ): Boolean {
        val surfaceSearch = signatureSurfaceSearch(signature)
        return LCD_ADAPTER_PRICE_TOKENS.any { token ->
            surfaceSearch.contains(token)
        }
    }

    private fun isLcdAdapterLikeSignature(
        signature: String,
    ): Boolean {
        val surfaceSearch = signatureSurfaceSearch(signature)
        return LCD_ADAPTER_METHOD_INTEREST_TOKENS.any { token ->
            surfaceSearch.contains(token)
        }
    }

    private fun collectDisplaySignals(
        packageName: String,
        serviceClassName: String,
        binderDescriptor: String?,
        resolvedClassName: String?,
        methodLogs: List<String>,
    ): List<String> {
        val signals = mutableListOf<String>()
        val identitySignals = listOfNotNull(packageName, serviceClassName, binderDescriptor, resolvedClassName)
            .filter(::containsDisplayIdentityKeyword)
            .map { "identity:$it" }
        signals += identitySignals
        signals += methodLogs.filter(::isPrinterDisplayMethodSignature)
        return signals.distinct()
    }

    private fun containsDisplayIdentityKeyword(
        value: String,
    ): Boolean {
        return DISPLAY_POSITIVE_KEYWORDS.any { keyword ->
            value.contains(keyword, ignoreCase = true)
        }
    }

    private fun nonDisplayServiceReason(
        packageName: String,
        serviceClassName: String,
        binderDescriptor: String?,
        resolvedClassName: String?,
    ): String? {
        val combined = listOfNotNull(packageName, serviceClassName, binderDescriptor, resolvedClassName)
            .joinToString(" ")
        val matchedKeyword = NON_DISPLAY_FAMILY_KEYWORDS.firstOrNull { keyword ->
            combined.contains(keyword, ignoreCase = true)
        }
        return matchedKeyword?.let { "matched_non_display_family=$it" }
    }

    private fun collectServiceActionHints(
        packageContext: Context?,
        serviceClassName: String,
        packageName: String,
    ): List<String> {
        if (packageContext == null) {
            return emptyList()
        }
        val serviceClass = runCatching {
            packageContext.classLoader.loadClass(serviceClassName)
        }.getOrNull() ?: return emptyList()
        return serviceClass.declaredFields
            .asSequence()
            .filter { field ->
                field.type == String::class.java &&
                    Modifier.isStatic(field.modifiers) &&
                    ACTION_HINT_FIELD_KEYWORDS.any { keyword ->
                        field.name.contains(keyword, ignoreCase = true)
                    }
            }
            .mapNotNull { field ->
                field.isAccessible = true
                val value = runCatching { field.get(null) as? String }.getOrNull() ?: return@mapNotNull null
                val looksRelevant =
                    value.contains(packageName, ignoreCase = true) ||
                        value.contains("sunmi", ignoreCase = true) ||
                        value.contains("service", ignoreCase = true)
                if (!looksRelevant) {
                    return@mapNotNull null
                }
                "${field.name}=$value"
            }
            .distinct()
            .sorted()
            .toList()
    }

    private fun inspectUnifiedPrinterBinder(
        packageContext: Context,
        packageName: String,
        rawPrinterBinder: Any,
    ): UnifiedPrinterBinderState {
        val methodLogs = mutableListOf<String>()
        val rawClass = rawPrinterBinder.javaClass
        val binder = when (rawPrinterBinder) {
            is IBinder -> rawPrinterBinder
            else -> {
                val asBinderMethod = rawClass.methods.firstOrNull { method ->
                    method.name == "asBinder" &&
                        method.parameterCount == 0 &&
                        IBinder::class.java.isAssignableFrom(method.returnType)
                }
                runCatching { asBinderMethod?.invoke(rawPrinterBinder) as? IBinder }.getOrNull()
            }
        }
        val binderDescriptor = binder?.let { runCatching { it.interfaceDescriptor }.getOrNull() }
        val binderAlive = binder?.let { runCatching { it.isBinderAlive }.getOrDefault(false) } ?: false
        val binderPing = binder?.let { runCatching { it.pingBinder() }.getOrDefault(false) } ?: false
        val descriptorClass = binderDescriptor?.let { descriptorName ->
            runCatching { packageContext.classLoader.loadClass(descriptorName) }.getOrNull()
        }
        val stubClass = descriptorClass?.let { descriptor ->
            runCatching { packageContext.classLoader.loadClass("${descriptor.name}\$Stub") }.getOrNull()
        }
        val proxy = when {
            stubClass != null && binder != null -> resolveServiceProxy(stubClass, binder)
            else -> null
        }
        descriptorClass?.let {
            logPrinterDeclaredMethods(ownerLabel = "unified-printer-descriptor-declared", ownerClass = it, sink = methodLogs)
            logPrinterInterfaceMethods(ownerLabel = "unified-printer-descriptor", ownerClass = it, sink = methodLogs)
        }
        stubClass?.let {
            logPrinterStubFields(it)
            logPrinterDeclaredMethods(ownerLabel = "unified-printer-stub-declared", ownerClass = it, sink = methodLogs)
            logPrinterInterfaceMethods(ownerLabel = "unified-printer-stub", ownerClass = it, sink = methodLogs)
        }
        rawClass.let {
            logPrinterDeclaredMethods(ownerLabel = "unified-printer-raw-declared", ownerClass = it, sink = methodLogs)
            logPrinterInterfaceMethods(ownerLabel = "unified-printer-raw", ownerClass = it, sink = methodLogs)
        }
        proxy?.javaClass?.let {
            logPrinterDeclaredMethods(ownerLabel = "unified-printer-proxy-declared", ownerClass = it, sink = methodLogs)
            logPrinterInterfaceMethods(ownerLabel = "unified-printer-proxy", ownerClass = it, sink = methodLogs)
        }
        rawClass.interfaces.forEach { iface ->
            logPrinterDeclaredMethods(ownerLabel = "unified-printer-interface-declared", ownerClass = iface, sink = methodLogs)
            logPrinterInterfaceMethods(ownerLabel = "unified-printer-interface", ownerClass = iface, sink = methodLogs)
        }

        val dedupedMethods = methodLogs.distinct()
        dedupedMethods.forEach { signature ->
            if (isIPrinterLcdBridgeSignature(signature)) {
                logInfo("iprinter.lcdBridgeMethod method=$signature")
            }
            when {
                isIPrinterDisplayLikeSignature(signature) -> {
                    logInfo("iprinter.displayLike method=$signature")
                }
                isIPrinterStatusInfoLikeSignature(signature) -> {
                    logInfo("iprinter.statusInfoLike method=$signature")
                }
                isIPrinterCommandLikeSignature(signature) -> {
                    logInfo("iprinter.commandLike method=$signature")
                }
                else -> {
                    logInfo("iprinter.unknownLike method=$signature")
                }
            }
        }
        val lcdBridgeMethods = dedupedMethods.filter(::isIPrinterLcdBridgeSignature)
        val availableLcdBridgeMethods = lcdBridgeMethods
            .map(::methodNameFromSignature)
            .distinct()
            .sorted()
        val missingLcdBridgeMethods = IPRINTER_LCD_BRIDGE_METHOD_NAMES.filterNot { methodName ->
            availableLcdBridgeMethods.contains(methodName)
        }
        logInfo(
            "iprinter.lcdBridgeSummary " +
                "availableMethods=${availableLcdBridgeMethods.ifEmpty { listOf("-") }.joinToString(",")} " +
                "missingMethods=${missingLcdBridgeMethods.ifEmpty { listOf("-") }.joinToString(",")}",
        )
        val displaySignals = lcdBridgeMethods
        val infoSignals = dedupedMethods.filter(::isUnifiedPrinterInfoMethodSignature)
        return UnifiedPrinterBinderState(
            rawClassName = rawClass.name,
            binderDescriptor = binderDescriptor,
            binderAlive = binderAlive,
            binderPing = binderPing,
            resolvedClassName = descriptorClass?.name ?: proxy?.javaClass?.name ?: rawClass.name,
            stubClassPresent = stubClass != null,
            methodLogs = dedupedMethods,
            infoSignals = infoSignals,
            displaySignals = displaySignals,
            lcdBridgeMethods = lcdBridgeMethods,
        )
    }

    private fun probePrinterLibraryFacade(
        trigger: String,
        manufacturer: String,
        model: String,
        availablePackages: List<String>,
    ): CustomerDisplayProbeReport {
        logInfo("printerLibraryProbe entered peripheral-printer trigger=$trigger")
        logInfo("printerLibraryProbe start trigger=$trigger")
        logInfo("printerLibraryProbe surface=peripheral-printer")
        val managerClass = runCatching {
            appContext.classLoader.loadClass(PRINTER_LIBRARY_MANAGER_CLASS)
        }.getOrElse { error ->
            logWarn("printerLibraryProbe earlyReturn reason=missing_manager_class class=$PRINTER_LIBRARY_MANAGER_CLASS")
            logWarn(
                "printerLibraryProbe initFailed missingClass=${PRINTER_LIBRARY_MANAGER_CLASS}" +
                    throwableSummary(error),
            )
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_LIBRARY_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = PRINTER_LIBRARY_MANAGER_CLASS,
                blocker = "SUNMI printerlibrary facade was not loadable.",
                error = error,
            )
        }
        logInfo("printerLibraryProbe dependencyLoaded class=${managerClass.name}")

        val getInstanceMethod = managerClass.methods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "getInstance" &&
                method.parameterCount == 0
        } ?: return failure(
            trigger = trigger,
            attemptedPath = PRINTER_LIBRARY_PATH,
            manufacturer = manufacturer,
            model = model,
            isSunmiDevice = true,
            availablePackages = availablePackages,
            resolvedClassName = managerClass.name,
            managerClassPresent = true,
            blocker = "SUNMI printerlibrary getInstance() was not found.",
        ).also {
            logWarn("printerLibraryProbe earlyReturn reason=missing_getInstance method=getInstance")
        }
        val bindServiceCandidates = managerClass.methods
            .filter { method -> method.name == "bindService" }
            .sortedWith(compareBy<Method> { it.parameterCount }.thenBy { formatMethodSignature(it) })
        bindServiceCandidates.forEach { method ->
            logInfo(
                "printerLibraryProbe managerMethod candidate=${formatMethodSignature(method)} " +
                    "returnType=${method.returnType.name} " +
                    "parameterTypes=${method.parameterTypes.joinToString(",") { it.name }.ifBlank { "-" }}",
            )
        }
        val bindServiceMethod = bindServiceCandidates.firstOrNull { method ->
            method.name == "bindService" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == Context::class.java &&
                method.parameterTypes[1] == InnerPrinterCallback::class.java
        } ?: return failure(
            trigger = trigger,
            attemptedPath = PRINTER_LIBRARY_PATH,
            manufacturer = manufacturer,
            model = model,
            isSunmiDevice = true,
            availablePackages = availablePackages,
            resolvedClassName = managerClass.name,
            managerClassPresent = true,
            managerAccessor = formatMethodSignature(getInstanceMethod),
            blocker = "SUNMI printerlibrary bindService(Context, InnerPrinterCallback) was not found.",
        ).also {
            logWarn("printerLibraryProbe earlyReturn reason=missing_bindService method=bindService")
        }
        val unbindServiceMethod = managerClass.methods.firstOrNull { method ->
            method.name == "unBindService" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == Context::class.java &&
                method.parameterTypes[1] == InnerPrinterCallback::class.java
        }
        logInfo("printerLibraryProbe managerMethod name=bindService present=true")
        logInfo("printerLibraryProbe managerMethod name=unBindService present=${unbindServiceMethod != null}")
        logInfo("printerLibraryProbe managerMethod selected=${formatMethodSignature(bindServiceMethod)}")
        logInfo("printerLibraryProbe callbackInterface class=${InnerPrinterCallback::class.java.name}")
        buildList {
            addAll(InnerPrinterCallback::class.java.methods.asList())
            addAll(InnerPrinterCallback::class.java.declaredMethods.asList())
        }
            .distinctBy { method -> "${method.declaringClass.name}.${formatMethodSignature(method)}" }
            .sortedWith(compareBy<Method> { it.name }.thenBy { formatMethodSignature(it) })
            .forEach { method ->
                logInfo(
                    "printerLibraryProbe callbackMethod name=${method.name} " +
                        "declaringClass=${method.declaringClass.name} " +
                        "returnType=${method.returnType.name} " +
                        "parameterTypes=${method.parameterTypes.joinToString(",") { it.name }.ifBlank { "-" }}",
                )
            }

        val managerInstance = runCatching { getInstanceMethod.invoke(null) }.getOrElse { error ->
            logWarn("printerLibraryProbe earlyReturn reason=getInstance_invocation_failed")
            logWarn(
                "printerLibraryProbe initFailed method=${formatMethodSignature(getInstanceMethod)}" +
                    throwableSummary(error),
            )
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_LIBRARY_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = managerClass.name,
                managerClassPresent = true,
                managerAccessor = formatMethodSignature(getInstanceMethod),
                blocker = "SUNMI printerlibrary getInstance() invocation failed.",
                error = error,
            )
        }
        val legacyWoyouInstalled = isPackageInstalled(LEGACY_WOYOU_PACKAGE)
        val legacyWoyouVisible = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.queryIntentServices(
                Intent(LEGACY_WOYOU_SERVICE_ACTION).setPackage(LEGACY_WOYOU_PACKAGE),
                0,
            ).orEmpty().isNotEmpty()
        }.getOrDefault(false)
        logInfo(
            "printerLibraryProbe legacyWoyouPackage " +
                "installed=$legacyWoyouInstalled visible=$legacyWoyouVisible",
        )

        val callbackReceived = AtomicBoolean(false)
        val disconnectedReceived = AtomicBoolean(false)
        val printerServiceRef = AtomicReference<SunmiPrinterService?>()
        val latch = CountDownLatch(1)
        val callback = object : InnerPrinterCallback() {
            override fun onConnected(service: SunmiPrinterService) {
                callbackReceived.set(true)
                printerServiceRef.set(service)
                logInfo(
                    "printerLibraryProbe callback connected serviceClass=${service.javaClass.name}",
                )
                latch.countDown()
            }

            override fun onDisconnected() {
                disconnectedReceived.set(true)
                logInfo("printerLibraryProbe callback disconnected")
            }
        }
        logInfo("printerLibraryProbe callbackObject class=${callback.javaClass.name}")

        var bindReturned = false
        try {
            val currentThread = Thread.currentThread()
            val isMainThread = Looper.getMainLooper().thread == currentThread
            val isMainLooper = Looper.myLooper() == Looper.getMainLooper()
            logInfo("printerLibraryProbe bindAttempt start")
            logInfo(
                "printerLibraryProbe bindAttempt contextClass=${appContext.javaClass.name} " +
                    "thread=${currentThread.name} isMainThread=$isMainThread isMainLooper=$isMainLooper",
            )
            bindReturned = runCatching {
                val rawResult = bindServiceMethod.invoke(managerInstance, appContext, callback)
                val rawResultKind = when {
                    rawResult == null -> "null"
                    bindServiceMethod.returnType == java.lang.Boolean.TYPE &&
                        rawResult is Boolean && rawResult -> "primitive_boolean_true"
                    bindServiceMethod.returnType == java.lang.Boolean.TYPE &&
                        rawResult is Boolean && !rawResult -> "primitive_boolean_false"
                    bindServiceMethod.returnType == java.lang.Boolean::class.java &&
                        rawResult is Boolean && rawResult -> "boxed_boolean_true"
                    bindServiceMethod.returnType == java.lang.Boolean::class.java &&
                        rawResult is Boolean && !rawResult -> "boxed_boolean_false"
                    else -> "non_boolean:${rawResult.javaClass.name}"
                }
                logInfo(
                    "printerLibraryProbe bindAttempt invokeResult " +
                        "kind=$rawResultKind expectedReturnType=${bindServiceMethod.returnType.name} " +
                        "rawClass=${rawResult?.javaClass?.name ?: "-"} rawValue=${rawResult ?: "null"}",
                )
                (rawResult as? Boolean) == true
            }.getOrElse { error ->
                logWarn("printerLibraryProbe earlyReturn reason=bindService_invocation_failed")
                val cause = (error as? InvocationTargetException)?.targetException
                if (cause != null) {
                    logWarn(
                        "printerLibraryProbe bindAttempt invocationCause " +
                            "class=${cause.javaClass.name} message=${cause.message ?: "-"}",
                    )
                }
                logWarn(
                    "printerLibraryProbe exception method=${formatMethodSignature(bindServiceMethod)}" +
                        throwableSummary(error),
                )
                return failure(
                    trigger = trigger,
                    attemptedPath = PRINTER_LIBRARY_PATH,
                    manufacturer = manufacturer,
                    model = model,
                    isSunmiDevice = true,
                    availablePackages = availablePackages,
                    resolvedClassName = managerClass.name,
                    managerClassPresent = true,
                    managerAccessor = formatMethodSignature(bindServiceMethod),
                    bindAttempted = true,
                    blocker = "SUNMI printerlibrary bindService(...) invocation failed.",
                    error = error,
                )
            }
            logInfo("printerLibraryProbe bindAttempt result=$bindReturned")
            if (!bindReturned && (!legacyWoyouInstalled || !legacyWoyouVisible)) {
                logWarn(
                    "printerLibraryProbe likelyLegacyWoyouDependencyUnavailable " +
                        "installed=$legacyWoyouInstalled visible=$legacyWoyouVisible " +
                        "package=$LEGACY_WOYOU_PACKAGE action=$LEGACY_WOYOU_SERVICE_ACTION",
                )
            }

            if (!latch.await(PRINTER_LIBRARY_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logWarn("printerLibraryProbe earlyReturn reason=callback_timeout timeoutMs=$PRINTER_LIBRARY_CALLBACK_TIMEOUT_MS")
                logWarn(
                    "printerLibraryProbe callbackNotReceivedWithinTimeout " +
                        "timeoutMs=$PRINTER_LIBRARY_CALLBACK_TIMEOUT_MS",
                )
                return failure(
                    trigger = trigger,
                    attemptedPath = PRINTER_LIBRARY_PATH,
                    manufacturer = manufacturer,
                    model = model,
                    isSunmiDevice = true,
                    availablePackages = availablePackages,
                    resolvedClassName = managerClass.name,
                    managerClassPresent = true,
                    managerAccessor = formatMethodSignature(bindServiceMethod),
                    bindAttempted = true,
                    blocker = if (!bindReturned && (!legacyWoyouInstalled || !legacyWoyouVisible)) {
                        "SUNMI printerlibrary bindService likely depends on legacy " +
                            "woyou.aidlservice.jiuiv5, which is unavailable or not visible on this runtime."
                    } else {
                        "SUNMI printerlibrary callback was not received within timeout."
                    },
                )
            }

            val printerService = printerServiceRef.get() ?: return failure(
                trigger = trigger,
                attemptedPath = PRINTER_LIBRARY_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = managerClass.name,
                managerClassPresent = true,
                managerAccessor = formatMethodSignature(bindServiceMethod),
                bindAttempted = true,
                bindSucceeded = bindReturned,
                blocker = "SUNMI printerlibrary callback fired, but no SunmiPrinterService was returned.",
            ).also {
                logWarn("printerLibraryProbe earlyReturn reason=callback_without_service bindReturned=$bindReturned")
            }
            val printerState = inspectPrinterLibraryServiceSurface(printerService)
            logInfo(
                "printerLibraryProbe summary " +
                    "managerLoaded=true bindMethodFound=true callbackReceived=${callbackReceived.get()} " +
                    "serviceObtained=true lcdBridgeFound=${printerState.lcdBridgeFound}",
            )
            if (!printerState.lcdBridgeFound) {
                logWarn("printerLibraryProbe earlyReturn reason=no_lcd_bridge_methods serviceClass=${printerState.serviceClassName}")
                return failure(
                    trigger = trigger,
                    attemptedPath = PRINTER_LIBRARY_PATH,
                    manufacturer = manufacturer,
                    model = model,
                    isSunmiDevice = true,
                    availablePackages = availablePackages,
                    resolvedClassName = printerState.serviceClassName,
                    managerClassPresent = true,
                    managerAccessor = formatMethodSignature(bindServiceMethod),
                    bindAttempted = true,
                    bindSucceeded = true,
                    textMethod = printerState.summaryText,
                    blocker = "Printer library service bound, but no LCD bridge methods were found. See SunmiCustomerDisplay logs.",
                )
            }
            logWarn("printerLibraryProbe earlyReturn reason=lcd_bridge_found_but_no_write_path serviceClass=${printerState.serviceClassName}")
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_LIBRARY_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = printerState.serviceClassName,
                managerClassPresent = true,
                managerAccessor = formatMethodSignature(bindServiceMethod),
                bindAttempted = true,
                bindSucceeded = true,
                textMethod = printerState.summaryText,
                blocker = "Printer library LCD bridge found. Write path is not enabled in this probe.",
            )
        } finally {
            if (unbindServiceMethod != null && bindReturned) {
                logInfo("printerLibraryProbe unbindAttempt start")
                runCatching {
                    unbindServiceMethod.invoke(managerInstance, appContext, callback)
                }.onSuccess {
                    logInfo("printerLibraryProbe unbindAttempt result=success")
                }.onFailure { error ->
                    logWarn("printerLibraryProbe unbindAttempt result=failure${throwableSummary(error)}")
                }
            } else {
                logInfo(
                    "printerLibraryProbe unbindAttempt skipped " +
                        "methodPresent=${unbindServiceMethod != null} bindReturned=$bindReturned " +
                        "callbackReceived=${callbackReceived.get()} disconnected=${disconnectedReceived.get()}",
                )
            }
        }
    }

    private fun probePrinterXFacade(
        trigger: String,
        manufacturer: String,
        model: String,
        availablePackages: List<String>,
    ): CustomerDisplayProbeReport {
        logInfo("printerXProbe start trigger=$trigger")
        val sdkInstance = runCatching { PrinterSdk.getInstance() }.getOrElse { error ->
            logWarn(
                "printerXProbe exception class=${error.javaClass.name} " +
                    "message=${error.message ?: "-"}",
            )
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_X_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = PrinterSdk::class.java.name,
                managerClassPresent = true,
                managerAccessor = "PrinterSdk.getInstance()",
                blocker = "SUNMI printerx SDK instance could not be obtained.",
                error = error,
            )
        }
        logInfo("printerXProbe sdkInstance obtained=${sdkInstance != null}")
        if (sdkInstance == null) {
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_X_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = PrinterSdk::class.java.name,
                managerClassPresent = true,
                managerAccessor = "PrinterSdk.getInstance()",
                blocker = "SUNMI printerx SDK instance was null.",
            )
        }

        val callbackState = AtomicReference("none")
        val printerRef = AtomicReference<PrinterSdk.Printer?>()
        val printerListRef = AtomicReference<List<PrinterSdk.Printer>>(emptyList())
        val latch = CountDownLatch(1)
        val listener = object : PrinterSdk.PrinterListen {
            override fun onDefPrinter(printer: PrinterSdk.Printer) {
                callbackState.set("onDefPrinter")
                printerRef.set(printer)
                logInfo("printerXProbe callback onDefPrinter printerClass=${printer.javaClass.name}")
                latch.countDown()
            }

            override fun onPrinters(printers: MutableList<PrinterSdk.Printer>) {
                callbackState.set("onPrinters")
                val printerList = printers.toList()
                printerListRef.set(printerList)
                val firstPrinter = printerList.firstOrNull()
                if (firstPrinter != null && printerRef.get() == null) {
                    printerRef.set(firstPrinter)
                }
                logInfo(
                    "printerXProbe callback onPrinters " +
                        "count=${printerList.size} firstPrinterClass=${firstPrinter?.javaClass?.name ?: "-"}",
                )
                latch.countDown()
            }
        }

        try {
            logInfo("printerXProbe getPrinter request start")
            sdkInstance.getPrinter(appContext, listener)
        } catch (error: Throwable) {
            logWarn(
                "printerXProbe exception class=${error.javaClass.name} " +
                    "message=${error.message ?: "-"}",
            )
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_X_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = PrinterSdk::class.java.name,
                managerClassPresent = true,
                managerAccessor = "PrinterSdk.getPrinter(Context, PrinterListen)",
                bindAttempted = true,
                blocker = "SUNMI printerx getPrinter(...) invocation failed.",
                error = error,
            )
        }

        if (!latch.await(PRINTER_X_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            logWarn("printerXProbe callback timeout timeoutMs=$PRINTER_X_CALLBACK_TIMEOUT_MS")
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_X_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = PrinterSdk::class.java.name,
                managerClassPresent = true,
                managerAccessor = "PrinterSdk.getPrinter(Context, PrinterListen)",
                bindAttempted = true,
                blocker = "SUNMI printerx callback was not received within timeout.",
            )
        }

        val printer = printerRef.get()
        if (printer == null) {
            val printerCount = printerListRef.get().size
            return failure(
                trigger = trigger,
                attemptedPath = PRINTER_X_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = PrinterSdk::class.java.name,
                managerClassPresent = true,
                managerAccessor = "PrinterSdk.getPrinter(Context, PrinterListen)",
                bindAttempted = true,
                bindSucceeded = true,
                blocker = "SUNMI printerx callback fired, but no Printer was returned. callback=${callbackState.get()} count=$printerCount",
            )
        }

        val printerState = inspectPrinterXPrinterSurface(printer)
        logInfo(
            "printerXProbe summary " +
                "printerObtained=true callback=${callbackState.get()} " +
                "lcdApiFound=${printerState.lcdApiFound} " +
                "queryApiFound=${printerState.queryApiFound} " +
                "lineApiFound=${printerState.lineApiFound} " +
                "canvasApiFound=${printerState.canvasApiFound} " +
                "showDigitalAttempted=${printerState.showDigitalAttempted} " +
                "showDigitalSucceeded=${printerState.showDigitalSucceeded} " +
                "showDigitalFound=${printerState.showDigitalFound} " +
                "configFound=${printerState.configFound} " +
                "showTextFound=${printerState.showTextFound} " +
                "showBitmapFound=${printerState.showBitmapFound}",
        )

        if (printerState.showDigitalSucceeded) {
            logInfo("printerXProbe completed manualWriteSucceeded=true")
            return CustomerDisplayProbeReport(
                trigger = trigger,
                attemptedPath = PRINTER_X_PATH,
                manufacturer = manufacturer,
                model = model,
                isSunmiDevice = true,
                availablePackages = availablePackages,
                resolvedClassName = printerState.printerClassName,
                managerClassPresent = true,
                managerAccessor = "PrinterSdk.getPrinter(Context, PrinterListen)",
                textMethod = printerState.summaryText,
                bindAttempted = true,
                bindSucceeded = true,
                sendAttempted = true,
                sendSucceeded = true,
                blocker = null,
            )
        }

        val blocker = when {
            !printerState.lcdApiPresent ->
                "PrinterX Printer obtained, but lcdApi() accessor was not present. See SunmiCustomerDisplay logs."
            !printerState.lcdApiFound ->
                "PrinterX Printer obtained, but lcdApi() could not be obtained. See SunmiCustomerDisplay logs."
            printerState.showDigitalAttempted ->
                "PrinterX showDigital(\"88.88\") invocation failed. See SunmiCustomerDisplay logs."
            else ->
                "PrinterX lcdApi facade found. Write path is not enabled in this probe."
        }
        return failure(
            trigger = trigger,
            attemptedPath = PRINTER_X_PATH,
            manufacturer = manufacturer,
            model = model,
            isSunmiDevice = true,
            availablePackages = availablePackages,
            resolvedClassName = printerState.printerClassName,
            managerClassPresent = true,
            managerAccessor = "PrinterSdk.getPrinter(Context, PrinterListen)",
            bindAttempted = true,
            bindSucceeded = true,
            textMethod = printerState.summaryText,
            sendAttempted = printerState.showDigitalAttempted,
            blocker = blocker,
        )
    }

    private fun inspectPrinterLibraryServiceSurface(
        service: SunmiPrinterService,
    ): PrinterLibraryProbeState {
        val serviceClass = service.javaClass
        logInfo("printerLibraryProbe callback connected serviceClass=${serviceClass.name}")
        val availableMethodNames = buildList {
            addAll(serviceClass.methods.asList())
            addAll(serviceClass.declaredMethods.asList())
        }
            .distinctBy { method ->
                "${method.declaringClass.name}.${formatMethodSignature(method)}"
            }
            .map { method -> method.name }
            .toSet()
        PRINTER_LIBRARY_LCD_METHOD_NAMES.forEach { methodName ->
            logInfo("printerLibraryProbe serviceMethod name=$methodName present=${availableMethodNames.contains(methodName)}")
        }
        return PrinterLibraryProbeState(
            serviceClassName = serviceClass.name,
            lcdBridgeMethods = PRINTER_LIBRARY_LCD_METHOD_NAMES.filter(availableMethodNames::contains),
            summaryText =
                "serviceClass=${serviceClass.name} " +
                    "lcdBridgeMethods=${PRINTER_LIBRARY_LCD_METHOD_NAMES.filter(availableMethodNames::contains).ifEmpty { listOf("-") }}",
        )
    }

    private fun inspectPrinterXPrinterSurface(
        printer: PrinterSdk.Printer,
    ): PrinterXProbeState {
        val printerClass = printer.javaClass
        val methods = buildList {
            addAll(printerClass.methods.asList())
            addAll(printerClass.declaredMethods.asList())
        }.distinctBy { "${it.declaringClass.name}.${formatMethodSignature(it)}" }
        val methodNames = methods.map { it.name }.toSet()

        PRINTER_X_ACCESSOR_NAMES.forEach { accessorName ->
            logInfo("printerXProbe printerAccessor name=$accessorName present=${methodNames.contains(accessorName)}")
        }

        val lcdApi = runCatching { printer.lcdApi() }
            .onFailure { error ->
                logWarn(
                    "printerXProbe exception class=${error.javaClass.name} " +
                        "message=${error.message ?: "-"} accessor=lcdApi",
                )
            }
            .getOrNull()
        val queryApi = invokePrinterXAccessor(printer, printerClass, "queryApi")
        val lineApi = invokePrinterXAccessor(printer, printerClass, "lineApi")
        val canvasApi = invokePrinterXAccessor(printer, printerClass, "canvasApi")

        logInfo("printerXProbe lcdApiFound=${lcdApi != null}")
        val lcdApiClassName = lcdApi?.javaClass?.name
        if (lcdApiClassName != null) {
            logInfo("printerXProbe lcdApiClass=$lcdApiClassName")
        }

        val lcdApiMethods = lcdApi?.javaClass?.let { lcdApiClass ->
            buildList {
                addAll(lcdApiClass.methods.asList())
                addAll(lcdApiClass.declaredMethods.asList())
            }.distinctBy { "${it.declaringClass.name}.${formatMethodSignature(it)}" }
                .map { it.name }
                .toSet()
        }.orEmpty()

        PRINTER_X_LCD_METHOD_NAMES.forEach { methodName ->
            logInfo("printerXProbe lcdApiMethod name=$methodName present=${lcdApiMethods.contains(methodName)}")
        }

        var showDigitalAttempted = false
        var showDigitalSucceeded = false

        if (lcdApi != null) {
            logInfo("printerXProbe lcdApiObtained=true")
            if (lcdApiMethods.contains("showDigital")) {
                showDigitalAttempted = true
                logInfo("printerXProbe showDigital invoke value=88.88")
                runCatching { lcdApi.showDigital("88.88") }.onSuccess {
                    showDigitalSucceeded = true
                    logInfo("printerXProbe showDigital success value=88.88")
                }.onFailure { error ->
                    logWarn(
                        "printerXProbe exception class=${error.javaClass.name} " +
                            "message=${error.message ?: "-"} method=showDigital",
                    )
                }
            } else {
                logInfo("printerXProbe showDigital skipped reason=method_missing")
            }
        } else {
            logInfo("printerXProbe lcdApiObtained=false")
        }

        return PrinterXProbeState(
            printerClassName = printerClass.name,
            lcdApiPresent = methodNames.contains("lcdApi"),
            queryApiPresent = methodNames.contains("queryApi"),
            lineApiPresent = methodNames.contains("lineApi"),
            canvasApiPresent = methodNames.contains("canvasApi"),
            lcdApiFound = lcdApi != null,
            queryApiFound = queryApi != null,
            lineApiFound = lineApi != null,
            canvasApiFound = canvasApi != null,
            lcdApiClassName = lcdApiClassName,
            showDigitalAttempted = showDigitalAttempted,
            showDigitalSucceeded = showDigitalSucceeded,
            showDigitalFound = lcdApiMethods.contains("showDigital"),
            configFound = lcdApiMethods.contains("config"),
            showTextFound = lcdApiMethods.contains("showText"),
            showBitmapFound = lcdApiMethods.contains("showBitmap"),
            summaryText =
                "printerClass=${printerClass.name} " +
                    "lcdApiFound=${lcdApi != null} " +
                    "queryApiFound=${queryApi != null} " +
                    "lineApiFound=${lineApi != null} " +
                    "canvasApiFound=${canvasApi != null} " +
                    "lcdApiClass=${lcdApiClassName ?: "-"} " +
                    "showDigitalAttempted=$showDigitalAttempted " +
                    "showDigitalSucceeded=$showDigitalSucceeded " +
                    "showDigitalFound=${lcdApiMethods.contains("showDigital")} " +
                    "configFound=${lcdApiMethods.contains("config")} " +
                    "showTextFound=${lcdApiMethods.contains("showText")} " +
                    "showBitmapFound=${lcdApiMethods.contains("showBitmap")}",
        )
    }

    private fun invokePrinterXAccessor(
        printer: PrinterSdk.Printer,
        printerClass: Class<out PrinterSdk.Printer>,
        methodName: String,
    ): Any? {
        val method = printerClass.methods.firstOrNull { candidate ->
            candidate.name == methodName && candidate.parameterCount == 0
        }
        if (method == null) {
            return null
        }
        return runCatching { method.invoke(printer) }
            .onFailure { error ->
                logWarn(
                    "printerXProbe exception class=${error.javaClass.name} " +
                        "message=${error.message ?: "-"} accessor=$methodName",
                )
            }
            .getOrNull()
    }

    private fun isRelevantPackageName(
        packageName: String,
    ): Boolean {
        return PRINTER_PACKAGE_KEYWORDS.any { keyword ->
            packageName.contains(keyword, ignoreCase = true)
        }
    }

    private fun isRelevantServiceName(
        serviceName: String,
    ): Boolean {
        return PRINTER_SERVICE_KEYWORDS.any { keyword ->
            serviceName.contains(keyword, ignoreCase = true)
        }
    }

    private fun shouldAttemptDiscoveryBind(
        packageName: String,
        serviceName: String,
    ): Boolean {
        return PRIORITY_BIND_PACKAGES.contains(packageName) && isRelevantServiceName(serviceName)
    }

    private fun unsafeDiscoveryReason(
        packageName: String,
        serviceName: String,
    ): String? {
        if (serviceName == UNSAFE_SERVICE_FILE_MANAGER) {
            return "explicit_denylist"
        }
        val combined = "$packageName $serviceName"
        val matchedKeyword = UNSAFE_SERVICE_KEYWORDS.firstOrNull { keyword ->
            combined.contains(keyword, ignoreCase = true)
        }
        return matchedKeyword?.let { "matched_keyword=$it" }
    }

    private fun isUnifiedBrokerCandidate(
        packageName: String,
        serviceName: String,
        binderDescriptor: String?,
        resolvedClassName: String?,
    ): Boolean {
        if (packageName != UNIFIED_SDK_PACKAGE) {
            return false
        }
        return serviceName == UNIFIED_SDK_SERVICE ||
            binderDescriptor == UNIFIED_SDK_DESCRIPTOR ||
            resolvedClassName == UNIFIED_SDK_DESCRIPTOR
    }

    private fun executeLcdCommand(
        executeMethod: Method,
        adapter: Any,
        category: String,
        command: String,
        payload: String,
    ): LcdCommandResult {
        logInfo(
            "adapterCommandAttempt category=$category command=$command payload=$payload",
        )
        return try {
            val result = executeMethod.invoke(adapter, category, command, payload)
            val succeeded = (result as? Boolean) == true
            logInfo(
                "adapterCommandResult category=$category command=$command payload=$payload result=$succeeded raw=${result ?: "null"}",
            )
            LcdCommandResult(category = category, command = command, payload = payload, succeeded = succeeded)
        } catch (error: Throwable) {
            val summary = throwableSummary(error)
            logWarn(
                "adapterCommandException category=$category command=$command payload=$payload$summary",
            )
            LcdCommandResult(category = category, command = command, payload = payload, succeeded = false)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun failure(
        trigger: String,
        attemptedPath: String,
        manufacturer: String,
        model: String,
        isSunmiDevice: Boolean,
        availablePackages: List<String>,
        serviceComponent: String? = null,
        bindAttempted: Boolean = false,
        bindSucceeded: Boolean = false,
        binderClassName: String? = null,
        binderDescriptor: String? = null,
        resolvedClassName: String? = null,
        managerClassPresent: Boolean = false,
        managerAccessor: String? = null,
        textMethod: String? = null,
        sendAttempted: Boolean = false,
        blocker: String,
        error: Throwable? = null,
    ): CustomerDisplayProbeReport {
        val suffix = error?.let { throwableSummary(it) }.orEmpty()
        logWarn("Probe path failed. path=$attemptedPath blocker=$blocker$suffix")
        return CustomerDisplayProbeReport(
            trigger = trigger,
            attemptedPath = attemptedPath,
            manufacturer = manufacturer,
            model = model,
            isSunmiDevice = isSunmiDevice,
            availablePackages = availablePackages,
            serviceComponent = serviceComponent,
            bindAttempted = bindAttempted,
            bindSucceeded = bindSucceeded,
            binderClassName = binderClassName,
            binderDescriptor = binderDescriptor,
            resolvedClassName = resolvedClassName,
            managerClassPresent = managerClassPresent,
            managerAccessor = managerAccessor,
            textMethod = textMethod,
            sendAttempted = sendAttempted,
            sendSucceeded = false,
            blocker = if (error == null) blocker else "$blocker${throwableSummary(error)}",
        )
    }

    private fun throwableSummary(error: Throwable): String {
        val unwrapped = when (error) {
            is InvocationTargetException -> error.targetException ?: error
            else -> error
        }
        return " (${unwrapped.javaClass.simpleName}: ${unwrapped.message ?: "No message"})"
    }

    private fun formatCustomerDisplayTotal(totalCents: Int?): String {
        val cents = totalCents ?: 0
        val sign = if (cents < 0) "-" else ""
        val absoluteCents = kotlin.math.abs(cents)
        val whole = absoluteCents / 100
        val fraction = (absoluteCents % 100).toString().padStart(2, '0')
        return "$sign$whole.$fraction"
    }

    private fun logInfo(message: String) {
        Log.i(LOG_TAG, message)
    }

    private fun logWarn(message: String) {
        Log.w(LOG_TAG, message)
    }

    private fun logDebug(message: String) {
        Log.d(LOG_TAG, message)
    }

    private data class VendorServiceBindingResult(
        val serviceComponent: String?,
        val bindAttempted: Boolean,
        val bindSucceeded: Boolean,
        val binderClassName: String?,
        val binderDescriptor: String?,
        val blocker: String?,
    )

    private data class VendorServiceSession(
        val serviceComponent: String?,
        val bindAttempted: Boolean,
        val bindSucceeded: Boolean,
        val binder: IBinder?,
        val binderClassName: String?,
        val binderDescriptor: String?,
        val binderAlive: Boolean,
        val binderPing: Boolean,
        val blocker: String?,
        val bound: Boolean,
        val connection: ServiceConnection?,
        val nullBindingReceived: Boolean,
        val bindingDiedReceived: Boolean,
        val serviceDisconnectedReceived: Boolean,
        val timedOut: Boolean,
        val timeoutMs: Long,
    )

    private data class LcdCommandAttempt(
        val category: String,
        val command: String,
        val payload: String,
    )

    private data class LcdCommandResult(
        val category: String,
        val command: String,
        val payload: String,
        val succeeded: Boolean,
    )

    private data class ReflectionAccessor(
        val label: String,
        val instance: Any,
    )

    private data class UnifiedPrinterBinderState(
        val rawClassName: String,
        val binderDescriptor: String?,
        val binderAlive: Boolean,
        val binderPing: Boolean,
        val resolvedClassName: String?,
        val stubClassPresent: Boolean,
        val methodLogs: List<String>,
        val infoSignals: List<String>,
        val displaySignals: List<String>,
        val lcdBridgeMethods: List<String>,
    )

    private data class UnifiedBrokerAccessorState(
        val methodName: String,
        val signature: String,
        val resultState: String,
        val returnedClassName: String? = null,
        val binderDescriptor: String? = null,
        val binderAlive: Boolean = false,
        val binderPing: Boolean = false,
        val errorSummary: String? = null,
        val returnedValue: Any? = null,
    )

    private data class PrinterLibraryProbeState(
        val serviceClassName: String,
        val lcdBridgeMethods: List<String>,
        val summaryText: String,
    ) {
        val lcdBridgeFound: Boolean
            get() = lcdBridgeMethods.isNotEmpty()
    }

    private data class PrinterXProbeState(
        val printerClassName: String,
        val lcdApiPresent: Boolean,
        val queryApiPresent: Boolean,
        val lineApiPresent: Boolean,
        val canvasApiPresent: Boolean,
        val lcdApiFound: Boolean,
        val queryApiFound: Boolean,
        val lineApiFound: Boolean,
        val canvasApiFound: Boolean,
        val lcdApiClassName: String?,
        val showDigitalAttempted: Boolean,
        val showDigitalSucceeded: Boolean,
        val showDigitalFound: Boolean,
        val configFound: Boolean,
        val showTextFound: Boolean,
        val showBitmapFound: Boolean,
        val summaryText: String,
    )

    private data class LcdAdapterSurfaceState(
        val methodCount: Int,
        val lcdLikeMethodCount: Int,
    )

    private data class DiscoveredServiceCandidate(
        val componentName: String,
        val exported: Boolean,
        val enabled: Boolean,
        val permission: String?,
        val processName: String?,
        val metaDataEntries: List<String>,
        val actionHints: List<String>,
    )

    companion object {
        private const val LOG_TAG = "SunmiCustomerDisplay"
        private const val DEFAULT_TEST_TEXT = "AIROS TEST"
        private const val SERVICE_BIND_TIMEOUT_MS = 1_500L
        private const val PACKAGE_QUERY_FLAGS = PackageManager.GET_SERVICES or PackageManager.GET_META_DATA

        private const val PATH_VENDOR_CHECK = "vendor-check"
        private const val PRINTER_PATH = "sunmi-printer-customer-display"
        private const val PRINTER_LIBRARY_PATH = "sunmi-printerlibrary-wrapper-probe"
        private const val PRINTER_X_PATH = "sunmi-printerx-wrapper-probe"
        private const val LCD_PATH = "sunmi-lcd-adapter-service"
        private const val USB_SCREEN_PATH = "sunmi-usbscreen-service"
        private const val FRAMEWORK_FALLBACK_PATH = "sunmi-framework-reflection-fallback"
        private const val UNIFIED_SDK_PACKAGE = "sunmi.unified.sdk.service"
        private const val UNIFIED_SDK_SERVICE = "com.sunmi.sdk.service.SunmiSDKService"
        private const val UNIFIED_SDK_DESCRIPTOR = "sunmi.unified.sdk.service.aidl.ISunmiApiStoreService"
        private const val UNIFIED_BROKER_PRINTER_BINDER_METHOD = "getPrinterBinder"
        private const val PRINTER_LIBRARY_CALLBACK_TIMEOUT_MS = 2_000L
        private const val PRINTER_X_CALLBACK_TIMEOUT_MS = 2_000L
        private const val CUSTOMER_DISPLAY_EMPTY_TOTAL = "0.00"
        private const val PRINTER_LIBRARY_MANAGER_CLASS = "com.sunmi.peripheral.printer.InnerPrinterManager"
        private const val PRINTER_LIBRARY_SERVICE_CLASS = "com.sunmi.peripheral.printer.SunmiPrinterService"
        private const val LEGACY_WOYOU_PACKAGE = "woyou.aidlservice.jiuiv5"
        private const val LEGACY_WOYOU_SERVICE_ACTION = "woyou.aidlservice.jiuiv5.IWoyouService"

        private const val PRINTER_SERVICE_STUB_CLASS = "com.sunmi.printerx.SunmiPrinterService\$Stub"

        private const val LCD_ADAPTER_PACKAGE = "com.sunmi.adapter.lcd"
        private const val LCD_ADAPTER_SERVICE = "com.sunmi.lcd.AdapterService"
        private const val LCD_ADAPTER_CLASS = "com.sunmi.lcd.LcdAdapter"
        private const val LCD_ADAPTER_ACTION = "ACTION_START_ADAPTER_SERVICE"
        private const val LCD_ADAPTER_SET = "set"
        private const val LCD_ADAPTER_SHOW = "show_digit"

        private const val USB_SCREEN_PACKAGE = "com.sunmi.usbscreen"
        private const val USB_SCREEN_SERVICE = "com.sunmi.usbscreen.service.SubScreenService"
        private const val USB_SCREEN_CLASS = "com.sunmi.usbscreen.service.SubScreenService"
        private const val USB_SCREEN_HELPER_CLASS = "com.sunmi.usbscreen.service.a"

        private const val SUNMI_MANAGER_CLASS = "android.app.sunmi.SunmiCustomerManager"

        private val PRINTER_CANDIDATE_PACKAGES = listOf(
            "com.sunmi.innerprinter",
            "com.sunmi.innerprintadapter",
            "com.sunmi.printerservice",
            "com.sunmi.peripheral.printer",
            "com.sunmi.usbscreen",
            "com.sunmi.adapter.lcd",
            "com.sunmi.printer.firmware",
            "sunmi.unified.sdk.service",
            "sunmi_customer",
            "sunmi.service",
        )
        private val SUNMI_SUPPORT_PACKAGES =
            listOf(
                LCD_ADAPTER_PACKAGE,
                USB_SCREEN_PACKAGE,
                "sunmi_customer",
                "sunmi.service",
            ) + PRINTER_CANDIDATE_PACKAGES
        private val PRINTER_METHOD_KEYWORDS = listOf(
            "lcd",
            "display",
            "screen",
            "digit",
            "price",
            "customer",
            "amount",
            "show",
            "text",
            "string",
            "command",
            "bitmap",
        )
        private val PRINTER_PACKAGE_KEYWORDS = listOf(
            "sunmi",
            "printer",
            "print",
            "lcd",
            "display",
            "screen",
            "digit",
            "customer",
            "unified",
        )
        private val PRINTER_SERVICE_KEYWORDS = listOf(
            "sunmi",
            "printer",
            "print",
            "lcd",
            "display",
            "screen",
            "digit",
            "customer",
            "unified",
        )
        private val BINDABLE_DISCOVERY_PACKAGES = setOf(
            "com.sunmi.innerprinter",
            "com.sunmi.innerprintadapter",
            "com.sunmi.printerservice",
            "com.sunmi.peripheral.printer",
            "com.sunmi.usbscreen",
            "com.sunmi.adapter.lcd",
            "sunmi.unified.sdk.service",
        )
        private val PRIORITY_BIND_PACKAGES = setOf(
            "com.sunmi.adapter.lcd",
            "com.sunmi.innerprintadapter",
            "com.sunmi.usbscreen",
            "sunmi.unified.sdk.service",
        )
        private val UNSAFE_SERVICE_FILE_MANAGER = "com.sunmi.internal.service.fm.FileManagerService"
        private val UNSAFE_SERVICE_KEYWORDS = listOf(
            "filemanager",
            ".fm.",
            "backup",
            "reboot",
            "lockmachine",
            "lock_machine",
            "lock",
            "remotecontrol",
            "remote_control",
            "remote",
            "mdm",
            "deviceadmin",
            "policy",
        )
        private val FRAMEWORK_ACCESSOR_METHODS = listOf("getInstance", "getDefault", "getService", "getManager")
        private val FRAMEWORK_ACCESSOR_FIELDS = listOf("instance", "sInstance", "INSTANCE")
        private val TEXT_METHOD_KEYWORDS = listOf("text", "display", "show", "lcd", "send")
        private val NON_DISPLAY_FAMILY_KEYWORDS = listOf(
            "scanner",
            "rfid",
            "cit",
            "test",
            "osmanager",
        )
        private val DISPLAY_POSITIVE_KEYWORDS = listOf(
            "lcd",
            "customer",
            "display",
            "screen",
            "digit",
            "price",
            "amount",
            "show",
            "text",
            "string",
            "command",
            "bitmap",
        )
        private val IGNORED_GENERIC_DISPLAY_METHODS = listOf(
            ".getDisplay(",
            ".createDisplayContext(",
            ".createWindowContext(",
        )
        private val UNIFIED_PRINTER_DISPLAY_KEYWORDS = listOf(
            "lcd",
            "display",
            "customer",
            "digit",
            "amount",
            "price",
            "text",
            "string",
            "command",
            "bitmap",
            "screen",
            "show",
        )
        private val UNIFIED_PRINTER_CONFIRMED_DISPLAY_KEYWORDS = listOf(
            "lcd",
            "customer",
            "digit",
            "amount",
            "price",
            "screen",
            "display",
            "show",
        )
        private val UNIFIED_PRINTER_INFO_KEYWORDS = listOf(
            "getPrinterModal",
            "getPrinterPaper",
            "getPrinterSerialNo",
            "getPrinterState",
            "getPrinterVersion",
            "getServiceVersion",
            "getTotalPrintLength",
        )
        private val METHOD_INTEREST_KEYWORDS = listOf(
            "lcd",
            "display",
            "customer",
            "screen",
            "digit",
            "digital",
            "amount",
            "price",
            "printer",
            "binder",
            "api",
            "query",
            "line",
            "canvas",
            "file",
            "cashdrawer",
            "show",
            "text",
            "command",
            "bitmap",
        )
        private val SPECIAL_METHOD_TOKENS = listOf(
            "queryapi",
            "lineapi",
            "canvasapi",
            "fileapi",
            "cashdrawerapi",
            "config",
            "getstatus",
            "getinfo",
        )
        private val BROKER_DISPLAY_CANDIDATE_KEYWORDS = listOf(
            "lcd",
            "display",
            "customer",
            "screen",
            "digit",
            "digital",
            "amount",
            "price",
            "show",
            "text",
            "command",
            "bitmap",
            "queryapi",
            "lineapi",
            "canvasapi",
        )
        private val BROKER_PRINTER_ONLY_KEYWORDS = listOf(
            "printer",
            "binder",
        )
        private val SAFE_ZERO_ARG_ACCESSOR_SUFFIXES = listOf(
            "Api",
            "Manager",
            "Service",
            "Binder",
        )
        private val UNSAFE_ZERO_ARG_ACCESSOR_KEYWORDS = listOf(
            "show",
            "print",
            "config",
            "wake",
            "sleep",
            "clear",
            "set",
            "send",
            "start",
            "open",
            "close",
            "init",
        )
        private val IPRINTER_STATUS_INFO_TOKENS = listOf(
            "state",
            "status",
            "version",
            "serial",
            "paper",
            "modal",
            "serviceversion",
            "totalprintlength",
            "info",
        )
        private val IPRINTER_DISPLAY_TOKENS = listOf(
            "lcd",
            "display",
            "customer",
            "screen",
            "digit",
            "digital",
            "price",
            "amount",
            "show",
            "bitmap",
            "text",
            "command",
        )
        private val IPRINTER_COMMAND_TOKENS = listOf(
            "print",
            "send",
            "set",
            "command",
            "text",
            "bitmap",
            "barcode",
            "qrcode",
            "cut",
            "open",
            "close",
            "commit",
            "update",
        )
        private val IPRINTER_LCD_BRIDGE_METHOD_NAMES = listOf(
            "sendLCDAsciiBitmap",
            "sendLCDBarcode",
            "sendLCDBase64Bitmap",
            "sendLCDClearCommand",
            "sendLCDCommand",
            "sendLCDHibernateCommand",
            "sendLCDInitializationCommand",
            "sendLCDMultiString",
            "sendLCDString",
            "sendLCDWakeUpCommand",
        )
        private val PRINTER_LIBRARY_LCD_METHOD_NAMES = listOf(
            "sendLCDCommand",
            "sendLCDString",
            "sendLCDBitmap",
            "sendLCDDoubleString",
            "sendLCDFillString",
            "sendLCDMultiString",
            "sendLCDDigital",
        )
        private val PRINTER_X_ACCESSOR_NAMES = listOf(
            "lcdApi",
            "queryApi",
            "lineApi",
            "canvasApi",
        )
        private val PRINTER_X_LCD_METHOD_NAMES = listOf(
            "showDigital",
            "config",
            "showText",
            "showBitmap",
        )
        private val LCD_ADAPTER_LIFECYCLE_TOKENS = listOf(
            "lcd",
            "init",
            "wake",
            "hibernate",
            "clear",
        )
        private val LCD_ADAPTER_TEXT_TOKENS = listOf(
            "text",
            "string",
            "multistring",
            "digital",
        )
        private val LCD_ADAPTER_BITMAP_TOKENS = listOf(
            "bitmap",
            "asciibitmap",
            "base64bitmap",
            "barcode",
            "qr",
            "url",
            "c128",
        )
        private val LCD_ADAPTER_COMMAND_TOKENS = listOf(
            "lcd",
            "command",
        )
        private val LCD_ADAPTER_PRICE_TOKENS = listOf(
            "price",
            "amount",
        )
        private val LCD_ADAPTER_METHOD_INTEREST_TOKENS = listOf(
            "lcd",
            "command",
            "init",
            "wake",
            "hibernate",
            "clear",
            "text",
            "string",
            "multistring",
            "asciibitmap",
            "base64bitmap",
            "barcode",
            "qr",
            "url",
            "c128",
            "price",
            "amount",
            "digital",
        )
        private val UNIFIED_BROKER_METHOD_KEYWORDS = listOf(
            "lcd",
            "display",
            "customer",
            "screen",
            "digit",
            "amount",
            "price",
            "printer",
            "binder",
            "show",
            "text",
            "string",
            "command",
            "bitmap",
        )
        private val UNIFIED_BROKER_DISPLAY_KEYWORDS = listOf(
            "lcd",
            "display",
            "customer",
            "screen",
            "digit",
            "amount",
            "price",
            "show",
        )
        private val UNIFIED_BROKER_PRINTER_ONLY_KEYWORDS = listOf(
            "printer",
            "binder",
        )
        private val ACTION_HINT_FIELD_KEYWORDS = listOf(
            "action",
            "intent",
            "interface",
            "service",
        )
    }
}
