package com.example.ioslauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Xml
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser

/** An app with its original, unmasked icon. */
data class RawApp(val label: String, val pkg: String, val component: String, val raw: Bitmap)

/** An app ready to display (icon already shaped or taken from an icon pack). */
data class AppInfo(val label: String, val pkg: String, val icon: ImageBitmap)

data class IconPack(val pkg: String, val label: String)

private const val ICON_PX = 160

/** Draws adaptive icon layers edge to edge so every icon can be masked with the same shape. */
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

/** Bakes a shape mask into the bitmap once, so scrolling never clips with a custom path. */
fun Bitmap.masked(shape: IconShapeType): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawPath(shapePath(shape, width.toFloat()), paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    c.drawBitmap(this, 0f, 0f, paint)
    return out
}

fun loadApps(ctx: Context): List<RawApp> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .mapNotNull {
            try {
                RawApp(
                    label = it.loadLabel(pm).toString(),
                    pkg = it.activityInfo.packageName,
                    component = ComponentName(it.activityInfo.packageName, it.activityInfo.name).toString(),
                    raw = it.loadIcon(pm).toUniformBitmap(ICON_PX)
                )
            } catch (e: Throwable) {
                null // one broken icon must never crash the launcher
            }
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

/* ---------- Icon packs (ADW / Nova / Apex compatible) ---------- */

private val PACK_ACTIONS = listOf(
    "org.adw.launcher.THEMES",
    "com.novalauncher.THEME",
    "com.anddoes.launcher.THEME"
)

fun loadPacks(ctx: Context): List<IconPack> {
    val pm = ctx.packageManager
    val found = LinkedHashMap<String, IconPack>()
    for (action in PACK_ACTIONS) {
        try {
            pm.queryIntentActivities(Intent(action), 0).forEach { ri ->
                val p = ri.activityInfo.packageName
                if (p != ctx.packageName && !found.containsKey(p)) {
                    found[p] = IconPack(p, ri.loadLabel(pm).toString())
                }
            }
        } catch (e: Throwable) { /* ignore */ }
    }
    return found.values.sortedBy { it.label.lowercase() }
}

private class PackData(val res: Resources, val pkg: String, val map: Map<String, String>) {
    fun iconFor(component: String): Bitmap? {
        val name = map[component] ?: return null
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null
        return try { ResourcesCompat.getDrawable(res, id, null)?.toBitmap(ICON_PX, ICON_PX) } catch (e: Throwable) { null }
    }
}

private fun loadPackData(ctx: Context, pkg: String): PackData? {
    val res = try { ctx.packageManager.getResourcesForApplication(pkg) } catch (e: Throwable) { return null }

    var parser: XmlPullParser? = null
    try {
        val p = Xml.newPullParser()
        p.setInput(res.assets.open("appfilter.xml"), "utf-8")
        parser = p
    } catch (e: Throwable) { parser = null }
    if (parser == null) {
        val id = res.getIdentifier("appfilter", "xml", pkg)
        if (id != 0) parser = try { res.getXml(id) } catch (e: Throwable) { null }
    }

    val map = HashMap<String, String>()
    val p = parser
    if (p != null) {
        try {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "item") {
                    val comp = p.getAttributeValue(null, "component")?.trim()
                    val drawable = p.getAttributeValue(null, "drawable")?.trim()
                    if (comp != null && drawable != null) map[comp] = drawable
                }
                ev = p.next()
            }
        } catch (e: Throwable) { /* use whatever was parsed */ }
    }
    return PackData(res, pkg, map)
}

/** Builds display icons: icon pack drawable if the pack has one, otherwise the original icon in the chosen shape. */
fun styleApps(ctx: Context, raw: List<RawApp>, shape: IconShapeType, packPkg: String?): List<AppInfo> {
    val pack = if (packPkg != null) loadPackData(ctx, packPkg) else null
    return raw.map { r ->
        val bmp = pack?.iconFor(r.component) ?: r.raw.masked(shape)
        AppInfo(r.label, r.pkg, bmp.asImageBitmap())
    }
}
