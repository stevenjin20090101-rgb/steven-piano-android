// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/** A network interface as the device lists it: its [name] ("tun0", "wlan0"), whether it is [up], and its addresses. */
data class NetInterface(val name: String, val addresses: List<InetAddress>, val up: Boolean = true)

/**
 * Where the web service listens (BUILD_SPEC.md › v1.5.1 — M18 › Binding): [tailnet], the tablet's
 * Tailscale address, serves the whole panel; [wifi], its address on the Wi-Fi, serves guests only
 * (the request page, the poster, the public API) unless Panel on Wi-Fi too is on. Either may be
 * missing. Never the any-address, never IPv6, never loopback.
 */
data class WebChoice(val tailnet: Inet4Address?, val wifi: Inet4Address?) {
    val isEmpty: Boolean get() = tailnet == null && wifi == null
}

/** One listener the web service starts: the [address] it binds, whether it serves [guestOnly], and other [names] it answers to. */
data class ListenerPlan(val address: String, val guestOnly: Boolean, val names: List<String> = emptyList())

/** Chooses the addresses the panel listens on. Pure but for [list]. */
object WebAddress {
    const val PORT = 8737

    /** The emulator's loopback, for `adb forward` (debug builds on an emulator only). */
    const val LOOPBACK = "127.0.0.1"

    /**
     * The listeners for [choice]: the tailnet address with the whole panel; the Wi-Fi address with
     * guests only unless [panelOnWifi]; and with [loopback] (a debug build on an emulator) the
     * loopback address, answering to "localhost" too. Never the any-address.
     */
    fun plan(choice: WebChoice, panelOnWifi: Boolean, loopback: Boolean): List<ListenerPlan> = listOfNotNull(
        choice.tailnet?.let { ListenerPlan(it.hostAddress!!, guestOnly = false) },
        choice.wifi?.let { ListenerPlan(it.hostAddress!!, guestOnly = !panelOnWifi) },
        if (loopback) ListenerPlan(LOOPBACK, guestOnly = false, names = listOf("localhost")) else null,
    )

    /**
     * The tailnet address: the first IPv4 in Tailscale's 100.64.0.0/10 on an interface that is up
     * (Tailscale's own, `tun0`, first when there are several). The Wi-Fi address: the first
     * private IPv4 (10/8, 172.16/12, 192.168/16) on an interface that is up and named `wlan…`.
     * Anything else (IPv6, loopback, link-local, the any-address, a mobile network) is never chosen.
     */
    fun choose(interfaces: List<NetInterface>): WebChoice {
        val up = interfaces.filter { it.up }
        val tailnet = up.sortedBy { if (it.name.startsWith("tun") || it.name.startsWith("tailscale")) 0 else 1 }
            .flatMap { it.addresses }
            .filterIsInstance<Inet4Address>()
            .firstOrNull(::isTailnet)
        val wifi = up.filter { it.name.startsWith("wlan") }
            .flatMap { it.addresses }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { isPrivate(it) && !isTailnet(it) }
        return WebChoice(tailnet, wifi)
    }

    /** 100.64.0.0/10: the carrier-grade NAT block Tailscale gives its devices. */
    fun isTailnet(address: Inet4Address): Boolean {
        val b = address.address
        return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
    }

    /** A private IPv4 address (RFC 1918): 10/8, 172.16/12, 192.168/16. */
    fun isPrivate(address: Inet4Address): Boolean {
        val b0 = address.address[0].toInt() and 0xFF
        val b1 = address.address[1].toInt() and 0xFF
        return b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168)
    }

    /** `http://100.101.2.3:8737` and [path] after it. */
    fun url(address: InetAddress, path: String = ""): String = "http://${address.hostAddress}:$PORT$path"

    /** The device's interfaces now; none when Android will not say. */
    fun list(): List<NetInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { nif ->
            NetInterface(nif.name, nif.inetAddresses.toList(), up = runCatching { nif.isUp }.getOrDefault(false))
        }
    } catch (e: SocketException) {
        emptyList()
    }
}
