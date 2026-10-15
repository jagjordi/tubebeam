package dev.tubebeam.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetSweepTest {
    private val self = byteArrayOf(192.toByte(), 168.toByte(), 1, 42)

    @Test
    fun slash24ListsAllOtherHosts() {
        val hosts = SubnetSweep.hostsAround(self, 24)
        assertEquals(253, hosts.size)
        assertTrue("192.168.1.1" in hosts)
        assertTrue("192.168.1.254" in hosts)
        assertFalse("192.168.1.42" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
    }

    @Test
    fun largerNetworksAreCappedToOwnSlash24() {
        val hosts = SubnetSweep.hostsAround(byteArrayOf(10, 0, 7, 9), 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.0.7.") })
    }

    @Test
    fun smallNetworksStaySmall() {
        val hosts = SubnetSweep.hostsAround(byteArrayOf(192.toByte(), 168.toByte(), 1, 42), 28) // .32-.47
        assertEquals(13, hosts.size)
        assertEquals("192.168.1.33", hosts.first())
        assertEquals("192.168.1.46", hosts.last())
    }
}
