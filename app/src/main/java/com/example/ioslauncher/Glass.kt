package com.example.ioslauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** One UI-style "squircle" (superellipse) used for all app icons. Higher n = squarer. */
class SquircleShape(private val n: Double = 5.0) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val a = size.width / 2f
        val b = size.height / 2f
        val steps = 90
        val path = Path()
        for (i in 0..steps) {
            val t = 2.0 * PI * i / steps
            val c = cos(t)
            val s = sin(t)
            val x = (a + a * sign(c) * abs(c).pow(2.0 / n)).toFloat()
            val y = (b + b * sign(s) * abs(s).pow(2.0 / n)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

val IconShape = SquircleShape(5.0)

/**
 * Glass surface: translucent tinted fill + a bright, angled edge highlight.
 * (Compose can't blur what is behind a single element, so the real backdrop blur is
 * applied at window level for the search screen; see MainActivity.)
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = Color.White
): Modifier = this
    .clip(shape)
    .background(
        Brush.linearGradient(
            listOf(tint.copy(alpha = 0.32f), tint.copy(alpha = 0.12f))
        )
    )
    .border(
        1.dp,
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.75f),
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.40f)
            )
        ),
        shape
    )

/** Same squircle as [SquircleShape], as an android.graphics.Path (used to bake icons once). */
fun squirclePath(size: Float, n: Double = 5.0): android.graphics.Path {
    val r = size / 2f
    val path = android.graphics.Path()
    val steps = 120
    for (i in 0..steps) {
        val t = 2.0 * PI * i / steps
        val c = cos(t)
        val s = sin(t)
        val x = (r + r * sign(c) * abs(c).pow(2.0 / n)).toFloat()
        val y = (r + r * sign(s) * abs(s).pow(2.0 / n)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Icon shapes the user can pick in Launcher settings. */
enum class IconShapeType(val label: String) {
    SQUIRCLE("Squircle"), CIRCLE("Circle"), ROUNDED("Rounded"), SQUARE("Square"), TEARDROP("Teardrop");

    companion object {
        fun from(name: String?): IconShapeType = values().firstOrNull { it.name == name } ?: SQUIRCLE
    }
}

fun shapePath(type: IconShapeType, size: Float): android.graphics.Path {
    val cw = android.graphics.Path.Direction.CW
    return when (type) {
        IconShapeType.SQUIRCLE -> squirclePath(size)
        IconShapeType.CIRCLE -> android.graphics.Path().apply { addOval(0f, 0f, size, size, cw) }
        IconShapeType.ROUNDED -> android.graphics.Path().apply {
            addRoundRect(0f, 0f, size, size, size * 0.24f, size * 0.24f, cw)
        }
        IconShapeType.SQUARE -> android.graphics.Path().apply {
            addRoundRect(0f, 0f, size, size, size * 0.06f, size * 0.06f, cw)
        }
        IconShapeType.TEARDROP -> android.graphics.Path().apply {
            val r = size * 0.5f
            val s = size * 0.1f
            // radii order: top-left, top-right, bottom-right, bottom-left (x,y each)
            addRoundRect(android.graphics.RectF(0f, 0f, size, size), floatArrayOf(r, r, r, r, s, s, r, r), cw)
        }
    }
}
