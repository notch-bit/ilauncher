package com.example.ioslauncher

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CardShape = RoundedCornerShape(28.dp)
private val IosRed = Color(0xFFFF453A)
private val IosGreen = Color(0xFF30D158)
private val OneUiBlue = Color(0xFF3E91FF)

/** Left-most "Today" page: iOS-style stacked widgets + One UI-style ring widgets, all glass. */
@Composable
fun TodayPage(onSearch: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Search pill
        Box(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(50))
                .clickable(onClick = onSearch)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) { Text("Search", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp) }

        ClockWidget()

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BatteryWidget(Modifier.weight(1f).aspectRatio(1f))
            StorageWidget(Modifier.weight(1f).aspectRatio(1f))
        }

        CalendarWidget()
        Spacer(Modifier.height(8.dp))
    }
}

/* ---------- Clock + date (iOS style) ---------- */

@Composable
private fun ClockWidget() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L * (60 - now.second))
        }
    }
    Column(
        Modifier.fillMaxWidth().glass(CardShape).padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        Text(
            now.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())).uppercase(),
            color = IosRed, fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
        Text(
            now.format(DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())),
            color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light
        )
        Text(
            now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())),
            color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp
        )
    }
}

/* ---------- Ring widgets (One UI "Device care" style) ---------- */

@Composable
private fun RingWidget(
    modifier: Modifier,
    title: String,
    percent: Int,
    center: String,
    caption: String,
    color: Color
) {
    Column(
        modifier.glass(CardShape).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 8.dp.toPx()
                val arc = Size(size.width - sw, size.height - sw)
                val tl = Offset(sw / 2, sw / 2)
                drawArc(Color.White.copy(alpha = 0.22f), 0f, 360f, false, tl, arc, style = Stroke(sw))
                drawArc(
                    color, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, tl, arc,
                    style = Stroke(sw, cap = StrokeCap.Round)
                )
            }
            Text(center, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(caption, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BatteryWidget(modifier: Modifier) {
    val ctx = LocalContext.current
    var level by remember { mutableIntStateOf(0) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (l >= 0) level = l * 100 / s
            val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            delay(30_000)
        }
    }
    RingWidget(
        modifier, "Battery", level, "$level%",
        if (charging) "Charging" else "On battery",
        if (level <= 20 && !charging) IosRed else IosGreen
    )
}

@Composable
private fun StorageWidget(modifier: Modifier) {
    val (usedPct, freeGb) = remember {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.blockCountLong * st.blockSizeLong
        val free = st.availableBlocksLong * st.blockSizeLong
        (((total - free) * 100) / total).toInt() to free / 1_000_000_000L
    }
    RingWidget(modifier, "Storage", usedPct, "$usedPct%", "$freeGb GB free", OneUiBlue)
}

/* ---------- Month calendar ---------- */

@Composable
private fun CalendarWidget() {
    val today = remember { LocalDate.now() }
    val first = today.withDayOfMonth(1)
    val offset = first.dayOfWeek.value % 7 // Sunday-first
    val days = today.lengthOfMonth()
    val rows = (offset + days + 6) / 7

    Column(Modifier.fillMaxWidth().glass(CardShape).padding(18.dp)) {
        Text(
            today.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())).uppercase(),
            color = IosRed, fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                Text(
                    it, Modifier.weight(1f), textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
                )
            }
        }
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                for (c in 0 until 7) {
                    val day = r * 7 + c - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (day in 1..days) {
                            val isToday = day == today.dayOfMonth
                            Box(
                                Modifier
                                    .fillMaxSize(0.82f)
                                    .clip(CircleShape)
                                    .background(if (isToday) IosRed else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$day", color = Color.White, fontSize = 13.sp,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
