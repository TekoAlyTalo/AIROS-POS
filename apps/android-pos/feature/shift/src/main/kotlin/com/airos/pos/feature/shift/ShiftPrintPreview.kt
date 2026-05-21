package com.airos.pos.feature.shift

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.PlannedStaffShift
import com.airos.pos.core.model.ShiftSchedulePublicationStatus
import com.airos.pos.core.model.ShiftScheduleSnapshot
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max

private val PreviewBackdrop = Color(0xCC000000)
private val PreviewPanel = Color(0xFF101A23)
private val PreviewPanelRaised = Color(0xFF182633)
private val PreviewBorder = Color(0x24FFFFFF)
private val PreviewText = Color(0xFFFBFEFF)
private val PreviewMuted = Color(0xFFC0CCD6)
private val PreviewCyan = Color(0xFF85F5E0)
private val PreviewGold = Color(0xFFD6A557)
private val PreviewDanger = Color(0xFFFF6B65)
private val ReceiptPaper = Color(0xFFFAFAF7)
private val ReceiptInk = Color(0xFF161616)
private val ReceiptLine = Color(0xFF3E3E3E)
private val ReceiptSoft = Color(0xFFF0F1F1)

private val PrintWeekdayLabels = listOf("Ma", "Ti", "Ke", "To", "Pe", "La", "Su")
private val PrintTimestampFormatter = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm", Locale("fi", "FI"))
private const val ShiftReceiptPoweredByText = "Powered by TekoÄlyTalo Oy"

