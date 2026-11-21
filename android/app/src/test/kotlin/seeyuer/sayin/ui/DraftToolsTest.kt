package seeyuer.sayin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftToolsTest {
    private fun ids(entries: List<DraftToolEntry>) = entries.map { it.tool.id }

    @Test fun missingOrBlankValueFallsBackToEveryToolVisibleInDefaultOrder() {
        val expected = DraftTool.entries.map { it.id }
        assertEquals(expected, ids(normalizeDraftTools(null)))
        assertEquals(expected, ids(normalizeDraftTools("")))
        assertEquals(expected, ids(normalizeDraftTools("   ")))
        assertTrue(normalizeDraftTools(null).all { it.visible })
    }

    @Test fun storedOrderAndVisibilityArePreserved() {
        val entries = normalizeDraftTools("clear=1,copy=0,assist=1,image=1,translate=1,polish=1")
        assertEquals(
            listOf("clear", "copy", "assist", "image", "translate", "polish",
                "send_mode", "engine", "save_phrase", "share"),
            ids(entries),
        )
        assertFalse(entries.single { it.tool == DraftTool.Copy }.visible)
    }

    @Test fun unknownDuplicatedAndMalformedTokensAreDropped() {
        val entries = normalizeDraftTools("bogus=1,clear=1,clear=0,assist=2,image=1")
        assertEquals(
            listOf("clear", "image", "assist", "translate", "polish", "copy",
                "send_mode", "engine", "save_phrase", "share"),
            ids(entries),
        )
        assertTrue("the first occurrence wins", entries.single { it.tool == DraftTool.Clear }.visible)
        assertTrue(entries.single { it.tool == DraftTool.Image }.visible)
    }

    @Test fun toolsAddedLaterAppearVisibleAtTheEndInsteadOfStayingHidden() {
        val entries = normalizeDraftTools("clear=0,copy=0")
        assertEquals(
            listOf("clear", "copy", "assist", "image", "translate", "polish",
                "send_mode", "engine", "save_phrase", "share"),
            ids(entries),
        )
        assertFalse(entries.single { it.tool == DraftTool.Clear }.visible)
        assertFalse(entries.single { it.tool == DraftTool.Copy }.visible)
        assertTrue(entries.filter { it.tool !in setOf(DraftTool.Clear, DraftTool.Copy) }.all { it.visible })
    }

    @Test fun entirelyInvalidValueFallsBackToDefaults() {
        val entries = normalizeDraftTools("garbage,,=1,nope=nope")
        assertEquals(DraftTool.entries.map { it.id }, ids(entries))
        assertTrue(entries.all { it.visible })
    }

    @Test fun encodeKeepsEveryToolAndRoundTrips() {
        val source = listOf(
            DraftToolEntry(DraftTool.Clear, false),
            DraftToolEntry(DraftTool.Copy, true),
            DraftToolEntry(DraftTool.Assist, false),
            DraftToolEntry(DraftTool.Image, true),
            DraftToolEntry(DraftTool.Translate, false),
            DraftToolEntry(DraftTool.Polish, true),
            DraftToolEntry(DraftTool.SendMode, false),
            DraftToolEntry(DraftTool.Engine, true),
            DraftToolEntry(DraftTool.SavePhrase, false),
            DraftToolEntry(DraftTool.Share, true),
        )
        val encoded = encodeDraftTools(source)
        assertEquals(
            "clear=0,copy=1,assist=0,image=1,translate=0,polish=1," +
                "send_mode=0,engine=1,save_phrase=0,share=1",
            encoded,
        )
        assertEquals(source, normalizeDraftTools(encoded))
    }

    @Test fun hidingEveryToolStillRoundTrips() {
        val hidden = defaultDraftTools().map { it.copy(visible = false) }
        val decoded = normalizeDraftTools(encodeDraftTools(hidden))
        assertEquals(defaultDraftTools().map { it.tool }, decoded.map { it.tool })
        assertTrue(decoded.none { it.visible })
    }

    @Test fun movingKeepsIdentityAndVisibility() {
        val entries = normalizeDraftTools("assist=1,image=0,translate=1,polish=1,copy=0,clear=1")
        val moved = moveDraftTool(entries, DraftTool.Clear, DraftTool.Assist)
        assertEquals(
            listOf("clear", "assist", "image", "translate", "polish", "copy",
                "send_mode", "engine", "save_phrase", "share"),
            ids(moved),
        )
        assertTrue(moved.first().visible)
        assertFalse(moved.single { it.tool == DraftTool.Image }.visible)
        assertFalse(moved.single { it.tool == DraftTool.Copy }.visible)
        assertEquals(entries.size, moved.size)
    }

    @Test fun movingIntoAnotherSlotAndNoOpsStayConsistent() {
        val entries = normalizeDraftTools(null)
        assertEquals(
            listOf("image", "assist", "translate", "polish", "copy", "clear",
                "send_mode", "engine", "save_phrase", "share"),
            ids(moveDraftTool(entries, DraftTool.Assist, DraftTool.Image)),
        )
        assertEquals(entries, moveDraftTool(entries, DraftTool.Assist, DraftTool.Assist))
    }

    @Test fun visibleToolsFollowOrderAndSkipHiddenOnes() {
        val entries = normalizeDraftTools("clear=1,assist=0,copy=1,image=1,translate=0,polish=1")
        assertEquals(
            listOf(DraftTool.Clear, DraftTool.Copy, DraftTool.Image, DraftTool.Polish,
                DraftTool.SendMode, DraftTool.Engine, DraftTool.SavePhrase, DraftTool.Share),
            visibleDraftTools(entries),
        )
    }

    @Test fun quickProfileKeepsTheFirstVisibleToolsAndPreservesOrder() {
        val full = normalizeDraftTools("clear=1,copy=0,assist=1,image=1,translate=1,polish=1")
        val quick = defaultQuickDraftTools(full)
        assertEquals(ids(full), ids(quick))
        assertEquals(
            listOf(DraftTool.Clear, DraftTool.Assist, DraftTool.Image, DraftTool.Translate, DraftTool.Polish),
            visibleDraftTools(quick),
        )
        assertFalse(quick.single { it.tool == DraftTool.Copy }.visible)
        assertFalse(quick.single { it.tool == DraftTool.SendMode }.visible)
    }

    @Test fun quickProfileOnlyShowsAsManyAsThePageAlreadyShows() {
        val sparse = defaultDraftTools().map { it.copy(visible = it.tool in setOf(DraftTool.Assist, DraftTool.Clear)) }
        val quick = defaultQuickDraftTools(sparse)
        assertEquals(listOf(DraftTool.Assist, DraftTool.Clear), visibleDraftTools(quick))
    }

    @Test fun quickUpgradeAddsNewToolsHidden() {
        val stored = "assist=1,image=1,translate=1,polish=1,copy=1,clear=0"
        val quick = normalizeDraftTools(stored, fallback = defaultQuickDraftTools(), missingVisible = false)
        assertFalse(quick.single { it.tool == DraftTool.SendMode }.visible)
        assertTrue(quick.single { it.tool == DraftTool.Assist }.visible)
    }

    @Test fun hidingEveryToolLeavesAnEmptyRow() {
        val hidden = defaultDraftTools().map { it.copy(visible = false) }
        assertTrue(visibleDraftTools(hidden).isEmpty())
        assertTrue(visibleDraftTools(defaultQuickDraftTools(hidden)).isEmpty())
    }

    @Test fun sideAcceptsOnlyThePersistedStartAndEndValues() {
        assertEquals(DraftToolSide.Start, normalizeDraftToolSide(null))
        assertEquals(DraftToolSide.Start, normalizeDraftToolSide(""))
        assertEquals(DraftToolSide.Start, normalizeDraftToolSide("left"))
        assertEquals(DraftToolSide.Start, normalizeDraftToolSide("start"))
        assertEquals(DraftToolSide.End, normalizeDraftToolSide("end"))
    }
}
