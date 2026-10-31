package com.example.ioslauncher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Dock slots are stored as -1..-4, home-screen cells as 0.. (page * cellsPerPage + row * columns + column). */
const val DOCK_SLOTS = 4

fun dockSlot(index: Int) = -(index + 1)

/**
 * Remembers where the user dropped each app (pkg -> slot) and survives restarts.
 * Apps without a saved slot are placed in the first free cell, so newly installed apps just appear.
 */
class HomeLayout(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("home_layout", Context.MODE_PRIVATE)

    var slots by mutableStateOf(parse(prefs.getString("slots", null)))
        private set
    var savedCap by mutableIntStateOf(prefs.getInt("cap", 0))
        private set

    /** Turns the saved slots into a complete, collision-free pkg -> slot map for the apps installed now. */
    fun resolve(apps: List<AppInfo>, cap: Int, seedDock: List<AppInfo>): Map<String, Int> {
        val installed = apps.map { it.pkg }.toSet()
        var saved: Map<String, Int> = slots.filterKeys { it in installed }

        if (slots.isEmpty()) {
            // First run: start with the usual phone / messages / browser / camera dock.
            seedDock.take(DOCK_SLOTS).forEachIndexed { i, a -> saved = saved + (a.pkg to dockSlot(i)) }
        } else if (savedCap != cap) {
            // Grid size changed: keep the order of the icons and re-flow them into the new grid.
            val dock = saved.filterValues { it < 0 }
            val grid = saved.filterValues { it >= 0 }.entries.sortedBy { it.value }
            saved = dock + grid.mapIndexed { i, e -> e.key to i }
        }

        val used = HashSet<Int>()
        val result = LinkedHashMap<String, Int>()
        for (a in apps) {
            val s = saved[a.pkg] ?: continue
            val valid = s >= 0 || s in dockSlot(DOCK_SLOTS - 1)..dockSlot(0)
            if (valid && used.add(s)) result[a.pkg] = s
        }
        var next = 0
        for (a in apps) {
            if (a.pkg in result) continue
            while (next in used) next++
            result[a.pkg] = next
            used.add(next)
        }
        return result
    }

    fun commit(map: Map<String, Int>, cap: Int) {
        slots = map
        savedCap = cap
        prefs.edit()
            .putString("slots", map.entries.joinToString(";") { "${it.key}=${it.value}" })
            .putInt("cap", cap)
            .apply()
    }

    fun reset() {
        slots = emptyMap()
        savedCap = 0
        prefs.edit().clear().apply()
    }

    private fun parse(s: String?): Map<String, Int> {
        if (s.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (part in s.split(";")) {
            val i = part.lastIndexOf('=')
            if (i <= 0) continue
            val slot = part.substring(i + 1).toIntOrNull() ?: continue
            out[part.substring(0, i)] = slot
        }
        return out
    }
}

/** Moves [pkg] to [target]. If another app is already there, the two swap places. */
fun moveToSlot(layout: Map<String, Int>, pkg: String, target: Int): Map<String, Int> {
    val origin = layout[pkg] ?: return layout
    if (origin == target) return layout
    val occupant = layout.entries.firstOrNull { it.value == target && it.key != pkg }?.key
    val out = LinkedHashMap(layout)
    out[pkg] = target
    if (occupant != null) out[occupant] = origin
    return out
}
