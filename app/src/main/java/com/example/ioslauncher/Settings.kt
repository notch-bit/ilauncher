package com.example.ioslauncher

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** All launcher options, saved to SharedPreferences and observable by Compose. */
class LauncherSettings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    var iconSize by mutableIntStateOf(prefs.getInt("iconSize", 62))
        private set
    var shape by mutableStateOf(IconShapeType.from(prefs.getString("shape", null)))
        private set
    var packPkg by mutableStateOf(prefs.getString("pack", null))
        private set
    var columns by mutableIntStateOf(prefs.getInt("columns", 4))
        private set
    var rows by mutableIntStateOf(prefs.getInt("rows", 6))
        private set
    var showLabels by mutableStateOf(prefs.getBoolean("labels", true))
        private set
    var showToday by mutableStateOf(prefs.getBoolean("today", true))
        private set
    var widgets by mutableStateOf(readWidgets())
        private set

    private fun readWidgets(): List<String> {
        val saved = prefs.getString("widgets", null) ?: return DEFAULT_WIDGETS
        return saved.split(",").filter { id -> id.isNotBlank() && AllWidgets.any { it.id == id } }
    }

    fun updateIconSize(v: Int) { iconSize = v; prefs.edit().putInt("iconSize", v).apply() }
    fun updateShape(v: IconShapeType) { shape = v; prefs.edit().putString("shape", v.name).apply() }
    fun updatePack(pkg: String?) { packPkg = pkg; prefs.edit().putString("pack", pkg).apply() }
    fun updateColumns(v: Int) { columns = v; prefs.edit().putInt("columns", v).apply() }
    fun updateRows(v: Int) { rows = v; prefs.edit().putInt("rows", v).apply() }
    fun updateShowLabels(v: Boolean) { showLabels = v; prefs.edit().putBoolean("labels", v).apply() }
    fun updateShowToday(v: Boolean) { showToday = v; prefs.edit().putBoolean("today", v).apply() }

    fun toggleWidget(id: String) {
        widgets = if (id in widgets) widgets - id else widgets + id
        prefs.edit().putString("widgets", widgets.joinToString(",")).apply()
    }

    fun resetLayout() {
        updateColumns(4); updateRows(6); updateIconSize(62)
        updateShape(IconShapeType.SQUIRCLE); updateShowLabels(true); updateShowToday(true)
    }
}

private val Muted = Color.White.copy(alpha = 0.6f)
private val Green = Color(0xFF30D158)

@Composable
fun SettingsScreen(settings: LauncherSettings, onClose: () -> Unit, onResetPositions: () -> Unit = {}) {
    val ctx = LocalContext.current
    var packs by remember { mutableStateOf<List<IconPack>>(emptyList()) }
    LaunchedEffect(Unit) { packs = withContext(Dispatchers.Default) { loadPacks(ctx) } }
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "alpha 1.0.0" }
        catch (e: Throwable) { "alpha 1.0.0" }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2101014))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Launcher settings", color = Color.White, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Text(
                "Done", color = Color(0xFF0A84FF), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onClose).padding(8.dp)
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            /* ---------- Icons ---------- */
            Section("Icons") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Icon size", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("${settings.iconSize} dp", color = Muted, fontSize = 14.sp)
                }
                Slider(
                    value = settings.iconSize.toFloat(),
                    onValueChange = { settings.updateIconSize(it.roundToInt()) },
                    valueRange = 48f..76f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    )
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(settings.iconSize.dp)) {
                        drawPath(
                            shapePath(settings.shape, size.minDimension).asComposePath(),
                            Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                Text("Icon shape", color = Color.White, fontSize = 15.sp)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconShapeType.values().forEach { t ->
                        ShapeChip(t, selected = t == settings.shape) { settings.updateShape(t) }
                    }
                }
                if (settings.packPkg != null) {
                    Text(
                        "An icon pack is active. Shapes apply to apps the pack doesn't theme.",
                        color = Muted, fontSize = 12.sp
                    )
                }

                Text("Icon pack", color = Color.White, fontSize = 15.sp)
                PackRow("System icons (default)", settings.packPkg == null) { settings.updatePack(null) }
                packs.forEach { p ->
                    PackRow(p.label, settings.packPkg == p.pkg) { settings.updatePack(p.pkg) }
                }
                if (packs.isEmpty()) {
                    Text(
                        "No icon packs found. Install an ADW / Nova compatible icon pack, then reopen settings.",
                        color = Muted, fontSize = 12.sp
                    )
                }
            }

            /* ---------- Layout ---------- */
            Section("Home screen layout") {
                Stepper("Columns", settings.columns, 3, 6) { settings.updateColumns(it) }
                Stepper("Rows", settings.rows, 4, 7) { settings.updateRows(it) }
                SwitchRow("Show app names", settings.showLabels) { settings.updateShowLabels(it) }
                SwitchRow("Show Today page (widgets)", settings.showToday) { settings.updateShowToday(it) }
                Text(
                    "Big icons with many rows can overlap on small screens. Lower the size or rows if that happens.",
                    color = Muted, fontSize = 12.sp
                )
                Text(
                    "Long-press an icon to pick it up, then drag it anywhere. Hold it at the screen edge to " +
                        "change page, or drop it on a page's empty space to move it there.",
                    color = Muted, fontSize = 12.sp
                )
                Text(
                    "Reset layout & icon size",
                    color = Color(0xFF0A84FF), fontSize = 15.sp,
                    modifier = Modifier.clickable { settings.resetLayout() }.padding(vertical = 4.dp)
                )
                Text(
                    "Reset icon positions",
                    color = Color(0xFF0A84FF), fontSize = 15.sp,
                    modifier = Modifier.clickable { onResetPositions() }.padding(vertical = 4.dp)
                )
            }

            /* ---------- Widgets ---------- */
            Section("Widgets") {
                Text(
                    "Choose which widgets appear on the Today page (swipe right from your first app page). " +
                        "They show in the order you switch them on.",
                    color = Muted, fontSize = 12.sp
                )
                WidgetStyle.values().forEach { style ->
                    Text(
                        style.label.uppercase(), color = Muted, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)
                    )
                    AllWidgets.filter { it.style == style }.forEach { w ->
                        SwitchRow(
                            w.name + if (w.half) "  (small)" else "",
                            w.id in settings.widgets
                        ) { settings.toggleWidget(w.id) }
                    }
                }
            }

            /* ---------- About ---------- */
            Section("About") {
                Text("iLauncher", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                AboutRow("Version", version)
                AboutRow("Made by", "Jay")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ---------- Building blocks ---------- */

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) { content() }
    }
}

@Composable
private fun ShapeChip(type: IconShapeType, selected: Boolean, onClick: () -> Unit) {
    val outline = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .clip(outline)
            .background(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.15f), outline)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(40.dp)) {
            drawPath(shapePath(type, size.minDimension).asComposePath(), Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(type.label, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun PackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(if (selected) "●" else "○", color = if (selected) Green else Muted, fontSize = 16.sp)
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        StepButton("−") { if (value > min) onChange(value - 1) }
        Text(
            "$value", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.width(38.dp)
        )
        StepButton("+") { if (value < max) onChange(value + 1) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 18.sp) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.White)
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
