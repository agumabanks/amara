package co.sanaa.agent.actions

internal object TikTokSoundSelection {
    const val PACKAGE = "com.zhiliaoapp.musically"
    private const val SHEET = "$PACKAGE:id/g0z"
    private const val TITLE = "$PACKAGE:id/title"
    private const val SOUND = "$PACKAGE:id/tv_top_text"
    // Captured composer controls: 46.9.3 (OPPO) and 47.0.3 (TPS450M).
    // 47.0.3 exposes an unresolved resource label instead of "Remove sound".
    private val REMOVE_IDS = setOf("$PACKAGE:id/e0m", "$PACKAGE:id/e1d")

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val valid: Boolean get() = right > left && bottom > top
        fun contains(other: Bounds): Boolean = valid && other.valid &&
            other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
        fun contains(x: Int, y: Int): Boolean = valid && x >= left && x < right && y >= top && y < bottom
    }

    data class Node(
        val path: String,
        val parent: String?,
        val packageName: String,
        val windowId: Int,
        val id: String,
        val className: String,
        val text: String,
        val description: String,
        val bounds: Bounds,
        val visible: Boolean = true,
        val enabled: Boolean = true,
        val clickable: Boolean = false,
        val checkable: Boolean = false,
        val checked: Boolean = false,
        val selected: Boolean = false,
        val editable: Boolean = false,
    )

    data class Frame(val packageName: String, val windowId: Int, val bounds: Bounds, val nodes: List<Node>) {
        fun usable(node: Node): Boolean = node.visible && node.enabled && node.packageName == packageName &&
            node.windowId == windowId && bounds.contains(node.bounds)
        fun inside(node: Node, parent: Node): Boolean = node.path == parent.path || node.path.startsWith(parent.path + "/")
        fun visible(id: String): List<Node> = nodes.filter { it.visible && it.id == id }
        fun parent(node: Node): Node? = nodes.singleOrNull { it.path == node.parent }
        fun action(node: Node): Node? {
            var current: Node? = node
            while (current != null && usable(current)) {
                if (current.clickable) return current.takeIf { it.parent != null && it.bounds.contains(node.bounds) &&
                    nodes.none { other -> inside(other, it) && other.visible && other.path != node.path &&
                        (other.text.trim() == "Next" || other.description.trim() == "Next") } }
                current = parent(current)
            }
            return null
        }
        fun sameWindow(other: Frame): Boolean = packageName == PACKAGE && other.packageName == PACKAGE &&
            windowId >= 0 && windowId == other.windowId && bounds.valid && bounds == other.bounds
    }

    data class Row(val title: Node, val control: Node, val sheet: Node, val contents: List<Node>) {
        val highlighted: Boolean get() = title.selected || control.selected ||
            (title.checkable && title.checked) || (control.checkable && control.checked)
        fun sameIdentity(other: Row): Boolean =
            title.copy(selected = false, checked = false) == other.title.copy(selected = false, checked = false) &&
                control.copy(selected = false, checked = false) == other.control.copy(selected = false, checked = false) &&
                sheet == other.sheet && contents == other.contents
        fun transitionedFrom(before: Row): Boolean = sameIdentity(before) && !before.highlighted && highlighted &&
            ((!before.title.selected && title.selected) || (!before.control.selected && control.selected) ||
                (before.title.checkable && !before.title.checked && title.checked) ||
                (before.control.checkable && !before.control.checked && control.checked))
    }

    internal fun normalizedTitle(text: String): String {
        val named = text.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"")
            .replace("&lt;", "<").replace("&gt;", ">")
        return Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(named) { match ->
            val number = match.groupValues[1]
            val code = if (number.startsWith("x")) number.drop(1).toIntOrNull(16) else number.toIntOrNull()
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
        }.trim().replace(Regex("\\s+"), " ")
    }

    fun isSelectedTitle(text: String): Boolean {
        val value = text.trim()
        return value.isNotEmpty() && value.length <= 180 && value.lowercase() !in setOf(
            "add sound", "sound", "sounds", "music", "original sound", "loading", "loading...", "search") &&
            !value.endsWith("…") && !value.endsWith("...")
    }

    private fun sheet(frame: Frame): Node? = frame.nodes.filter {
        it.visible && (it.id == SHEET || it.description.equals("Bottom sheet", true))
    }.singleOrNull()?.takeIf(frame::usable)

    fun rows(frame: Frame): List<Row> {
        if (frame.packageName != PACKAGE || frame.windowId < 0) return emptyList()
        val sheet = sheet(frame) ?: return emptyList()
        val titles = frame.visible(TITLE).filter { frame.inside(it, sheet) }
        return titles.mapNotNull { title ->
            if (!frame.usable(title) || !sheet.bounds.contains(title.bounds) || !isSelectedTitle(title.text) ||
                titles.count { it.text.trim() == title.text.trim() } != 1) return@mapNotNull null
            var control: Node? = title
            while (control != null && control.path != sheet.path && !control.clickable && !control.checkable) {
                control = frame.parent(control)
            }
            val row = control?.takeIf { it.path != sheet.path && frame.inside(it, sheet) && frame.usable(it) &&
                it.bounds.contains(title.bounds) && sheet.bounds.contains(it.bounds) } ?: return@mapNotNull null
            if (titles.count { frame.inside(it, row) } != 1) return@mapNotNull null
            val contents = frame.nodes.filter { frame.inside(it, row) && it.visible }
            if (contents.any { !frame.usable(it) || !row.bounds.contains(it.bounds) }) return@mapNotNull null
            Row(title, row, sheet, contents.map { it.copy(selected = false, checked = false) })
        }
    }

    // Revalidate the actual target, not playback animation/decorative descendants.
    fun matchingRow(frame: Frame, expected: Row): Row? = rows(frame).singleOrNull {
        it.sameTrack(expected) && it.title.path == expected.title.path &&
            it.control.path == expected.control.path && it.control.bounds == expected.control.bounds &&
            it.title.bounds == expected.title.bounds && it.sheet.path == expected.sheet.path &&
            it.sheet.bounds == expected.sheet.bounds
    }

    fun composerSound(frame: Frame): Node? {
        if (frame.packageName != PACKAGE || frame.windowId < 0 || sheet(frame) != null ||
            frame.visible(TITLE).isNotEmpty()) return null
        val next = frame.nodes.filter { frame.usable(it) && (it.text.trim() == "Next" || it.description.trim() == "Next") }
            .singleOrNull() ?: return null
        if (frame.action(next) == null) return null
        return frame.visible(SOUND).singleOrNull()?.takeIf { frame.usable(it) &&
            (it.text.trim() == "Add sound" || isSelectedTitle(it.text)) && frame.action(it) != null }
    }

    fun composerConfirmed(frame: Frame, expected: String): Boolean {
        val sound = composerSound(frame) ?: return false
        if (!isSelectedTitle(expected) || normalizedTitle(sound.text) != normalizedTitle(expected)) return false
        val remove = frame.nodes.filter { frame.usable(it) &&
            (it.id in REMOVE_IDS || it.description.equals("Close", true) || it.description.equals("Remove sound", true))
        }.singleOrNull() ?: return false
        var group = frame.parent(sound)
        while (group != null && group.parent != null) {
            if (frame.inside(remove, group)) {
                return frame.usable(group) && group.bounds.contains(sound.bounds) && group.bounds.contains(remove.bounds) &&
                    frame.nodes.none { frame.inside(it, group) && it.visible &&
                        (it.text.trim() == "Next" || it.description.trim() == "Next") }
            }
            group = frame.parent(group)
        }
        return false
    }

    fun outsidePoint(frame: Frame): Pair<Int, Int>? {
        val sheet = sheet(frame) ?: return null
        if (rows(frame).isEmpty() || sheet.bounds.top <= frame.bounds.top) return null
        val x = frame.bounds.left + (frame.bounds.right - frame.bounds.left) / 2
        val y = frame.bounds.top + (sheet.bounds.top - frame.bounds.top) / 2
        if (!frame.bounds.contains(x, y) || sheet.bounds.contains(x, y)) return null
        if (frame.nodes.any { it.visible && it.bounds.contains(x, y) &&
                (it.editable || ((it.clickable || it.checkable) &&
                    (it.text.isNotBlank() || it.description.isNotBlank()))) }) return null
        return x to y
    }

    // TikTok changes row geometry and inserts trim/favourite controls on selection.
    // Compare the track identity, not a byte-for-byte accessibility subtree.
    internal fun Row.sameTrack(other: Row): Boolean {
        fun artists(row: Row): List<String> {
            val named = row.contents.filter { it.id.endsWith(":id/tv_author") }
            val metadata = named.ifEmpty { row.contents.filter {
                it.path != row.title.path && it.text.isNotBlank() && !it.clickable
            } }
            return metadata.map { normalizedTitle(it.text.substringBefore('·')) }
        }
        return normalizedTitle(title.text) == normalizedTitle(other.title.text) && artists(this) == artists(other)
    }

    private fun Row.hasTrimControl(): Boolean = contents.any {
        it.visible && it.enabled && it.clickable &&
            (it.description.equals("Cut", true) || it.description.equals("Trim", true))
    }

    suspend fun select(
        observe: () -> Frame?,
        openPicker: (Frame) -> Boolean,
        tapRow: (Frame, Row) -> Boolean,
        dismiss: (Frame, Row) -> Boolean,
        pause: suspend () -> Unit,
        report: (String, String?) -> Unit = { _, _ -> },
        allowed: () -> Boolean = { true },
        now: () -> Long = { System.nanoTime() / 1_000_000 },
    ): Boolean {
        fun valid(frame: Frame) = frame.packageName == PACKAGE && frame.windowId >= 0 && frame.bounds.valid
        fun failed(reason: String, track: String? = null): Boolean {
            report(reason, track)
            return false
        }
        val budget = UiProgressBudget(idleMillis = 30_000, maxMillis = 90_000, now = now)
        var initial: Frame? = null
        for (attempt in 0 until 300) {
            if (!allowed()) return failed("OWNER_PAUSED")
            if (budget.expired()) return failed("EDITOR_STALLED")
            val frame = observe()
            if (frame != null && frame.packageName.isNotBlank() && !valid(frame)) return failed("FOREIGN_WINDOW")
            if (frame != null && composerSound(frame) != null) { initial = frame; break }
            pause()
        }
        val editor = initial ?: return failed("EDITOR_UNAVAILABLE")
        budget.progress("editor")
        val attached = composerSound(editor)!!.text.trim()
        if (composerConfirmed(editor, attached)) {
            pause()
            val fresh = observe()
            if (fresh != null && composerConfirmed(fresh, attached)) {
                report("SELECTED", attached)
                return true
            }
        }
        if (!openPicker(editor)) return failed("PICKER_OPEN_FAILED")
        report("PICKER_OPENING", null)
        budget.progress("picker_opened")
        var baseline: Row? = null
        var pickerObserved = false
        var candidatesObserved = false
        var frameObserved = false
        for (attempt in 0 until 300) {
            if (!allowed()) return failed("OWNER_PAUSED")
            if (budget.expired()) return failed(when {
                !frameObserved -> "PICKER_WINDOW_UNAVAILABLE"
                candidatesObserved -> "PICKER_TAP_REJECTED"
                pickerObserved -> "PICKER_ROWS_UNAVAILABLE"
                else -> "PICKER_STALLED"
            })
            pause()
            val frame = observe() ?: continue
            if (frame.packageName.isBlank()) continue
            if (!valid(frame)) return failed("FOREIGN_WINDOW")
            frameObserved = true
            if (sheet(frame) != null && !pickerObserved) {
                pickerObserved = true
                budget.progress("picker_visible")
                report("PICKER_VISIBLE", null)
            }
            val candidate = rows(frame).firstOrNull { !it.highlighted && !it.hasTrimControl() } ?: continue
            if (!candidatesObserved) {
                candidatesObserved = true
                budget.progress("rows_available")
                report("ROWS_AVAILABLE", candidate.title.text)
            }
            // The live click adapter re-observes the exact node/window before dispatch.
            if (tapRow(frame, candidate)) { baseline = candidate; break }
        }
        val chosen = baseline ?: return failed("PICKER_ROW_UNAVAILABLE")
        val expected = chosen.title.text.trim()
        report("TRACK_TAPPED", expected)
        budget.progress("track_tapped")
        var stable = 0
        var composerStable = 0
        var dismissed = false
        for (attempt in 0 until 300) {
            if (!allowed()) return failed("OWNER_PAUSED")
            if (budget.expired()) return failed(if (dismissed) "COMPOSER_STALLED" else "SELECTION_STALLED", expected)
            pause()
            val frame = observe() ?: continue
            if (frame.packageName.isBlank()) continue
            if (!valid(frame)) return failed("FOREIGN_WINDOW", expected)
            // Some versions apply the track and close the picker themselves.
            composerStable = if (composerConfirmed(frame, expected)) composerStable + 1 else 0
            if (composerStable >= 2) {
                report("SELECTED", expected)
                return true
            }
            if (dismissed) continue
            val row = rows(frame).singleOrNull { it.sameTrack(chosen) }
            val applied = row != null && (row.transitionedFrom(chosen) ||
                (!chosen.hasTrimControl() && row.hasTrimControl()))
            stable = if (applied) stable + 1 else 0
            // Closing a picker is reversible. Some versions expose no selected/trim
            // signal at all: inspect the composer after a bounded settle, without
            // treating dismissal as proof that audio was attached.
            if (row != null && (stable >= 2 || attempt >= 8)) {
                report(if (stable >= 2) "ROW_SELECTION_CONFIRMED" else "CHECKING_COMPOSER", expected)
                if (!dismiss(frame, row)) return failed("PICKER_DISMISS_FAILED", expected)
                dismissed = true
                budget.progress("picker_dismissed")
            }
        }
        return failed(if (dismissed) "COMPOSER_UNCONFIRMED" else "SELECTION_UNCONFIRMED", expected)
    }
}
