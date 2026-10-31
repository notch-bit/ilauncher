package com.example.ioslauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText(Log.getStackTraceString(e)) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
        enableEdgeToEdge()
        setContent { LauncherScreen() }
    }
}

/* ---------- Helpers ---------- */

private fun defaultDock(ctx: Context, apps: List<AppInfo>): List<AppInfo> {
    val pm = ctx.packageManager
    val intents = listOf(
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    )
    val picked = intents.mapNotNull { i ->
        pm.queryIntentActivities(i, 0).firstNotNullOfOrNull { r ->
            apps.firstOrNull { it.pkg == r.activityInfo.packageName }
        }
    }.distinctBy { it.pkg }.toMutableList()
    for (a in apps) { if (picked.size >= 4) break; if (picked.none { it.pkg == a.pkg }) picked += a }
    return picked.take(4)
}

private fun launch(ctx: Context, app: AppInfo) {
    try {
        ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
        }
    } catch (e: Throwable) {
        // app was removed or can't be opened
    }
}

/* ---------- Screen ---------- */

@Composable
fun LauncherScreen() {
    val ctx = LocalContext.current
    val settings = remember { LauncherSettings(ctx) }

    var raw by remember { mutableStateOf<List<RawApp>>(emptyList()) }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(Unit) { raw = withContext(Dispatchers.Default) { loadApps(ctx) } }

    val shape = settings.shape
    val pack = settings.packPkg
    LaunchedEffect(raw, shape, pack) {
        apps = withContext(Dispatchers.Default) { styleApps(ctx, raw, shape, pack) }
    }

    val columns = settings.columns
    val rows = settings.rows
    val cap = columns * rows

    // Where every app lives (pkg -> slot), including what the user has dragged around.
    val layout = remember { HomeLayout(ctx) }
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    val resolved = remember(apps, cap, layout.slots, layout.savedCap) {
        if (apps.isEmpty()) emptyMap() else layout.resolve(apps, cap, defaultDock(ctx, apps))
    }
    val dock = remember(resolved, byPkg) {
        List(DOCK_SLOTS) { i -> resolved.entries.firstOrNull { it.value == dockSlot(i) }?.key?.let { byPkg[it] } }
    }

    var showSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Drag & drop state (long-press an icon, then move it anywhere).
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var dragPkg by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var gridRect by remember { mutableStateOf(Rect.Zero) }
    var dockRect by remember { mutableStateOf(Rect.Zero) }

    val hasToday = settings.showToday
    val gridPages = if (resolved.isEmpty()) 1 else maxOf(resolved.values.max() / cap + 1, 1)
    // While dragging, one extra empty page is offered at the end so an icon can start a new page.
    val appPageCount = gridPages + (if (dragPkg != null) 1 else 0)
    val pageTotal = appPageCount + (if (hasToday) 1 else 0)
    val pagerState = key(hasToday) {
        rememberPagerState(initialPage = if (hasToday) 1 else 0, pageCount = { pageTotal })
    }

    // Which slot is under a point on screen: a dock slot (negative), a home cell (>= 0) or null.
    val slotAt = { pos: Offset ->
        val padH = with(density) { 12.dp.toPx() }
        val padV = with(density) { 16.dp.toPx() }
        val dockHit = Rect(
            dockRect.left, dockRect.top - with(density) { 24.dp.toPx() },
            dockRect.right, dockRect.bottom + with(density) { 24.dp.toPx() }
        )
        val page = pagerState.currentPage - (if (hasToday) 1 else 0)
        if (dockRect.width > 0f && dockHit.contains(pos)) {
            val i = ((pos.x - dockRect.left) / (dockRect.width / DOCK_SLOTS)).toInt().coerceIn(0, DOCK_SLOTS - 1)
            dockSlot(i)
        } else if (page >= 0 && gridRect.width > 0f) {
            val inner = Rect(gridRect.left + padH, gridRect.top + padV, gridRect.right - padH, gridRect.bottom - padV)
            if (inner.width <= 0f || inner.height <= 0f || !inner.contains(pos)) null
            else {
                val c = ((pos.x - inner.left) / (inner.width / columns)).toInt().coerceIn(0, columns - 1)
                val r = ((pos.y - inner.top) / (inner.height / rows)).toInt().coerceIn(0, rows - 1)
                page * cap + r * columns + c
            }
        } else null
    }
    val currentSlotAt by rememberUpdatedState(slotAt)
    val target by remember { derivedStateOf { if (dragPkg == null) null else currentSlotAt(dragPos) } }

    val appAt: (Offset) -> String? = { pos ->
        currentSlotAt(pos)?.let { s -> resolved.entries.firstOrNull { it.value == s }?.key }
    }
    val currentAppAt by rememberUpdatedState(appAt)

    val drop: () -> Unit = {
        val pkg = dragPkg
        val t = currentSlotAt(dragPos)
        dragPkg = null
        if (pkg != null && t != null) layout.commit(moveToSlot(resolved, pkg, t), cap)
    }
    val currentDrop by rememberUpdatedState(drop)

    // Holding an icon near the left/right edge flips to the neighbouring page.
    val edge by remember(density) {
        derivedStateOf {
            val edgePx = with(density) { 44.dp.toPx() }
            when {
                dragPkg == null || gridRect.width <= 0f -> 0
                dragPos.x < gridRect.left + edgePx -> -1
                dragPos.x > gridRect.right - edgePx -> 1
                else -> 0
            }
        }
    }
    LaunchedEffect(edge, pagerState.currentPage) {
        if (edge != 0) {
            delay(650)
            val t = pagerState.currentPage + edge
            if (t in 0 until pagerState.pageCount) pagerState.animateScrollToPage(t)
        }
    }

    val overlayOpen by rememberUpdatedState(showSearch || showSettings)
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showSearch && !showSettings) { showSearch = false }

    var crash by remember {
        mutableStateOf(try { File(ctx.filesDir, "crash.txt").takeIf { it.exists() }?.readText() } catch (e: Throwable) { null })
    }

    // Real frosted-glass blur of the wallpaper behind the search screen (Android 12+, device permitting).
    LaunchedEffect(showSearch) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                (ctx as? Activity)?.window?.let { w ->
                    val lp = w.attributes
                    if (showSearch) {
                        lp.blurBehindRadius = 70
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    } else {
                        lp.blurBehindRadius = 0
                        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    }
                    w.attributes = lp
                }
            } catch (e: Throwable) { /* blur not supported on this device */ }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 180f) showSearch = true },
                    onVerticalDrag = { _, dy -> total += dy }
                )
            }
            // Declared after the swipe-down gesture so it wins once a long press has started.
            // Long-press an icon to pick it up; long-press empty space to open settings.
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { off ->
                        if (!overlayOpen) {
                            val pkg = currentAppAt(off)
                            if (pkg != null) {
                                dragPkg = pkg
                                dragPos = off
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else {
                                showSettings = true
                            }
                        }
                    },
                    onDrag = { change, amount ->
                        if (dragPkg != null) {
                            change.consume()
                            dragPos += amount
                        }
                    },
                    onDragEnd = { currentDrop() },
                    onDragCancel = { dragPkg = null }
                )
            }
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Today page (widgets) is page 0 when enabled; app pages follow.
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = dragPkg == null,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { gridRect = it.boundsInRoot() }
            ) { page ->
                if (hasToday && page == 0) {
                    TodayPage(settings.widgets, onSearch = { showSearch = true })
                } else {
                    val appPage = page - (if (hasToday) 1 else 0)
                    val cells = remember(resolved, byPkg, appPage, cap) {
                        resolved.entries
                            .filter { it.value >= 0 && it.value / cap == appPage }
                            .mapNotNull { e -> byPkg[e.key]?.let { (e.value % cap) to it } }
                            .toMap()
                    }
                    val highlight = target?.takeIf { it >= 0 && it / cap == appPage }?.let { it % cap }
                    IconGrid(
                        cells = cells,
                        columns = columns, rows = rows,
                        iconSize = settings.iconSize, showLabels = settings.showLabels,
                        hiddenPkg = dragPkg, highlightCell = highlight
                    ) { launch(ctx, it) }
                }
            }

            PageDots(
                count = pagerState.pageCount,
                current = pagerState.currentPage,
                onSettings = { showSettings = true }
            )
            Spacer(Modifier.height(12.dp))
            Dock(
                dock, settings.iconSize,
                hiddenPkg = dragPkg,
                highlightSlot = target?.takeIf { it < 0 },
                onBounds = { dockRect = it }
            ) { launch(ctx, it) }
            Spacer(Modifier.height(8.dp))
        }

        // The icon being dragged, following the finger.
        val dragged = dragPkg?.let { byPkg[it] }
        if (dragged != null) {
            val sizePx = with(density) { settings.iconSize.dp.toPx() }
            Image(
                bitmap = dragged.icon,
                contentDescription = null,
                modifier = Modifier
                    .offset { IntOffset((dragPos.x - sizePx / 2).roundToInt(), (dragPos.y - sizePx / 2).roundToInt()) }
                    .size(settings.iconSize.dp)
                    .scale(1.2f)
                    .alpha(0.95f)
            )
        }

        AnimatedVisibility(showSearch, enter = fadeIn(), exit = fadeOut()) {
            SearchOverlay(apps, onClose = { showSearch = false }) {
                showSearch = false
                launch(ctx, it)
            }
        }

        AnimatedVisibility(
            showSettings,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            SettingsScreen(
                settings,
                onClose = { showSettings = false },
                onResetPositions = { layout.reset() }
            )
        }

        crash?.let { trace ->
            Column(
                Modifier.fillMaxSize().background(Color.Black)
                    .statusBarsPadding().navigationBarsPadding().padding(16.dp)
            ) {
                Text("iLauncher crashed last time. Please screenshot this and send it:", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    trace.take(3000), color = Color(0xFFFF8A80), fontSize = 10.sp,
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                )
                Text(
                    "Dismiss", color = Color(0xFF0A84FF), fontSize = 16.sp,
                    modifier = Modifier.clickable {
                        try { File(ctx.filesDir, "crash.txt").delete() } catch (e: Throwable) {}
                        crash = null
                    }.padding(12.dp)
                )
            }
        }
    }
}

