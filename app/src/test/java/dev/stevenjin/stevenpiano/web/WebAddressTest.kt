// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** Which addresses the panel listens on (the audit's point 9): the tailnet's and the Wi-Fi's, and nothing else. */
class WebAddressTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    private fun v4(text: String): Inet4Address = ip(text) as Inet4Address

    @Test
    fun `the tailnet address from Tailscale's interface, the Wi-Fi address from wlan`() {
        val choice = WebAddress.choose(
            listOf(
                NetInterface("lo", listOf(ip("127.0.0.1"), ip("::1"))),
                NetInterface("rmnet_data0", listOf(ip("10.84.3.9"))),
                NetInterface("wlan0", listOf(ip("fe80::1"), ip("192.168.1.20"))),
                NetInterface("tun0", listOf(ip("fd7a:115c:a1e0::1"), ip("100.101.2.3"))),
            ),
        )
        assertEquals(v4("100.101.2.3"), choice.tailnet)
        assertEquals(v4("192.168.1.20"), choice.wifi)
    }

    @Test
    fun `never the any-address, loopback, IPv6, a mobile network or an interface that is down`() {
        val choice = WebAddress.choose(
            listOf(
                NetInterface("any", listOf(ip("0.0.0.0"))),
                NetInterface("lo", listOf(ip("127.0.0.1"))),
                NetInterface("wlan0", listOf(ip("fe80::2"), ip("169.254.10.1"), ip("8.8.8.8"))),
                NetInterface("rmnet0", listOf(ip("10.0.0.5"))),
                NetInterface("eth0", listOf(ip("10.0.2.15"))),
                NetInterface("wlan1", listOf(ip("192.168.5.5")), up = false),
                NetInterface("tun1", listOf(ip("100.101.2.3")), up = false),
            ),
        )
        assertNull(choice.tailnet)
        assertNull(choice.wifi)
        assertTrue(choice.isEmpty)
    }

    @Test
    fun `Tailscale's block is 100·64 to 100·127, and a private Wi-Fi address is RFC 1918`() {
        assertTrue(WebAddress.isTailnet(v4("100.64.0.1")))
        assertTrue(WebAddress.isTailnet(v4("100.127.255.254")))
        assertFalse(WebAddress.isTailnet(v4("100.63.255.255")))
        assertFalse(WebAddress.isTailnet(v4("100.128.0.1")))
        assertFalse(WebAddress.isTailnet(v4("10.64.0.1")))
        for (private in listOf("10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.0.10")) assertTrue(private, WebAddress.isPrivate(v4(private)))
        for (public in listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "100.101.2.3", "8.8.8.8")) assertFalse(public, WebAddress.isPrivate(v4(public)))
    }

    @Test
    fun `the listeners are the tailnet's with the panel, the Wi-Fi's for guests unless Panel on Wi-Fi too, loopback only on the emulator`() {
        val both = WebChoice(v4("100.101.2.3"), v4("192.168.1.20"))
        assertEquals(
            listOf(ListenerPlan("100.101.2.3", guestOnly = false), ListenerPlan("192.168.1.20", guestOnly = true)),
            WebAddress.plan(both, panelOnWifi = false, loopback = false),
        )
        assertEquals(ListenerPlan("192.168.1.20", guestOnly = false), WebAddress.plan(both, panelOnWifi = true, loopback = false)[1])
        assertEquals(
            ListenerPlan("127.0.0.1", guestOnly = false, names = listOf("localhost")),
            WebAddress.plan(WebChoice(null, null), panelOnWifi = false, loopback = true).single(),
        )
        assertEquals("no network, no listener", emptyList<ListenerPlan>(), WebAddress.plan(WebChoice(null, null), panelOnWifi = true, loopback = false))
        val every = WebAddress.plan(both, panelOnWifi = true, loopback = true).map { it.address }
        assertFalse("never the any-address", "0.0.0.0" in every)
    }

    @Test
    fun `the carrier block on Wi-Fi or a mobile network is never the tailnet's, and a Wi-Fi alone still serves guests`() {
        // Carriers and some networks use 100.64/10 too: only a VPN's interface gives the tailnet.
        val carrier = WebAddress.choose(
            listOf(
                NetInterface("rmnet_data0", listOf(ip("100.70.1.2"))),
                NetInterface("wlan0", listOf(ip("100.101.2.3"), ip("192.168.1.20"))),
                NetInterface("eth0", listOf(ip("100.90.0.4"))),
            ),
        )
        assertNull(carrier.tailnet)
        assertEquals(v4("192.168.1.20"), carrier.wifi)
        val onlyCarrierWifi = WebAddress.choose(listOf(NetInterface("wlan0", listOf(ip("100.101.2.3")))))
        assertTrue("nothing to listen on", onlyCarrierWifi.isEmpty)
        val tailscale = WebAddress.choose(listOf(NetInterface("tailscale0", listOf(ip("100.101.2.3"))), NetInterface("wlan0", listOf(ip("100.101.9.9")))))
        assertEquals(v4("100.101.2.3"), tailscale.tailnet)
        assertNull(tailscale.wifi)
        val wifiOnly = WebAddress.choose(listOf(NetInterface("wlan0", listOf(ip("10.0.2.16")))))
        assertNull(wifiOnly.tailnet)
        assertEquals(v4("10.0.2.16"), wifiOnly.wifi)
        assertEquals("http://10.0.2.16:8737/request", WebAddress.url(v4("10.0.2.16"), "/request"))
        assertEquals(8737, WebAddress.PORT)
    }
}
