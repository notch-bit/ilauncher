package com.example.ioslauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import java.io.File
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

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

/* ---------- Data ---------- */

data class AppInfo(val label: String, val pkg: String, val icon: ImageBitmap)

/**
 * One UI draws every icon edge-to-edge inside the same squircle. Adaptive icons normally come
 * pre-masked by the device, so we draw their two layers ourselves to fill the whole square.
 */
private fun Drawable.toUniformBitmap(px: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= 26 && this is AdaptiveIconDrawable) {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pad = (px * 0.25f).toInt() // layers are 108dp, visible window is 72dp
        background?.apply { setBounds(-pad, -pad, px + pad, px + pad); draw(canvas) }
        foreground?.apply { setBounds(-pad, -pad, px + pad, px + pad); draw(canvas) }
        return bmp
    }
    return toBitmap(px, px)
}

/** Bakes the squircle mask into the bitmap once, so scrolling never has to clip with a custom path. */
private fun Bitmap.squircled(): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawPath(squirclePath(width.toFloat()), paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    c.drawBitmap(this, 0f, 0f, paint)
    return out
}

private fun loadApps(ctx: Context): List<AppInfo> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .mapNotNull {
            try {
                AppInfo(
                    label = it.loadLabel(pm).toString(),
                    pkg = it.activityInfo.packageName,
                    icon = it.loadIcon(pm).toUniformBitmap(160).squircled().asImageBitmap()
                )
            } catch (e: Throwable) {
                null // one broken icon must never crash the launcher
            }
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

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

private const val COLUMNS = 4
private const val ROWS = 6

@Composable
fun LauncherScreen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps(ctx) } }

    val dock = remember(apps) { if (apps.isEmpty()) emptyList() else defaultDock(ctx, apps) }
    val pages = remember(apps, dock) {
        apps.filter { a -> dock.none { it.pkg == a.pkg } }.chunked(COLUMNS * ROWS)
    }
    var showSearch by remember { mutableStateOf(false) }
    BackHandler(enabled = showSearch) { showSearch = false }
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
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Page 0 = Today widgets, pages 1.. = app grids. Starts on the first app page.
            val pagerState = rememberPagerState(initialPage = 1, pageCount = { maxOf(pages.size, 1) + 1 })

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                if (page == 0) TodayPage(onSearch = { showSearch = true })
                else IconGrid(pages.getOrNull(page - 1).orEmpty()) { launch(ctx, it) }
            }

            PageDots(count = pagerState.pageCount, current = pagerState.currentPage)
            Spacer(Modifier.height(12.dp))
            Dock(dock) { launch(ctx, it) }
            Spacer(Modifier.height(8.dp))
        }

        crash?.let { trace ->
            Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
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

        AnimatedVisibility(showSearch, enter = fadeIn(), exit = fadeOut()) {
            SearchOverlay(apps, onClose = { showSearch = false }) {
                showSearch = false
                launch(ctx, it)
            }
        }
    }
}

/* ---------- Home grid ---------- */

@Composable
private fun IconGrid(items: List<AppInfo>, onClick: (AppInfo) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        items.chunked(COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AppIcon(app, showLabel = true, onClick = onClick)
                    }
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppIcon(app: AppInfo, showLabel: Boolean, onClick: (AppInfo) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "press")

    Column(
        Modifier
            .scale(scale)
            .clickable(interactionSource = source, indication = null) { onClick(app) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier.size(62.dp) // already squircle-shaped
        )
        if (showLabel) {
            Spacer(Modifier.height(6.dp))
            Text(
                app.label,
                modifier = Modifier.width(76.dp),
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
private fun PageDots(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
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
}

/* ---------- Dock (glass) ---------- */

@Composable
private fun Dock(apps: List<AppInfo>, onClick: (AppInfo) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(34.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        apps.forEach { AppIcon(it, showLabel = false, onClick = onClick) }
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
