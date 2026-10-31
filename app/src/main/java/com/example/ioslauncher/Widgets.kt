package com.example.ioslauncher

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.util.Locale

/* ====================== Registry ====================== */

enum class WidgetStyle(val label: String) { APPLE("Apple"), ONEUI("One UI"), NOTHING("Nothing") }

class WidgetDef(
    val id: String,
    val name: String,
    val style: WidgetStyle,
    val half: Boolean,                       // half = square card, two sit side by side
    val content: @Composable (Modifier) -> Unit
)

val DEFAULT_WIDGETS = listOf("apple_clock", "oneui_battery", "oneui_storage", "apple_calendar")

val AllWidgets: List<WidgetDef> = listOf(
    WidgetDef("apple_clock", "Clock", WidgetStyle.APPLE, false) { m -> AppleClock(m) },
    WidgetDef("apple_calendar", "Calendar", WidgetStyle.APPLE, false) { m -> AppleCalendar(m) },
    WidgetDef("apple_battery", "Batteries", WidgetStyle.APPLE, true) { m -> AppleBattery(m) },

    WidgetDef("oneui_clock", "Clock & date", WidgetStyle.ONEUI, false) { m -> OneUiClock(m) },
    WidgetDef("oneui_week", "Week", WidgetStyle.ONEUI, false) { m -> OneUiWeek(m) },
    WidgetDef("oneui_battery", "Battery", WidgetStyle.ONEUI, true) { m -> OneUiBattery(m) },
    WidgetDef("oneui_storage", "Storage", WidgetStyle.ONEUI, true) { m -> OneUiStorage(m) },

    WidgetDef("nothing_clock", "Dot clock", WidgetStyle.NOTHING, false) { m -> NothingClock(m) },
    WidgetDef("nothing_battery", "Dot battery", WidgetStyle.NOTHING, true) { m -> NothingBattery(m) },
    WidgetDef("nothing_date", "Dot date", WidgetStyle.NOTHING, true) { m -> NothingDate(m) }
)

/* ====================== Today page ====================== */

@Composable
fun TodayPage(enabled: List<String>, onSearch: () -> Unit) {
    val defs = enabled.mapNotNull { id -> AllWidgets.firstOrNull { it.id == id } }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(50))
                .clickable(onClick = onSearch)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) { Text("Search", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp) }

        var i = 0
        while (i < defs.size) {
            val d = defs[i]
            if (!d.half) {
                d.content(Modifier.fillMaxWidth())
                i += 1
            } else {
                val next = defs.getOrNull(i + 1)?.takeIf { it.half }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    d.content(Modifier.weight(1f).aspectRatio(1f))
                    if (next != null) next.content(Modifier.weight(1f).aspectRatio(1f))
                    else Spacer(Modifier.weight(1f))
                }
                i += if (next != null) 2 else 1
            }
        }

        if (defs.isEmpty()) {
            Text(
                "No widgets yet. Open Launcher settings (⚙) → Widgets to add some.",
                color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ====================== Shared helpers ====================== */

private val CardShape = RoundedCornerShape(28.dp)
private val IosRed = Color(0xFFFF453A)
private val IosGreen = Color(0xFF30D158)
private val OneUiBlue = Color(0xFF3E91FF)
private val NothingRed = Color(0xFFD71921)

@Composable
private fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L * (60 - now.second))
        }
    }
    return now
}

@Composable
private fun rememberBattery(): Pair<Int, Boolean> {
    val ctx = LocalContext.current
    var level by remember { mutableIntStateOf(0) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val i = try { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (e: Throwable) { null }
            val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (l >= 0 && s > 0) level = l * 100 / s
            val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            delay(30_000)
        }
    }
    return level to charging
}

@Composable
private fun rememberStorage(): Pair<Int, Long> = remember {
    try {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.blockCountLong * st.blockSizeLong
        val free = st.availableBlocksLong * st.blockSizeLong
        (if (total > 0) (((total - free) * 100) / total).toInt() else 0) to free / 1_000_000_000L
    } catch (e: Throwable) {
        0 to 0L
    }
}

@Composable
private fun Ring(diameter: Dp, stroke: Dp, percent: Int, color: Color) {
    Canvas(Modifier.size(diameter)) {
        val sw = stroke.toPx()
        val arc = Size(size.width - sw, size.height - sw)
        val tl = Offset(sw / 2, sw / 2)
        drawArc(Color.White.copy(alpha = 0.22f), 0f, 360f, false, tl, arc, style = Stroke(sw))
        drawArc(
            color, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, tl, arc,
            style = Stroke(sw, cap = StrokeCap.Round)
        )
    }
}

/* ====================== Apple style ====================== */

