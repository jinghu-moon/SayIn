package seeyuer.sayin.ui

import org.junit.Assert.*
import org.junit.Test
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.ReceptionScope
import seeyuer.sayin.data.receptionScope

class SpeakInteractionTest {
    private val scope = ReceptionScope("192.168.1.23:7788", "target-a", "7")
    private fun finish(text: String = "运行测试", composing: Boolean = false) =
        SpeakInteraction().finish(text, composing, SpeakSendMode.Automatic, scope, 1000)

    @Test fun typingAndSwitchingDoNotStartCountdowns() {
        val interaction = SpeakInteraction().inputChanged()
        assertNull(interaction.countdown)
        assertNull(interaction.cancelCountdown().countdown)
        assertNull(interaction.edit().countdown)
        assertEquals(interaction.version, interaction.cancelCountdown().version)
    }

    @Test fun onlyExplicitFinishInAutomaticModeArmsCountdown() {
        assertEquals(DraftReview.Automatic, finish().review)
        assertEquals(4000L, finish().countdown!!.deadlineMillis)
        assertNull(SpeakInteraction().finish("运行测试", false, SpeakSendMode.Manual, scope, 1000).countdown)
    }

    @Test fun longChineseEnglishAndEmojiUseTheSameAutomaticRule() {
        for (text in listOf("中".repeat(4000), "😀".repeat(1000), "运行 cargo test", "Keep the same draft.", "C:/src")) {
            assertEquals(text, DraftReview.Automatic, finish(text).review)
            assertNotNull(text, finish(text).countdown)
        }
    }

    @Test fun automaticPreferenceDoesNotAuthorizeTypingOrComposition() {
        val typing = SpeakInteraction().inputChanged().edit()
        assertNull(typing.countdown)
        assertNull(typing.consumeDue("English draft", false, scope, Long.MAX_VALUE))
        assertNull(typing.finish("English draft", true, SpeakSendMode.Automatic, scope, 1000).countdown)
    }

    @Test fun emptyInvalidAndComposingInputCannotArm() {
        for (text in listOf("", " \n\t", "bad\u0000", "\ud800", "中".repeat(4001))) {
            assertNull(text, finish(text).countdown)
        }
        assertEquals(DraftReview.Composing, finish(composing = true).review)
        assertNull(finish(composing = true).countdown)
    }

    @Test fun editingCancelsOldTicketButNextExplicitUtteranceCanArmAgain() {
        val edited = finish().edit().inputChanged()
        assertNull(edited.countdown)
        assertNull(edited.consumeDue("运行测试", false, scope, 9000))
        val reviewed = edited.finish("重新测试", false, SpeakSendMode.Automatic, scope, 2000)
        assertEquals(DraftReview.Automatic, reviewed.review)
        assertNotNull(reviewed.countdown)
    }

    @Test fun editingAwayAndBackCannotResurrectOldCountdown() {
        val initial = finish()
        val changed = initial.inputChanged().inputChanged()
        assertFalse(initial.countdown!!.matches("运行测试", changed.version, false, scope))
        assertNull(changed.consumeDue("运行测试", false, scope, 9000))
    }

    @Test fun countdownRequiresSameComputerTargetRevisionAndCommittedText() {
        val initial = finish()
        for (replacement in listOf(null, scope.copy(address = "192.168.1.24:7788"),
            scope.copy(target = "target-b"), scope.copy(revision = "8"), scope.copy(port = 23456), scope.copy(connectionEpoch = 1))) {
            assertNull(initial.consumeDue("运行测试", false, replacement, 9000))
        }
        assertNull(initial.consumeDue("别的内容", false, scope, 9000))
        assertNull(initial.consumeDue("运行测试", true, scope, 9000))
    }

    @Test fun deadlineConsumesOnceAndNeverBeforeTime() {
        val initial = finish()
        assertNull(initial.consumeDue("运行测试", false, scope, 3999))
        val consumed = initial.consumeDue("运行测试", false, scope, 4000)!!
        assertNull(consumed.countdown)
        assertNull(consumed.consumeDue("运行测试", false, scope, 9000))
        assertEquals(3, initial.countdown!!.secondsRemaining(1000))
        assertEquals(1, initial.countdown.secondsRemaining(3999))
        assertEquals(0, initial.countdown.secondsRemaining(5000))
    }

    @Test fun offlineFinishDoesNotQueueFutureAutoSend() {
        val offline = SpeakInteraction().finish("运行测试", false, SpeakSendMode.Automatic, null, 1000)
        assertEquals(DraftReview.Offline, offline.review)
        assertNull(offline.consumeDue("运行测试", false, scope, 9000))
        assertNull(finish().cancelCountdown().consumeDue("运行测试", false, scope, 9000))
    }

    @Test fun receptionScopeExistsOnlyWhileReadyAndChangesWithState() {
        val ready = ClientState(connection = Connection.ONLINE, address = scope.address, target = scope.target,
            revision = scope.revision, active = true, paused = false)
        assertEquals(scope, ready.receptionScope)
        for (blocked in listOf(ready.copy(connection = Connection.OFFLINE), ready.copy(paused = true),
            ready.copy(active = false), ready.copy(target = null), ready.copy(recovering = true), ready.copy(storageHealthy = false))) {
            assertNull(blocked.receptionScope)
        }
        assertNotEquals(scope, ready.copy(revision = "8").receptionScope)
    }
}
