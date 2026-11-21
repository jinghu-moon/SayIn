package seeyuer.sayin.ui

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import seeyuer.sayin.R
import seeyuer.sayin.designsystem.icon.VoiceLinkIcon

/**
 * One configurable button in the draft card's tool row. The enum order is the default order;
 * the user's order and visibility are stored in [DraftTools].
 */
internal enum class DraftTool(val id: String, @StringRes val labelRes: Int) {
    Assist("assist", R.string.assist_open),
    Image("image", R.string.settings_draft_tool_image),
    Translate("translate", R.string.settings_draft_tool_translate),
    Polish("polish", R.string.polish_title),
    Copy("copy", R.string.speak_copy_draft),
    Clear("clear", R.string.speak_clear_draft),
    SendMode("send_mode", R.string.draft_tool_send_mode),
    Engine("engine", R.string.draft_tool_engine),
    SavePhrase("save_phrase", R.string.draft_tool_save_phrase),
    Share("share", R.string.draft_tool_share),
    ;

    val icon: VoiceLinkIcon
        get() = when (this) {
            Assist -> VoiceLinkIcon.Clipboard
            Image -> VoiceLinkIcon.Photo
            Translate -> VoiceLinkIcon.Translate
            Polish -> VoiceLinkIcon.Ai
            Copy -> VoiceLinkIcon.Copy
            Clear -> VoiceLinkIcon.Clear
            SendMode -> VoiceLinkIcon.Send
            Engine -> VoiceLinkIcon.Cloud
            SavePhrase -> VoiceLinkIcon.Plus
            Share -> VoiceLinkIcon.Share
        }
}

/** Stable Compose test tag shared by the tool row and the overflow sheet. */
internal fun DraftTool.testTag(): String = when (this) {
    DraftTool.Assist -> "open-assist"
    DraftTool.Image -> "choose-image"
    DraftTool.Translate -> "translate-draft"
    DraftTool.Polish -> "polish-draft"
    DraftTool.Copy -> "copy-draft"
    DraftTool.Clear -> "clear-draft"
    DraftTool.SendMode -> "send-mode"
    DraftTool.Engine -> "engine"
    DraftTool.SavePhrase -> "save-phrase"
    DraftTool.Share -> "share-draft"
}

/** Identity plus the user's visibility choice; the list position is the user's order. */
internal data class DraftToolEntry(val tool: DraftTool, val visible: Boolean)

/**
 * Where a short tool row sits. Start/End follow the interface direction, so the row honors
 * mirrored layouts while the LTR labels read as left/right.
 */
internal enum class DraftToolSide(val id: String) {
    Start("start"),
    End("end"),
}

/** Each surface keeps its own order, visibility and side; the two profiles are independent. */
internal enum class DraftToolSurface(val id: String) {
    Page("page"),
    Quick("quick"),
}

/** The floating quick card starts with the first few visible tools and is configured separately. */
internal const val QUICK_DRAFT_TOOL_LIMIT = 5

/** Below this count the row no longer spans the card, so a side choice is meaningful. */
internal const val DRAFT_TOOL_SIDE_CHOICE_MAX = 3

internal fun defaultDraftTools(): List<DraftToolEntry> = DraftTool.entries.map { DraftToolEntry(it, true) }

/** First-run quick profile: keep the page's first visible tools, hide the rest, keep the order. */
internal fun defaultQuickDraftTools(full: List<DraftToolEntry> = defaultDraftTools()): List<DraftToolEntry> {
    var shown = 0
    return full.map { entry ->
        val visible = entry.visible && shown < QUICK_DRAFT_TOOL_LIMIT
        if (visible) shown++
        entry.copy(visible = visible)
    }
}

internal fun normalizeDraftToolSide(stored: String?): DraftToolSide =
    DraftToolSide.entries.firstOrNull { it.id == stored } ?: DraftToolSide.Start

/** The tools to render in the saved order. */
internal fun visibleDraftTools(entries: List<DraftToolEntry>): List<DraftTool> =
    entries.filter { it.visible }.map { it.tool }

/**
 * Decodes the persisted `id=1,id=0` list. Unknown ids are dropped, duplicates keep their first
 * slot, and tools added since the value was written are appended (visible for the page, hidden for
 * the quick card, so an upgrade cannot bloat the floating card). A missing or fully invalid value
 * falls back to [fallback].
 */