@Composable
internal fun ShiftPrintPreviewOverlay(
    schedule: ShiftScheduleSnapshot?,
    currentStaffId: String?,
    currentStaffName: String?,
    loading: Boolean,
    message: String?,
    anchorDate: LocalDate,
    onDismiss: () -> Unit,
    receiptLogoPainter: Painter? = null,
    receiptLogoBitmap: Bitmap? = null,
    onPrintReceiptBitmap: suspend (Bitmap) -> PosResult<Unit> = {
        PosResult.Failure("Kuittitulostinta ei ole kytketty tähän näkymään.")
    },
    modifier: Modifier = Modifier,
) {
    var printNotice by remember { mutableStateOf<String?>(null) }
    var printNoticeIsError by remember { mutableStateOf(false) }
    var isPrinting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val printedAt = remember { LocalDateTime.now() }
    val printedAtText = remember(printedAt) { printedAt.format(PrintTimestampFormatter) }
    val weeks = remember(schedule, currentStaffId, anchorDate) {
        buildShiftPrintWeeks(schedule = schedule, currentStaffId = currentStaffId, anchorDate = anchorDate)
    }
    val staffName = currentStaffName?.takeIf { it.isNotBlank() } ?: "Aktiivinen myyjä puuttuu"
    val hasPrintableShifts = weeks.any { week -> week.days.any { day -> day.shifts.isNotEmpty() } }
    val qrPayload = remember(weeks, currentStaffId, schedule) {
        buildShiftReceiptQrPayload(
            weeks = weeks,
            staffId = currentStaffId,
            hasSchedule = schedule != null,
        )
    }

    Box(
        modifier = modifier
            .background(PreviewBackdrop)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clickable(enabled = false, onClick = {}),
            shape = RoundedCornerShape(22.dp),
            color = PreviewPanel,
            border = BorderStroke(1.dp, PreviewBorder),
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Työvuorokuitti – Esikatselu",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = PreviewText,
                        )
                        Text(
                            text = "4 viikon näkymä · logo vasemmalla · QR oikealla",
                            style = MaterialTheme.typography.bodySmall,
                            color = PreviewMuted,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                when {
                                    isPrinting -> Unit
                                    currentStaffId.isNullOrBlank() -> {
                                        printNotice = "Aktiivista työntekijää ei ole valittu. Tulostusta ei tehty."
                                        printNoticeIsError = true
                                    }
                                    loading && schedule == null -> {
                                        printNotice = "Omat vuorot latautuvat vielä. Tulostusta ei tehty."
                                        printNoticeIsError = true
                                    }
                                    schedule == null -> {
                                        printNotice = "Omat vuorot eivät ole saatavilla. Tulostusta ei tehty."
                                        printNoticeIsError = true
                                    }
                                    !hasPrintableShifts -> {
                                        printNotice = "Ei tulostettavia työvuoroja tällä neljän viikon jaksolla."
                                        printNoticeIsError = true
                                    }
                                    else -> {
                                        isPrinting = true
                                        printNotice = "Lähetetään työvuorokuitti kuittitulostimelle..."
                                        printNoticeIsError = false
                                        scope.launch {
                                            val bitmap = renderShiftScheduleReceiptBitmap(
                                                weeks = weeks,
                                                staffName = staffName,
                                                printedAtText = printedAtText,
                                                logoBitmap = receiptLogoBitmap,
                                                qrPayload = qrPayload,
                                            )
                                            when (val result = onPrintReceiptBitmap(bitmap)) {
                                                is PosResult.Success -> {
                                                    printNotice = "Työvuorokuitti lähetetty kuittitulostimelle."
                                                    printNoticeIsError = false
                                                }
                                                is PosResult.Failure -> {
                                                    printNotice = "Tulostus epäonnistui: ${result.message}"
                                                    printNoticeIsError = true
                                                }
                                            }
                                            isPrinting = false
                                        }
                                    }
                                }
                            },
                            enabled = !isPrinting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PreviewCyan.copy(alpha = 0.86f),
                                contentColor = Color(0xFF071109),
                                disabledContainerColor = PreviewCyan.copy(alpha = 0.36f),
                                disabledContentColor = Color(0xFF071109).copy(alpha = 0.72f),
                            ),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(if (isPrinting) "Tulostetaan..." else "Tulosta", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PreviewPanelRaised,
                                contentColor = PreviewText,
                            ),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("Sulje", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                printNotice?.takeIf { it.isNotBlank() }?.let { notice ->
                    val tint = if (printNoticeIsError) PreviewDanger else PreviewGold
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = tint.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, tint.copy(alpha = 0.34f)),
                    ) {
                        Text(
                            text = notice,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PreviewText,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                ShiftReceiptPreviewCard(
                    weeks = weeks,
                    staffName = staffName,
                    printedAtText = printedAtText,
                    loading = loading,
                    message = message,
                    hasStaff = !currentStaffId.isNullOrBlank(),
                    hasSchedule = schedule != null,
                    logoPainter = receiptLogoPainter,
                    logoBitmap = receiptLogoBitmap,
                    qrPayload = qrPayload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ShiftReceiptPreviewCard(
    weeks: List<ShiftPrintWeek>,
    staffName: String,
    printedAtText: String,
    loading: Boolean,
    message: String?,
    hasStaff: Boolean,
    hasSchedule: Boolean,
    logoPainter: Painter?,
    logoBitmap: Bitmap?,
    qrPayload: String,
    modifier: Modifier = Modifier,
) {
    val qrBitmap = remember(qrPayload) { renderQrCodeBitmap(qrPayload, 520) }
    val safeLogoBitmap = remember(logoBitmap) { logoBitmap?.scaledForShiftReceiptLogo() }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = ReceiptPaper,
        border = BorderStroke(1.dp, Color(0xFFDEDEDE)),
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShiftReceiptLogoArea(
                logoPainter = logoPainter,
                logoBitmap = safeLogoBitmap,
                modifier = Modifier
                    .width(148.dp)
                    .fillMaxHeight(),
            )
            ShiftReceiptDivider()
            ShiftReceiptCalendarArea(
                weeks = weeks,
                staffName = staffName,
                printedAtText = printedAtText,
                loading = loading,
                message = message,
                hasStaff = hasStaff,
                hasSchedule = hasSchedule,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            ShiftReceiptDivider()
            ShiftReceiptQrArea(
                qrBitmap = qrBitmap,
                modifier = Modifier
                    .width(160.dp)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ShiftReceiptLogoArea(
    logoPainter: Painter?,
    logoBitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            logoBitmap != null -> {
                Image(
                    bitmap = logoBitmap.asImageBitmap(),
                    contentDescription = "BarLast",
                    modifier = Modifier
                        .fillMaxWidth(0.98f)
                        .aspectRatio(1f),
                    contentScale = ContentScale.Fit,
                )
            }
            logoPainter != null -> {
                Image(
                    painter = logoPainter,
                    contentDescription = "BarLast",
                    modifier = Modifier
                        .fillMaxWidth(0.98f)
                        .aspectRatio(1f),
                    contentScale = ContentScale.Fit,
                )
            }
            else -> {
                Text(
                    text = "BARLAST",
                    style = MaterialTheme.typography.titleLarge,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Bold,
                    color = ReceiptInk,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ShiftReceiptDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(ReceiptLine.copy(alpha = 0.46f)),
    )
}

@Composable
private fun ShiftReceiptCalendarArea(
    weeks: List<ShiftPrintWeek>,
    staffName: String,
    printedAtText: String,
    loading: Boolean,
    message: String?,
    hasStaff: Boolean,
    hasSchedule: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "AIROS · Omat työvuorot",
            style = MaterialTheme.typography.titleLarge,
            color = ReceiptInk,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = staffName,
            style = MaterialTheme.typography.bodyLarge,
            color = ReceiptInk,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Tulostettu $printedAtText",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF606060),
            maxLines = 1,
        )

        when {
            !hasStaff -> ShiftReceiptUnavailableText("Aktiivista työntekijää ei ole valittu.")
            loading && !hasSchedule -> ShiftReceiptUnavailableText("Haetaan omia vuoroja...")
            !hasSchedule -> ShiftReceiptUnavailableText("Omat vuorot eivät ole saatavilla.")
            weeks.isEmpty() -> ShiftReceiptUnavailableText("Ei tulostettavia työvuoroja tällä jaksolla.")
            else -> ShiftReceiptFourWeekGrid(
                weeks = weeks,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }

        if (!message.isNullOrBlank() && hasSchedule) {
            Text(
                text = "Huom: käytössä viimeksi ladattu vuoronäkymä.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF606060),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShiftReceiptFourWeekGrid(
    weeks: List<ShiftPrintWeek>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(BorderStroke(1.4.dp, ReceiptLine)),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp),
        ) {
            ShiftReceiptCell(text = "", weight = 0.9f, header = true)
            PrintWeekdayLabels.forEach { label ->
                ShiftReceiptCell(text = label, weight = 1f, header = true)
            }
        }
        weeks.take(4).forEach { week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                ShiftReceiptCell(text = week.label, weight = 0.9f, bold = true)
                week.days.forEach { day ->
                    ShiftReceiptDayCell(day = day, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.ShiftReceiptCell(
    text: String,
    weight: Float,
    header: Boolean = false,
    bold: Boolean = false,
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxSize()
            .background(if (header) ReceiptSoft else Color.Transparent)
            .border(BorderStroke(1.dp, ReceiptLine.copy(alpha = 0.78f)))
            .padding(horizontal = 3.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (header || bold) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            color = ReceiptInk,
            fontWeight = if (header || bold) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ShiftReceiptDayCell(
    day: ShiftPrintDay,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(Color.Transparent)
            .border(BorderStroke(1.dp, ReceiptLine.copy(alpha = 0.78f)))
            .padding(horizontal = 2.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (day.shifts.isEmpty()) {
                Text(
                    text = "–",
                    style = MaterialTheme.typography.labelMedium,
                    color = ReceiptInk,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            } else {
                day.shifts.take(2).forEach { shift ->
                    Text(
                        text = shiftPrintTimeRange(shift),
                        style = MaterialTheme.typography.labelSmall,
                        color = ReceiptInk,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                if (day.shifts.size > 2) {
                    Text(
                        text = "+${day.shifts.size - 2}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ReceiptInk,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ShiftReceiptUnavailableText(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = ReceiptInk,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ShiftReceiptQrArea(
    qrBitmap: Bitmap,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Skannaa vuorot\npuhelimeen",
            style = MaterialTheme.typography.labelLarge,
            color = ReceiptInk,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = "Työvuorojen QR-koodi",
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .aspectRatio(1f),
            contentScale = ContentScale.Fit,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = ShiftReceiptPoweredByText,
            style = MaterialTheme.typography.labelSmall,
            color = ReceiptInk,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class ShiftPrintWeek(
    val label: String,
    val days: List<ShiftPrintDay>,
)

private data class ShiftPrintDay(
    val date: LocalDate,
    val shifts: List<PlannedStaffShift>,
)

private fun buildShiftPrintWeeks(
    schedule: ShiftScheduleSnapshot?,
    currentStaffId: String?,
    anchorDate: LocalDate,
): List<ShiftPrintWeek> {
    if (schedule == null || currentStaffId.isNullOrBlank()) return emptyList()
    val weekStart = anchorDate.minusDays((anchorDate.dayOfWeek.value - 1).toLong())
    val publishedDays = schedule.days.filter { day ->
        day.publicationStatus == ShiftSchedulePublicationStatus.PUBLISHED ||
            day.publicationStatus == ShiftSchedulePublicationStatus.CLOSED
    }
    val shiftsByDate = publishedDays
        .flatMap { day ->
            day.plannedShifts
                .filter { shift -> shift.staffId == currentStaffId }
                .map { shift -> day.date to shift }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, shifts) -> shifts.sortedBy { it.startsAt } }

    return (0 until 4).map { weekIndex ->
        val start = weekStart.plusDays((weekIndex * 7).toLong())
        val days = (0..6).map { dayIndex ->
            val date = start.plusDays(dayIndex.toLong())
            ShiftPrintDay(date = date, shifts = shiftsByDate[date].orEmpty())
        }
        ShiftPrintWeek(
            label = "${printDateShort(start)}–${printDateShort(start.plusDays(6))}",
            days = days,
        )
    }
}

private fun shiftPrintTimeRange(shift: PlannedStaffShift): String {
    fun compactTime(value: LocalDateTime): String {
        val midnightNextDay = value.toLocalDate().isAfter(shift.startsAt.toLocalDate()) &&
            value.hour == 0 && value.minute == 0
        val hour = if (midnightNextDay) 24 else value.hour
        return "$hour.${value.minute.toString().padStart(2, '0')}"
    }
    val range = "${compactTime(shift.startsAt)}–${compactTime(shift.endsAt)}"
    return if (shift.endsAt.toLocalDate().isAfter(shift.startsAt.toLocalDate()) &&
        !(shift.endsAt.hour == 0 && shift.endsAt.minute == 0)
    ) {
        "$range +1"
    } else {
        range
    }
}

private fun printDateShort(date: LocalDate): String = "${date.dayOfMonth}.${date.monthValue}."

private fun buildShiftReceiptQrPayload(
    weeks: List<ShiftPrintWeek>,
    staffId: String?,
    hasSchedule: Boolean,
): String {
    val allDays = weeks.flatMap { it.days }
    val startDate = allDays.firstOrNull()?.date
    val endDate = allDays.lastOrNull()?.date
    val safeStaffId = staffId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        ?: "NO-STAFF"
    val from = startDate?.toString() ?: "NO-DATE"
    val to = endDate?.toString() ?: "NO-DATE"
    val hasShifts = allDays.any { it.shifts.isNotEmpty() }
    val basePayload = "AIROS-SHIFT:$safeStaffId:$from:$to"
    return when {
        staffId.isNullOrBlank() -> "$basePayload:NO-STAFF"
        !hasSchedule -> "$basePayload:NO-SHIFTS-AVAILABLE"
        !hasShifts -> "$basePayload:NO-SHIFTS"
        else -> basePayload
    }
}

private fun Bitmap.scaledForShiftReceiptLogo(maxEdge: Int = 512): Bitmap {
    if (isRecycled) return this
    val longestEdge = max(width, height)
    if (longestEdge <= maxEdge) return this
    val scale = maxEdge.toFloat() / longestEdge.toFloat()
    val targetWidth = max(1, (width * scale).toInt())
    val targetHeight = max(1, (height * scale).toInt())
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

private fun renderQrCodeBitmap(payload: String, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawQrCode(AndroidCanvas(bitmap), payload, RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()))
    return bitmap
}

private fun drawQrCode(canvas: AndroidCanvas, payload: String, rect: RectF) {
    val modules = ShiftQrEncoder.encode(payload)
    val quietZone = 4
    val moduleCount = modules.size + quietZone * 2
    val moduleSize = minOf(rect.width(), rect.height()) / moduleCount.toFloat()
    val qrSize = moduleSize * moduleCount
    val left = rect.centerX() - qrSize / 2f
    val top = rect.centerY() - qrSize / 2f
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }
    val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        style = Paint.Style.FILL
    }
    canvas.drawRect(RectF(left, top, left + qrSize, top + qrSize), white)
    modules.forEachIndexed { y, row ->
        row.forEachIndexed { x, dark ->
            if (dark) {
                val cellLeft = left + (x + quietZone) * moduleSize
                val cellTop = top + (y + quietZone) * moduleSize
                canvas.drawRect(
                    cellLeft,
                    cellTop,
                    cellLeft + moduleSize + 0.05f,
                    cellTop + moduleSize + 0.05f,
                    black,
                )
            }
        }
    }
}

private object ShiftQrEncoder {
    private const val MASK_PATTERN = 0

    private data class Spec(
        val version: Int,
        val dataCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val group1Blocks: Int,
        val group1DataCodewords: Int,
        val group2Blocks: Int,
        val group2DataCodewords: Int,
        val alignmentPatternCenters: IntArray,
    )

    private val specs = listOf(
        Spec(1, 19, 7, 1, 19, 0, 0, intArrayOf()),
        Spec(2, 34, 10, 1, 34, 0, 0, intArrayOf(6, 18)),
        Spec(3, 55, 15, 1, 55, 0, 0, intArrayOf(6, 22)),
        Spec(4, 80, 20, 1, 80, 0, 0, intArrayOf(6, 26)),
        Spec(5, 108, 26, 1, 108, 0, 0, intArrayOf(6, 30)),
        Spec(10, 274, 18, 2, 68, 2, 69, intArrayOf(6, 28, 50)),
        Spec(15, 523, 22, 5, 87, 1, 88, intArrayOf(6, 26, 48, 70)),
        Spec(20, 861, 28, 3, 107, 5, 108, intArrayOf(6, 34, 62, 90)),
        Spec(25, 1276, 26, 8, 106, 4, 107, intArrayOf(6, 32, 58, 84, 110)),
    )

    fun encode(payload: String): Array<BooleanArray> {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val spec = specs.firstOrNull { bytes.size + 3 <= it.dataCodewords } ?: specs.last()
        val finalBytes = if (bytes.size + 3 <= spec.dataCodewords) {
            bytes
        } else {
            "BarLast\nTyövuorolista ei mahdu QR-koodiin.".toByteArray(StandardCharsets.UTF_8)
        }
        val dataCodewords = encodeDataCodewords(finalBytes, spec)
        val allCodewords = addErrorCorrectionAndInterleave(dataCodewords, spec)
        return drawMatrix(allCodewords, spec)
    }

    private fun encodeDataCodewords(bytes: ByteArray, spec: Spec): IntArray {
        val bits = mutableListOf<Boolean>()
        appendBits(bits, 0b0100, 4)
        appendBits(bits, bytes.size, if (spec.version <= 9) 8 else 16)
        bytes.forEach { appendBits(bits, it.toInt() and 0xFF, 8) }
        repeat(minOf(4, spec.dataCodewords * 8 - bits.size)) { bits += false }
        while (bits.size % 8 != 0) bits += false

        val result = mutableListOf<Int>()
        bits.chunked(8).forEach { chunk ->
            var value = 0
            chunk.forEach { bit -> value = (value shl 1) or if (bit) 1 else 0 }
            result += value
        }
        var pad = 0
        while (result.size < spec.dataCodewords) {
            result += if (pad % 2 == 0) 0xEC else 0x11
            pad++
        }
        return result.toIntArray()
    }

    private fun addErrorCorrectionAndInterleave(dataCodewords: IntArray, spec: Spec): IntArray {
        val dataBlocks = mutableListOf<IntArray>()
        var cursor = 0
        repeat(spec.group1Blocks) {
            dataBlocks += dataCodewords.copyOfRange(cursor, cursor + spec.group1DataCodewords)
            cursor += spec.group1DataCodewords
        }
        repeat(spec.group2Blocks) {
            dataBlocks += dataCodewords.copyOfRange(cursor, cursor + spec.group2DataCodewords)
            cursor += spec.group2DataCodewords
        }

        val divisor = reedSolomonDivisor(spec.ecCodewordsPerBlock)
        val ecBlocks = dataBlocks.map { reedSolomonRemainder(it, divisor) }
        val result = mutableListOf<Int>()
        val maxDataCodewords = dataBlocks.maxOf { it.size }

        for (i in 0 until maxDataCodewords) {
            dataBlocks.forEach { block ->
                if (i < block.size) result += block[i]
            }
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            ecBlocks.forEach { block -> result += block[i] }
        }
        return result.toIntArray()
    }

    private fun drawMatrix(codewords: IntArray, spec: Spec): Array<BooleanArray> {
        val size = spec.version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun setFunction(x: Int, y: Int, dark: Boolean) {
            if (x !in 0 until size || y !in 0 until size) return
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        fun drawFinderPattern(centerX: Int, centerY: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    setFunction(centerX + dx, centerY + dy, distance == 3 || distance <= 1)
                }
            }
        }

        fun drawAlignmentPattern(centerX: Int, centerY: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) {
                    setFunction(centerX + dx, centerY + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                }
            }
        }

        drawFinderPattern(3, 3)
        drawFinderPattern(size - 4, 3)
        drawFinderPattern(3, size - 4)

        for (i in 0 until size) {
            if (!isFunction[6][i]) setFunction(i, 6, i % 2 == 0)
            if (!isFunction[i][6]) setFunction(6, i, i % 2 == 0)
        }

        spec.alignmentPatternCenters.forEach { y ->
            spec.alignmentPatternCenters.forEach { x ->
                val overlapsFinder = (x == 6 && y == 6) ||
                    (x == 6 && y == size - 7) ||
                    (x == size - 7 && y == 6)
                if (!overlapsFinder) drawAlignmentPattern(x, y)
            }
        }

        drawFormatBits(modules, isFunction, size, MASK_PATTERN)
        drawVersionBits(modules, isFunction, spec.version, size)
        drawCodewords(modules, isFunction, codewords, size)
        drawFormatBits(modules, isFunction, size, MASK_PATTERN)
        return modules
    }

    private fun drawCodewords(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        codewords: IntArray,
        size: Int,
    ) {
        var bitIndex = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right--
            for (vertical in 0 until size) {
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vertical else vertical
                for (j in 0..1) {
                    val x = right - j
                    if (!isFunction[y][x]) {
                        val bit = if (bitIndex < codewords.size * 8) {
                            ((codewords[bitIndex ushr 3] ushr (7 - (bitIndex and 7))) and 1) != 0
                        } else {
                            false
                        }
                        modules[y][x] = bit xor maskBit(MASK_PATTERN, x, y)
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
    }

    private fun drawFormatBits(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        size: Int,
        mask: Int,
    ) {
        val data = (0b01 shl 3) or mask
        var remainder = data
        repeat(10) {
            remainder = (remainder shl 1) xor (((remainder ushr 9) and 1) * 0x537)
        }
        val bits = ((data shl 10) or remainder) xor 0x5412

        fun set(x: Int, y: Int, bitIndex: Int) {
            modules[y][x] = ((bits ushr bitIndex) and 1) != 0
            isFunction[y][x] = true
        }

        for (i in 0..5) set(8, i, i)
        set(8, 7, 6)
        set(8, 8, 7)
        set(7, 8, 8)
        for (i in 9..14) set(14 - i, 8, i)
        for (i in 0..7) set(size - 1 - i, 8, i)
        for (i in 8..14) set(8, size - 15 + i, i)
        modules[size - 8][8] = true
        isFunction[size - 8][8] = true
    }

    private fun drawVersionBits(
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        version: Int,
        size: Int,
    ) {
        if (version < 7) return
        var remainder = version
        repeat(12) {
            remainder = (remainder shl 1) xor (((remainder ushr 11) and 1) * 0x1F25)
        }
        val bits = (version shl 12) or remainder
        for (i in 0 until 18) {
            val bit = ((bits ushr i) and 1) != 0
            val a = size - 11 + i % 3
            val b = i / 3
            modules[b][a] = bit
            isFunction[b][a] = true
            modules[a][b] = bit
            isFunction[a][b] = true
        }
    }

    private fun appendBits(bits: MutableList<Boolean>, value: Int, width: Int) {
        for (i in width - 1 downTo 0) {
            bits += ((value ushr i) and 1) != 0
        }
    }

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean {
        return when (mask) {
            0 -> (x + y) % 2 == 0
            else -> false
        }
    }

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = gfMultiply(result[j], root)
                if (j + 1 < result.size) {
                    result[j] = result[j] xor result[j + 1]
                }
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        data.forEach { value ->
            val factor = value xor result[0]
            for (i in 0 until result.lastIndex) {
                result[i] = result[i + 1]
            }
            result[result.lastIndex] = 0
            divisor.indices.forEach { i ->
                result[i] = result[i] xor gfMultiply(divisor[i], factor)
            }
        }
        return result
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        var a = x
        var b = y
        while (b != 0) {
            if ((b and 1) != 0) z = z xor a
            a = (a shl 1) xor if ((a and 0x80) != 0) 0x11D else 0
            b = b ushr 1
        }
        return z and 0xFF
    }
}

private fun renderShiftScheduleReceiptBitmap(
    weeks: List<ShiftPrintWeek>,
    staffName: String,
    printedAtText: String,
    logoBitmap: Bitmap?,
    qrPayload: String,
): Bitmap {
    // Render at printer-native width after rotation. SUNMI D3 Mini thermal width is
    // effectively a narrow bitmap target, so the source height becomes the final
    // paper width after the 90 degree rotation below.
    val logicalWidth = 1720
    val logicalHeight = 384
    val source = Bitmap.createBitmap(logicalWidth, logicalHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(source)
    canvas.drawColor(AndroidColor.WHITE)

    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isFakeBoldText = true
    }
    val bold = Paint(ink).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isFakeBoldText = true
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
    }
    val heavyLine = Paint(line).apply {
        strokeWidth = 2.8f
    }
    val softFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(234, 236, 236)
        style = Paint.Style.FILL
    }
    val muted = Paint(ink).apply { color = AndroidColor.BLACK; alpha = 220; isFakeBoldText = true }

    fun drawCenteredText(text: String, x: Float, y: Float, paint: Paint, size: Float) {
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, x, y, paint)
    }

    fun drawMultilineCentered(lines: List<String>, rect: RectF, paint: Paint, size: Float, lineGap: Float = 3f) {
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        val fontMetrics = paint.fontMetrics
        val lineHeight = fontMetrics.descent - fontMetrics.ascent + lineGap
        val totalHeight = lineHeight * lines.size
        var baseline = rect.centerY() - totalHeight / 2f - fontMetrics.ascent
        lines.forEach { lineText ->
            canvas.drawText(lineText, rect.centerX(), baseline, paint)
            baseline += lineHeight
        }
    }

    val outer = RectF(6f, 6f, logicalWidth - 6f, logicalHeight - 6f)
    canvas.drawRect(outer, heavyLine)

    val leftW = 260f
    val rightW = 330f
    val gap = 6f
    val contentTop = 12f
    val contentBottom = logicalHeight - 12f
    val leftStart = 12f
    val leftEnd = leftStart + leftW
    val rightEnd = logicalWidth - 12f
    val rightStart = rightEnd - rightW
    val centerStart = leftEnd + gap
    val centerEnd = rightStart - gap

    canvas.drawLine(leftEnd + gap / 2f, contentTop, leftEnd + gap / 2f, contentBottom, line)
    canvas.drawLine(rightStart - gap / 2f, contentTop, rightStart - gap / 2f, contentBottom, line)

    val logoRect = RectF(leftStart + 1f, contentTop + 5f, leftEnd - 1f, contentBottom - 5f)
    val safeLogoBitmap = logoBitmap?.scaledForShiftReceiptLogo()
    if (safeLogoBitmap != null && !safeLogoBitmap.isRecycled) {
        val srcRatio = safeLogoBitmap.width.toFloat() / max(1, safeLogoBitmap.height).toFloat()
        val targetRatio = logoRect.width() / logoRect.height()
        val dst = if (srcRatio > targetRatio) {
            val h = logoRect.width() / srcRatio
            RectF(logoRect.left, logoRect.centerY() - h / 2f, logoRect.right, logoRect.centerY() + h / 2f)
        } else {
            val w = logoRect.height() * srcRatio
            RectF(logoRect.centerX() - w / 2f, logoRect.top, logoRect.centerX() + w / 2f, logoRect.bottom)
        }
        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(safeLogoBitmap, null, dst, logoPaint)
    } else {
        drawCenteredText("BARLAST", logoRect.centerX(), logoRect.centerY(), bold, 32f)
    }

    drawCenteredText("Omat työvuorot", (centerStart + centerEnd) / 2f, contentTop + 21f, bold, 29f)
    drawCenteredText(staffName, (centerStart + centerEnd) / 2f, contentTop + 48f, bold, 23f)
    drawCenteredText("Tulostettu $printedAtText", (centerStart + centerEnd) / 2f, contentTop + 68f, muted, 16.5f)

    val gridTop = contentTop + 76f
    val gridBottom = contentBottom
    val gridLeft = centerStart
    val gridRight = centerEnd
    val gridW = gridRight - gridLeft
    val gridH = gridBottom - gridTop
    val weekColW = 96f
    val dayW = (gridW - weekColW) / 7f
    val headerH = 30f
    val rowH = (gridH - headerH) / 4f

    val gridRect = RectF(gridLeft, gridTop, gridRight, gridBottom)
    canvas.drawRect(gridRect, heavyLine)
    canvas.drawRect(RectF(gridLeft, gridTop, gridRight, gridTop + headerH), softFill)

    for (i in 0..8) {
        val x = if (i == 0) gridLeft else if (i == 1) gridLeft + weekColW else gridLeft + weekColW + dayW * (i - 1)
        canvas.drawLine(x, gridTop, x, gridBottom, line)
    }
    canvas.drawLine(gridRight, gridTop, gridRight, gridBottom, line)
    canvas.drawLine(gridLeft, gridTop + headerH, gridRight, gridTop + headerH, heavyLine)
    for (r in 0..4) {
        val y = gridTop + headerH + rowH * r
        canvas.drawLine(gridLeft, y, gridRight, y, line)
    }

    PrintWeekdayLabels.forEachIndexed { index, label ->
        drawCenteredText(label, gridLeft + weekColW + dayW * index + dayW / 2f, gridTop + 22f, bold, 21f)
    }

    weeks.take(4).forEachIndexed { rowIndex, week ->
        val rowTop = gridTop + headerH + rowH * rowIndex
        val rowBottom = rowTop + rowH
        drawMultilineCentered(listOf(week.label), RectF(gridLeft, rowTop, gridLeft + weekColW, rowBottom), bold, 18.5f)
        week.days.forEachIndexed { dayIndex, day ->
            val cellLeft = gridLeft + weekColW + dayW * dayIndex
            val cell = RectF(cellLeft + 5f, rowTop + 5f, cellLeft + dayW - 5f, rowBottom - 5f)
            val texts = if (day.shifts.isEmpty()) {
                listOf("–")
            } else {
                buildList {
                    day.shifts.take(2).forEach { add(shiftPrintTimeRange(it)) }
                    if (day.shifts.size > 2) add("+${day.shifts.size - 2}")
                }
            }
            val textSize = when {
                day.shifts.isEmpty() -> 21f
                else -> 22.5f
            }
            val gapForLines = if (day.shifts.size >= 2) 6.5f else 2f
            drawMultilineCentered(texts, cell, if (day.shifts.isEmpty()) bold else ink, textSize, lineGap = gapForLines)
        }
    }

    val qrLabelRect = RectF(rightStart + 8f, contentTop + 18f, rightEnd - 8f, contentTop + 66f)
    drawMultilineCentered(listOf("Skannaa vuorot", "puhelimeen"), qrLabelRect, bold, 19.5f)
    val qrSize = 232f
    val qrRect = RectF(
        rightStart + (rightW - qrSize) / 2f,
        contentTop + 72f,
        rightStart + (rightW + qrSize) / 2f,
        contentTop + 72f + qrSize,
    )
    drawQrCode(canvas, qrPayload, qrRect)
    drawMultilineCentered(
        listOf(ShiftReceiptPoweredByText),
        RectF(rightStart + 10f, qrRect.bottom + 9f, rightEnd - 10f, qrRect.bottom + 39f),
        muted,
        14f,
    )

    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
