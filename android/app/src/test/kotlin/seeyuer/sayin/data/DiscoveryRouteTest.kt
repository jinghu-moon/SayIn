package seeyuer.sayin.data

import org.junit.Assert.*
import org.junit.Test

class DiscoveryRouteTest {
    private fun ip(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
    @Test fun instanceNamesOnlyFilterUnrelatedServices() {
        assertTrue(discoveryInstanceMatches("SayIn-abc", "abc"))
        assertTrue(discoveryInstanceMatches("SayIn-abc (2)", "abc"))
        assertFalse(discoveryInstanceMatches("SayIn-abcd", "abc"))
        assertFalse(discoveryInstanceMatches("Another computer", "abc"))
    }
    @Test fun acceptsOnlyCurrentIpv4PrefixIncludingNonOctetMasks() {
        val local = ip(192,168,71,6)
        assertTrue(sameIpv4Subnet(local, ip(192,168,71,72), 24))
        assertFalse(sameIpv4Subnet(local, ip(192,168,72,6), 24))
        assertTrue(sameIpv4Subnet(ip(10,0,0,129), ip(10,0,0,254), 25))
        assertFalse(sameIpv4Subnet(ip(10,0,0,129), ip(10,0,0,127), 25))
    }
    @Test fun rejectsDefaultRoutesAndUnsupportedAddresses() {
        assertFalse(sameIpv4Subnet(ip(10,0,0,1), ip(8,8,8,8), 0))
        assertFalse(sameIpv4Subnet(ByteArray(16), ByteArray(16), 24))
        assertFalse(sameIpv4Subnet(ip(10,0,0,1), ip(10,0,0,1), 33))
        assertTrue(sameIpv4Subnet(ip(10,0,0,1), ip(10,0,0,1), 32))
        assertFalse(sameIpv4Subnet(ip(10,0,0,1), ip(10,0,0,2), 32))
    }
}