internal fun normalizeDraftTools(
    stored: String?,
    fallback: List<DraftToolEntry> = defaultDraftTools(),
    missingVisible: Boolean = true,
): List<DraftToolEntry> {
    val ordered = linkedMapOf<DraftTool, Boolean>()
    stored?.split(',')?.forEach { token ->
        val parts = token.trim().split('=', limit = 2)
        if (parts.size != 2) return@forEach
        val tool = DraftTool.entries.firstOrNull { it.id == parts[0].trim() } ?: return@forEach
        val flag = parts[1].trim()
        if (flag != "0" && flag != "1") return@forEach
        if (tool in ordered) return@forEach
        ordered[tool] = flag == "1"
    }
    if (ordered.isEmpty()) return fallback
    DraftTool.entries.forEach { tool ->
        val existing = fallback.firstOrNull { it.tool == tool }
        ordered.putIfAbsent(tool, existing?.visible ?: missingVisible)
    }
    return ordered.map { (tool, visible) -> DraftToolEntry(tool, visible) }
}

internal fun encodeDraftTools(entries: List<DraftToolEntry>): String =
    entries.joinToString(",") { "${it.tool.id}=${if (it.visible) 1 else 0}" }

/** Moves [from] into [to]'s slot; hidden entries keep their slot instead of being dropped. */
internal fun moveDraftTool(entries: List<DraftToolEntry>, from: DraftTool, to: DraftTool): List<DraftToolEntry> {
    val fromIndex = entries.indexOfFirst { it.tool == from }
    val toIndex = entries.indexOfFirst { it.tool == to }
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return entries
    val moved = entries.toMutableList()
    val item = moved.removeAt(fromIndex)
    moved.add(toIndex.coerceIn(0, moved.size), item)
    return moved
}

/** Process-wide tool profiles, persisted with presentation prefs; the page and quick card differ. */
internal object DraftTools {
    private const val PREFS = "presentation"
    private fun entriesKey(surface: DraftToolSurface) =
        if (surface == DraftToolSurface.Page) "draft_tools" else "quick_draft_tools"
    private fun sideKey(surface: DraftToolSurface) =
        if (surface == DraftToolSurface.Page) "draft_tool_side" else "quick_draft_tool_side"
    private val pageEntries = MutableStateFlow(defaultDraftTools())
    private val quickEntries = MutableStateFlow(defaultQuickDraftTools())
    private val pageSide = MutableStateFlow(DraftToolSide.Start)
    private val quickSide = MutableStateFlow(DraftToolSide.Start)

    fun entriesFor(surface: DraftToolSurface) = if (surface == DraftToolSurface.Page) pageEntries else quickEntries
    fun sideFor(surface: DraftToolSurface) = if (surface == DraftToolSurface.Page) pageSide else quickSide

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val page = normalizeDraftTools(prefs.getString(entriesKey(DraftToolSurface.Page), null))
        pageEntries.value = page
        quickEntries.value = normalizeDraftTools(
            prefs.getString(entriesKey(DraftToolSurface.Quick), null),
            fallback = defaultQuickDraftTools(page),
            missingVisible = false,
        )
        val pageSideValue = normalizeDraftToolSide(prefs.getString(sideKey(DraftToolSurface.Page), null))
        pageSide.value = pageSideValue
        quickSide.value = prefs.getString(sideKey(DraftToolSurface.Quick), null)
            ?.let(::normalizeDraftToolSide) ?: pageSideValue
    }

    /** Persists the normalized order and visibility and updates every observing screen. */
    fun replace(context: Context, surface: DraftToolSurface, value: List<DraftToolEntry>) {
        val normalized = normalizeDraftTools(
            encodeDraftTools(value),
            missingVisible = surface == DraftToolSurface.Page,
        )
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(entriesKey(surface), encodeDraftTools(normalized)).apply()
        if (surface == DraftToolSurface.Page) pageEntries.value = normalized
        else quickEntries.value = normalized
    }

    /** Persists the side used only while the selected profile's row is short enough to sit at one edge. */
    fun setSide(context: Context, surface: DraftToolSurface, value: DraftToolSide) {
        context.getSharedPreferences(PREFS, 0).edit().putString(sideKey(surface), value.id).apply()
        if (surface == DraftToolSurface.Page) pageSide.value = value else quickSide.value = value
    }

    fun reset(context: Context, surface: DraftToolSurface) {
        replace(
            context, surface,
            if (surface == DraftToolSurface.Page) defaultDraftTools() else defaultQuickDraftTools(),
        )
        setSide(context, surface, DraftToolSide.Start)
    }
}