/* ---------- Home grid ---------- */

@Composable
private fun IconGrid(
    cells: Map<Int, AppInfo>, columns: Int, rows: Int,
    iconSize: Int, showLabels: Boolean,
    hiddenPkg: String?, highlightCell: Int?,
    onClick: (AppInfo) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp)) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (c in 0 until columns) {
                    val index = r * columns + c
                    val app = cells[index]
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (highlightCell == index) Color.White.copy(alpha = 0.18f) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        if (app != null) AppIcon(app, iconSize, showLabels, app.pkg == hiddenPkg, onClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: AppInfo, size: Int, showLabel: Boolean, hidden: Boolean, onClick: (AppInfo) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "press")

    Column(
        Modifier
            .alpha(if (hidden) 0f else 1f)
            .scale(scale)
            .clickable(interactionSource = source, indication = null) { onClick(app) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(bitmap = app.icon, contentDescription = app.label, modifier = Modifier.size(size.dp))
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(
                app.label,
                modifier = Modifier.width((size + 16).dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 6f)
                )
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, onSettings: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.align(Alignment.Center)) {
            repeat(count) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (i == current) 0.95f else 0.4f))
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .size(30.dp)
                .glass(CircleShape)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center
        ) { Text("⚙", color = Color.White, fontSize = 15.sp) }
    }
}

