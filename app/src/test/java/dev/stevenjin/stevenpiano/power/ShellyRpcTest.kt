// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plug's RPC over Bluetooth (v1.26 — M60): the Mongoose OS framing, the JSON-RPC frames, and what the app reads back. */
class ShellyRpcTest {
    @Test
    fun `a frame's length goes to TX control as 4 bytes big-endian, and RX control's reads back`() {
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x2C), ShellyGatt.length(300))
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), ShellyGatt.length(0x01020304))
        assertEquals(300, ShellyGatt.lengthOf(byteArrayOf(0, 0, 1, 0x2C)))
        assertEquals("not ready yet", 0, ShellyGatt.lengthOf(ByteArray(4)))
        assertEquals(0x01020304, ShellyGatt.lengthOf(ShellyGatt.length(0x01020304)))
        assertNull("three bytes are no length", ShellyGatt.lengthOf(byteArrayOf(0, 1, 2)))
        assertNull(ShellyGatt.lengthOf(null))
    }

    @Test
    fun `the frame goes in writes of the MTU less the ATT header, in order, and never under 20 bytes`() {
        val frame = ByteArray(500) { it.toByte() }
        val at247 = ShellyGatt.chunks(frame, 247)
        assertEquals(listOf(244, 244, 12), at247.map { it.size })
        assertArrayEquals(frame, at247.reduce { a, b -> a + b })
        assertEquals("the default MTU: 20 a write", 25, ShellyGatt.chunks(frame, 23).size)
        assertEquals("a nonsense MTU is the default's", 25, ShellyGatt.chunks(frame, 5).size)
        assertEquals(listOf(10), ShellyGatt.chunks(ByteArray(10), 247).map { it.size })
    }

    @Test
    fun `a request is the id, the source, the method and its params`() {
        val frame = JSONObject(ShellyRpc.request(7, "Switch.Set", ShellyRpc.switchSet(on = true, toggleAfter = 30)))
        assertEquals(7, frame.getInt("id"))
        assertEquals("steven-piano", frame.getString("src"))
        assertEquals("Switch.Set", frame.getString("method"))
        val params = frame.getJSONObject("params")
        assertEquals(0, params.getInt("id"))
        assertTrue(params.getBoolean("on"))
        assertEquals("ARM's timer", 30, params.getInt("toggle_after"))
        val cut = ShellyRpc.switchSet(on = false)
        assertFalse(cut.getBoolean("on"))
        assertFalse("CUT has no timer", cut.has("toggle_after"))
        assertFalse("no params, none sent", JSONObject(ShellyRpc.request(1, "Shelly.GetDeviceInfo")).has("params"))
    }

    @Test
    fun `the plug's settings - off after a mains blip, its button unable to hold the rail on, in_mode left out when refused`() {
        val config = ShellyRpc.switchConfig().getJSONObject("config")
        assertEquals("Piano 24 V", config.getString("name"))
        assertEquals("off", config.getString("initial_state"))
        assertFalse(config.getBoolean("auto_on"))
        assertFalse(config.getBoolean("auto_off"))
        assertEquals("detached", config.getString("in_mode"))
        assertTrue(config.getBoolean("in_locked"))
        val without = ShellyRpc.switchConfig(inMode = false).getJSONObject("config")
        assertFalse(without.has("in_mode"))
        assertTrue(without.getBoolean("in_locked"))
        val network = ShellyRpc.networkOff().getJSONObject("config")
        assertFalse(network.getJSONObject("sta").getBoolean("enable"))
        assertFalse("the access point closed too", network.getJSONObject("ap").getBoolean("enable"))
        assertTrue(ShellyRpc.bleRpcOn().getJSONObject("config").getJSONObject("rpc").getBoolean("enable"))
        assertFalse(ShellyRpc.cloudOff().getJSONObject("config").getBoolean("enable"))
    }

    @Test
    fun `answers - a result, an error, a null result, or no answer at all`() {
        val ok = ShellyRpc.answer("""{"id":7,"src":"shellyplugusg4-a8032ab5c1d2","dst":"steven-piano","result":{"was_on":false}}""")!!
        assertEquals(7, ok.id)
        assertTrue(ok.ok)
        assertFalse(ok.result!!.getBoolean("was_on"))
        val error = ShellyRpc.answer("""{"id":8,"src":"x","dst":"steven-piano","error":{"code":-103,"message":"Invalid argument 'in_mode'"}}""")!!
        assertFalse(error.ok)
        assertEquals(-103, error.code)
        val none = ShellyRpc.answer("""{"id":9,"src":"x","dst":"steven-piano","result":null}""")!!
        assertTrue("Shelly.Reboot and BLE.StartPairing answer null", none.ok)
        assertNull(none.result)
        assertNull(ShellyRpc.answer("not json"))
        assertNull("neither result nor error", ShellyRpc.answer("""{"id":1}"""))
        assertTrue(ShellyRpc.restartRequired(ShellyRpc.answer("""{"id":2,"result":{"restart_required":true}}""")))
        assertFalse(ShellyRpc.restartRequired(ShellyRpc.answer("""{"id":2,"result":{"restart_required":false}}""")))
    }

    @Test
    fun `the plug's bonds are read whatever their list's shape`() {
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), ShellyRpc.pairedAddresses(JSONObject("""{"devices":[{"addr":"aa:bb:cc:dd:ee:01"}]}""")))
        assertEquals(listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03"), ShellyRpc.pairedAddresses(JSONObject("""{"paired":["aa:bb:cc:dd:ee:02",{"mac":"aa:bb:cc:dd:ee:03"}]}""")))
        assertEquals(emptyList<String>(), ShellyRpc.pairedAddresses(JSONObject("""{"devices":[]}""")))
        assertEquals(emptyList<String>(), ShellyRpc.pairedAddresses(null))
    }

    @Test
    fun `GetDeviceInfo - the setup state, and whether its firmware pairs`() {
        val info = PlugInfo.of(JSONObject("""{"id":"shellyplugusg4-a8032ab5c1d2","mac":"A8032AB5C1D2","model":"S4PL-00116US","ver":"2.0.3","provision":"confirmed","name":null}"""))!!
        assertEquals(Provision.Confirmed, info.provision)
        assertEquals("S4PL-00116US", info.model)
        assertTrue("2.0.3 pairs", info.pairs)
        assertEquals(Provision.Locked, Provision.of("locked"))
        assertEquals(Provision.Complete, Provision.of(" Complete "))
        assertEquals(Provision.Unknown, Provision.of(null))
        assertFalse("1.7.5 has no BLE.StartPairing", PlugInfo.versionAtLeast("1.7.5", 2, 0))
        assertTrue(PlugInfo.versionAtLeast("2.1.0-beta2", 2, 0))
        assertTrue(PlugInfo.versionAtLeast("10.0.0", 2, 0))
        assertFalse(PlugInfo.versionAtLeast("garbage", 2, 0))
        assertFalse(PlugInfo.versionAtLeast(null, 2, 0))
    }

    @Test
    fun `Switch_GetStatus - the output, its timer and the supply's draw`() {
        val on = plugStatusOf(JSONObject("""{"id":0,"output":true,"apower":6.4,"timer_started_at":1700000000,"timer_duration":30}"""))!!
        assertTrue(on.output)
        assertEquals(30.0, on.timerS!!, 0.0)
        assertEquals(6.4, on.watts!!, 0.0)
        val off = plugStatusOf(JSONObject("""{"id":0,"output":false,"apower":0.0}"""))!!
        assertFalse(off.output)
        assertNull("no timer while off", off.timerS)
        assertNull("not a switch's status", plugStatusOf(JSONObject("""{"id":0}""")))
    }

    @Test
    fun `boards - OK or missing, C1's board first`() {
        val boards = Boards.parse("OK,OK,MISSING,OK,OK,OK,OK")!!
        assertEquals(6, boards.answered)
        assertEquals(7, boards.total)
        assertFalse(boards.all)
        assertEquals(setOf(0, 1, 3, 4, 5, 6), boards.okSet)
        assertTrue(boards.holds(setOf(0, 1, 6)))
        assertFalse("a baseline board missing", boards.holds(setOf(2)))
        assertFalse("a board the piano doesn't report", boards.holds(setOf(7)))
        assertTrue(Boards.parse("OK,OK,OK,OK,OK,OK,OK")!!.all)
        assertNull(Boards.parse(""))
        assertNull(Boards.parse(null))
        assertEquals("a word that isn't OK reads as missing", 0, Boards.parse("??,missing")!!.answered)
    }
}