@Composable
private fun AppleClock(modifier: Modifier) {
    val now = rememberNow()
    Column(modifier.glass(CardShape).padding(horizontal = 22.dp, vertical = 18.dp)) {
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

@Composable
private fun AppleCalendar(modifier: Modifier) {
    val today = remember { LocalDate.now() }
    val first = today.withDayOfMonth(1)
    val offset = first.dayOfWeek.value % 7 // Sunday-first
    val days = today.lengthOfMonth()
    val rows = (offset + days + 6) / 7

    Column(modifier.glass(CardShape).padding(18.dp)) {
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

@Composable
private fun AppleBattery(modifier: Modifier) {
    val (level, charging) = rememberBattery()
    Column(
        modifier.glass(CardShape).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Batteries", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box(contentAlignment = Alignment.Center) {
            Ring(68.dp, 7.dp, level, if (level <= 20 && !charging) IosRed else IosGreen)
            Text("$level%", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            if (charging) "iPhone ⚡" else "iPhone",
            color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp
        )
    }
}

/* ====================== One UI style ====================== */

@Composable
private fun OneUiClock(modifier: Modifier) {
    val now = rememberNow()
    Row(
        modifier.glass(RoundedCornerShape(32.dp)).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            now.format(DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())),
            color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                now.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            Text(
                now.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())),
                color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun OneUiWeek(modifier: Modifier) {
    val today = remember { LocalDate.now() }
    val start = today.minusDays((today.dayOfWeek.value % 7).toLong()) // Sunday
    Column(modifier.glass(RoundedCornerShape(32.dp)).padding(horizontal = 14.dp, vertical = 14.dp)) {
        Text(
            "This week", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
        )
        Row(Modifier.fillMaxWidth()) {
            val letters = listOf("S", "M", "T", "W", "T", "F", "S")
            for (n in 0 until 7) {
                val day = start.plusDays(n.toLong())
                val isToday = day == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(letters[n], color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isToday) OneUiBlue else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${day.dayOfMonth}", color = Color.White, fontSize = 14.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RingCard(
    modifier: Modifier, title: String, percent: Int, center: String, caption: String, color: Color
) {
    Column(
        modifier.glass(CardShape).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box(contentAlignment = Alignment.Center) {
            Ring(72.dp, 8.dp, percent, color)
            Text(center, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(caption, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun OneUiBattery(modifier: Modifier) {
    val (level, charging) = rememberBattery()
    RingCard(
        modifier, "Battery", level, "$level%",
        if (charging) "Charging" else "On battery",
        if (level <= 20 && !charging) IosRed else IosGreen
    )
}

@Composable
private fun OneUiStorage(modifier: Modifier) {
    val (usedPct, freeGb) = rememberStorage()
    RingCard(modifier, "Storage", usedPct, "$usedPct%", "$freeGb GB free", OneUiBlue)
}

/* ====================== Nothing style (dot matrix, monochrome, red accent) ====================== */

private fun Modifier.nothingCard(shape: Shape = RoundedCornerShape(26.dp)): Modifier =
    this.clip(shape)
        .background(Color(0xD9000000))
        .border(1.dp, Color.White.copy(alpha = 0.22f), shape)

@Composable
private fun NothingCard(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.nothingCard()) {
        content()
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(NothingRed)
        )
    }
}

/** 5x7 dot-matrix glyphs ("#" = lit dot). */
private val DotFont: Map<Char, List<String>> = mapOf(
    '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
    '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
    '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
    '3' to listOf(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."),
    '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
    '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
    '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
    '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
    '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
    '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
    ':' to listOf(".", ".", "#", ".", "#", ".", "."),
    '%' to listOf("##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##")
)

@Composable
private fun DotText(text: String, dot: Dp, color: Color) {
    val glyphs = text.mapNotNull { DotFont[it] }
    if (glyphs.isEmpty()) return
    val cols = glyphs.sumOf { it[0].length } + (glyphs.size - 1)
    Canvas(Modifier.size(dot * cols, dot * 7)) {
        val d = dot.toPx()
        val r = d * 0.38f
        var x0 = 0
        for (g in glyphs) {
            for (row in 0 until 7) {
                for (col in g[row].indices) {
                    if (g[row][col] == '#') {
                        drawCircle(color, r, Offset((x0 + col + 0.5f) * d, (row + 0.5f) * d))
                    }
                }
            }
            x0 += g[0].length + 1
        }
    }
}

@Composable
private fun NothingClock(modifier: Modifier) {
    val now = rememberNow()
    NothingCard(modifier) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            DotText(now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())), 9.dp, Color.White)
            Spacer(Modifier.height(12.dp))
            Text(
                now.format(DateTimeFormatter.ofPattern("EEE · d MMM", Locale.getDefault())).uppercase(),
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun NothingBattery(modifier: Modifier) {
    val (level, _) = rememberBattery()
    NothingCard(modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                "BATTERY", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
            )
            Canvas(Modifier.fillMaxWidth().aspectRatio(2f)) {
                val cols = 10
                val rows = 5
                val cell = size.width / cols
                val lit = level / 2 // 50 dots = 100%
                for (c in 0 until cols) {
                    for (r in 0 until rows) {
                        val index = c * rows + (rows - 1 - r)
                        drawCircle(
                            if (index < lit) Color.White else Color.White.copy(alpha = 0.18f),
                            cell * 0.3f,
                            Offset((c + 0.5f) * cell, (r + 0.5f) * cell)
                        )
                    }
                }
            }
            DotText("$level%", 4.dp, Color.White)
        }
    }
}

@Composable
private fun NothingDate(modifier: Modifier) {
    val now = rememberNow()
    NothingCard(modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                now.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())).uppercase(),
                color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
            )
            DotText(now.format(DateTimeFormatter.ofPattern("dd", Locale.getDefault())), 7.dp, Color.White)
            Text(
                now.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())).uppercase(),
                color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
            )
        }
    }
}
