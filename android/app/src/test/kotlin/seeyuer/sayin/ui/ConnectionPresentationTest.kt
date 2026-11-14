package seeyuer.sayin.ui

import org.junit.Assert.*
import org.junit.Test
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection

class ConnectionPresentationTest {
    private val ready = ClientState(connection = Connection.ONLINE, paired = true, paused = false, active = true, target = "scope")

    @Test fun uncertaintyTakesPrecedenceOverPauseTargetAndActiveDevice() {
        for (state in listOf(ready.copy(paused = true), ready.copy(target = null), ready.copy(active = false))) {
            assertEquals(ConnectionPresentation.Review, state.copy(pauseReason = "outcome_review_required").connectionPresentation)
        }
        assertEquals(ConnectionPresentation.Paused, ready.copy(paused = true, pauseReason = "paused").connectionPresentation)
        assertEquals(ConnectionPresentation.Ready, ready.connectionPresentation)
    }

    @Test fun transportStorageAndRecoveryCannotAppearReady() {
        assertEquals(ConnectionPresentation.First, ClientState(connection = Connection.OFFLINE).connectionPresentation)
        assertEquals(ConnectionPresentation.Offline, ready.copy(connection = Connection.OFFLINE).connectionPresentation)
        assertEquals(ConnectionPresentation.Approval, ready.copy(connection = Connection.APPROVAL).connectionPresentation)
        assertEquals(ConnectionPresentation.Storage, ready.copy(storageHealthy = false).connectionPresentation)
        assertEquals(ConnectionPresentation.Recovering, ready.copy(recovering = true).connectionPresentation)
    }

    @Test fun endpointEntryUsesOneFieldAndNeverResolvesNames() {
        assertEquals("192.168.1.3" to 7788, parseComputerAddress(" 192.168.1.3 "))
        assertEquals("192.168.1.3" to 23456, parseComputerAddress("192.168.1.3:23456"))
        for (invalid in listOf("", "host.local", "https://192.168.1.3", "192.168.001.3", "0.0.0.0", "239.0.0.1", "1.2.3.4:0", "1.2.3.4:65536", "1.2.3.4:", "::1")) {
            assertNull(invalid, parseComputerAddress(invalid))
        }
    }
}
