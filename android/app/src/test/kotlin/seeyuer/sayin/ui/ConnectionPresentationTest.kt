package seeyuer.sayin.ui

import org.junit.Assert.*
import org.junit.Test
import seeyuer.sayin.data.ClientState
import seeyuer.sayin.data.Connection
import seeyuer.sayin.data.ClientIssue
import seeyuer.sayin.R

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

    @Test fun permissionAndStorageOverrideRetryAndStaleTransport() {
        val denied = ready.copy(connection = Connection.OFFLINE, issue = ClientIssue.LOCAL_NETWORK_PERMISSION)
        assertEquals(ConnectionPresentation.Permission, denied.connectionPresentation)
        assertEquals(R.string.connect_lan_title, connectionStatusText(denied))
        assertFalse(denied.showConnectionRetry)
        assertFalse(denied.copy(storageHealthy = false).showConnectionRetry)
        assertEquals(ConnectionPresentation.Storage, denied.copy(storageHealthy = false).connectionPresentation)
        assertTrue(ready.copy(connection = Connection.OFFLINE).showConnectionRetry)
        assertFalse(ready.copy(connection = Connection.OFFLINE, discovering = true).showConnectionRetry)
        assertFalse(ready.copy(connection = Connection.CONNECTING).showConnectionRetry)
        assertFalse(ready.showConnectionRetry)
    }

    @Test fun discoveryAndActualDesktopGateHaveDistinctStatus() {
        assertEquals(R.string.discovering_computer, connectionStatusText(ready.copy(connection = Connection.OFFLINE, discovering = true)))
        assertEquals(R.string.connect_retry_wait, connectionStatusText(ready.copy(connection = Connection.OFFLINE)))
        assertEquals(R.string.connect_ready, connectionStatusText(ready))
        assertNotEquals(R.string.connect_ready, connectionStatusText(ready.copy(paused = true)))
        assertNotEquals(R.string.connect_ready, connectionStatusText(ready.copy(recovering = true)))
    }

    @Test fun endpointEntryUsesOneFieldAndNeverResolvesNames() {
        assertEquals("192.168.1.3" to 7788, parseComputerAddress(" 192.168.1.3 "))
        assertEquals("192.168.1.3" to 23456, parseComputerAddress("192.168.1.3:23456"))
        for (invalid in listOf("", "host.local", "https://192.168.1.3", "192.168.001.3", "0.0.0.0", "239.0.0.1", "1.2.3.4:0", "1.2.3.4:65536", "1.2.3.4:", "::1")) {
            assertNull(invalid, parseComputerAddress(invalid))
        }
    }
}