/* ---------- Dock (glass) ---------- */

@Composable
private fun Dock(
    apps: List<AppInfo?>, iconSize: Int,
    hiddenPkg: String?, highlightSlot: Int?,
    onBounds: (Rect) -> Unit,
    onClick: (AppInfo) -> Unit
) {
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(34.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
    ) {
        apps.forEachIndexed { i, app ->
            Box(
                Modifier
                    .weight(1f)
                    .height(iconSize.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (highlightSlot == dockSlot(i)) Color.White.copy(alpha = 0.18f) else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (app != null) AppIcon(app, iconSize, false, app.pkg == hiddenPkg, onClick)
            }
        }
    }
}

/* ---------- Search ---------- */

@Composable
private fun SearchOverlay(apps: List<AppInfo>, onClose: () -> Unit, onOpen: (AppInfo) -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val results = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .statusBarsPadding()
            .imePadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .glass(RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 13.dp)
            ) {
                if (query.isEmpty()) Text("Search", color = Color.White.copy(alpha = 0.65f), fontSize = 16.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("Cancel", color = Color(0xFF0A84FF), fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onClose))
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            Modifier.glass(RoundedCornerShape(24.dp)).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(results, key = { it.pkg }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpen(app) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(app.icon, app.label, Modifier.size(42.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(app.label, color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
}
