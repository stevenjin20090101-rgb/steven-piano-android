// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The relay protocol's messages and frames, as `cloud/src/shared/protocol.ts` writes and reads them. */
class RelayProtocolTest {
    @Test
    fun `a frame is its id big-endian, its kind, then its payload, as the relay's encodeFrame`() {
        val frame = Frame(0x0102_0304L, Frame.RES_CHUNK, byteArrayOf(9, 8, 7))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 3, 9, 8, 7), frame.encode())
        val top = Frame(0xDEAD_BEEFL, Frame.REQ_END).encode()
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 2), top)
        val back = Frame.decode(top)!!
        assertEquals("an id past 2³¹ reads as the unsigned number", 0xDEAD_BEEFL, back.id)
        assertEquals(Frame.REQ_END, back.kind)
        assertEquals(0, back.payload.size)
        val full = Frame.decode(Frame(7, Frame.REQ_CHUNK, ByteArray(RelayProtocol.MAX_CHUNK) { it.toByte() }).encode())!!
        assertEquals(RelayProtocol.MAX_CHUNK, full.payload.size)
        assertEquals(255.toByte(), full.payload[255])
        assertEquals(listOf(1, 2, 3, 4), listOf(Frame.REQ_CHUNK, Frame.REQ_END, Frame.RES_CHUNK, Frame.RES_END))
    }

    @Test
    fun `a frame shorter than its head, or with more than a chunk, is none`() {
        assertNull(Frame.decode(ByteArray(4)))
        assertNull(Frame.decode(ByteArray(Frame.HEAD + RelayProtocol.MAX_CHUNK + 1)))
        assertTrue(runCatching { Frame(-1, 1) }.isFailure)
        assertTrue(runCatching { Frame(0x1_0000_0000L, 1) }.isFailure)
        assertTrue(runCatching { Frame(1, 1, ByteArray(RelayProtocol.MAX_CHUNK + 1)) }.isFailure)
    }

    @Test
    fun `the room's hello is read with its caps, and one that names another prefix is none`() {
        val hello = RelayProtocol.decode(
            """{"t":"hello","pianoId":"abcdefgh2345","host":"Relay.Example.dev","prefix":"/p/abcdefgh2345","caps":{"maxBody":104857600,"chunk":65536,"window":1048576},"at":1759250000000}""",
        ) as RelayMessage.Hello
        assertEquals("abcdefgh2345", hello.pianoId)
        assertEquals("the host in lower case", "relay.example.dev", hello.host)
        assertEquals("/p/abcdefgh2345", hello.prefix)
        assertEquals(Caps(104_857_600L, 65_536, 1_048_576), hello.caps)
        assertEquals(1_759_250_000_000L, hello.at)
        assertEquals("localhost:8787", (RelayProtocol.decode(hello.copy(host = "localhost:8787").encode()) as RelayMessage.Hello).host)
        assertNull(RelayProtocol.decode(hello.copy(prefix = "/p/other").encode()))
        assertNull(RelayProtocol.decode(hello.copy(pianoId = "ABC").encode()))
        assertNull(RelayProtocol.decode(hello.copy(host = "evil.example/p").encode()))
        val odd = RelayProtocol.decode("""{"t":"hello","pianoId":"abcdefgh2345","host":"r.dev","prefix":"/p/abcdefgh2345","caps":{"chunk":999999,"window":5}}""") as RelayMessage.Hello
        assertEquals("caps out of range take the defaults", Caps(), odd.caps)
    }

    @Test
    fun `a request keeps only the forwarded headers, its query without the question mark, and its id as an unsigned number`() {
        val req = RelayProtocol.decode(
            """{"t":"req","id":4294967295,"method":"PUT","path":"/api/upload","query":"?name=a%20b.mid","headers":{"Host":"r.dev","cookie":"sp_session=x","x-steven-piano":"1","content-length":"10","authorization":"Bearer x","x-relay-address":"1.2.3.4"},"address":"203.0.113.9","prefix":"/p/abcdefgh2345","body":true}""",
        ) as RelayMessage.Req
        assertEquals(4_294_967_295L, req.id)
        assertEquals("PUT", req.method)
        assertEquals("/api/upload", req.path)
        assertEquals("name=a%20b.mid", req.query)
        assertEquals(mapOf("host" to "r.dev", "cookie" to "sp_session=x", "x-steven-piano" to "1", "content-length" to "10"), req.headers)
        assertEquals("203.0.113.9", req.address)
        assertTrue(req.body)
        for (bad in listOf(
            """{"t":"req","id":-1,"method":"GET","path":"/","query":"","headers":{},"address":"a","prefix":"","body":false}""",
            """{"t":"req","id":4294967296,"method":"GET","path":"/","query":"","headers":{},"address":"a","prefix":"","body":false}""",
            """{"t":"req","id":1.5,"method":"GET","path":"/","query":"","headers":{},"address":"a","prefix":"","body":false}""",
            """{"t":"req","id":"1","method":"GET","path":"/","query":"","headers":{},"address":"a","prefix":"","body":false}""",
            """{"t":"req","id":1,"method":"get","path":"/","query":"","headers":{},"address":"a","prefix":"","body":false}""",
            """{"t":"req","id":1,"method":"GET","path":"api","query":"","headers":{},"address":"a","prefix":"","body":false}""",
            """{"t":"req","id":1,"method":"GET","path":"/\r\nX: y","query":"","headers":{},"address":"a","prefix":"","body":false}""",
            """{"t":"req","id":1,"method":"GET","path":"/","query":"","headers":[],"address":"a","prefix":"","body":false}""",
        )) {
            assertNull(bad, RelayProtocol.decode(bad))
        }
        val noQuery = RelayProtocol.decode("""{"t":"req","id":2,"method":"GET","path":"/","headers":{},"address":"a","prefix":"","body":false}""") as RelayMessage.Req
        assertEquals("", noQuery.query)
    }

    @Test
    fun `the tablet's messages carry exactly the protocol's fields`() {
        fun json(message: RelayMessage) = JSONObject(message.encode())
        val res = json(RelayMessage.Res(7, 200, mapOf("Content-Type" to "application/json; charset=utf-8"), 12))
        assertEquals(setOf("t", "id", "status", "headers", "length"), res.keys().asSequence().toSet())
        assertEquals("res", res.getString("t"))
        assertEquals(7, res.getInt("id"))
        assertEquals("application/json; charset=utf-8", res.getJSONObject("headers").getString("Content-Type"))
        assertEquals(12L, res.getLong("length"))
        assertEquals(mapOf("t" to "req.credit", "id" to 3, "bytes" to 65536), flat(json(RelayMessage.ReqCredit(3, 65_536))))
        assertEquals(mapOf("t" to "ws.accept", "id" to 9), flat(json(RelayMessage.WsAccept(9))))
        assertEquals(mapOf("t" to "ws.refuse", "id" to 9, "status" to 401), flat(json(RelayMessage.WsRefuse(9, 401))))
        assertEquals(mapOf("t" to "ws.text", "id" to 9, "data" to """{"type":"state"}"""), flat(json(RelayMessage.WsText(9, """{"type":"state"}"""))))
        assertEquals(mapOf("t" to "ws.close", "id" to 9, "code" to 4000, "reason" to "The session has ended."), flat(json(RelayMessage.WsClose(9, 4000, "The session has ended."))))
        assertEquals(mapOf("t" to "cmd.result", "id" to 5, "ok" to true, "message" to "Playing."), flat(json(RelayMessage.CmdResult(5, true, "Playing."))))
        assertEquals(mapOf("t" to "secret.ack"), flat(json(RelayMessage.SecretAck)))
        val status = json(RelayMessage.Status(JSONObject().put("app", JSONObject().put("version", "1.10").put("code", 18)).put("at", 1)))
        assertEquals("status", status.getString("t"))
        assertEquals("1.10", status.getJSONObject("app").getString("version"))
    }

    @Test
    fun `every message reads back as it was written`() {
        val messages = listOf(
            RelayMessage.Hello("abcdefgh2345", "relay.example.dev", "/p/abcdefgh2345", Caps(), 5),
            RelayMessage.Req(1, "POST", "/api/login", "", mapOf("host" to "relay.example.dev", "content-type" to "application/json"), "203.0.113.9", "/p/abcdefgh2345", true),
            RelayMessage.ReqAbort(2),
            RelayMessage.ReqCredit(2, 70_000),
            RelayMessage.Res(3, 204, mapOf("Cache-Control" to "no-store"), 0),
            RelayMessage.WsOpen(4, mapOf("cookie" to "sp_session=t", "origin" to "https://relay.example.dev", "host" to "relay.example.dev"), "203.0.113.9"),
            RelayMessage.WsAccept(4),
            RelayMessage.WsRefuse(4, 503),
            RelayMessage.WsText(4, "{}"),
            RelayMessage.WsClose(4, 1000, ""),
            RelayMessage.Cmd(5, "transport", mapOf("action" to "next")),
            RelayMessage.CmdResult(5, false, "Not now."),
            RelayMessage.Secret("A".repeat(43)),
            RelayMessage.SecretAck,
        )
        for (message in messages) assertEquals(message.toString(), message, RelayProtocol.decode(message.encode()))
        assertEquals("the secret never prints", "Secret(kept)", RelayMessage.Secret("A".repeat(43)).toString())
    }

    @Test
    fun `too long, too deep, unknown or malformed text is no message`() {
        val long = """{"t":"ws.text","id":1,"data":"${"x".repeat(RelayProtocol.MAX_TEXT)}"}"""
        assertNull(RelayProtocol.decode(long))
        val head = """{"t":"ws.text","id":1,"data":""""
        val atLimit = head + "x".repeat(RelayProtocol.MAX_TEXT - head.length - 2) + "\"}"
        assertEquals(RelayProtocol.MAX_TEXT, atLimit.length)
        assertTrue(RelayProtocol.decode(atLimit) is RelayMessage.WsText)
        val multibyte = """{"t":"ws.text","id":1,"data":"${"é".repeat(RelayProtocol.MAX_TEXT / 2)}"}"""
        assertNull("counted in UTF-8 bytes, not characters", RelayProtocol.decode(multibyte))
        assertNull(RelayProtocol.decode("""{"t":"cmd","id":1,"name":"transport","args":{"a":{"b":{"c":{"d":{"e":{"f":{}}}}}}}}"""))
        assertNull(RelayProtocol.decode("""{"t":"bogus","id":1}"""))
        assertNull(RelayProtocol.decode("""not json"""))
        assertNull(RelayProtocol.decode("""[1,2]"""))
        assertNull(RelayProtocol.decode("""{"t":"secret","secret":"short"}"""))
        assertNull(RelayProtocol.decode("""{"t":"cmd","id":1,"name":"rm -rf","args":{}}"""))
        assertNull(RelayProtocol.decode("""{"t":"ws.refuse","id":1,"status":200}"""))
        assertEquals("a close without a code is a normal one", RelayMessage.WsClose(1, 1000, ""), RelayProtocol.decode("""{"t":"ws.close","id":1}"""))
    }

    @Test
    fun `UTF-8 lengths are counted as the encoder would`() {
        for (text in listOf("", "abc", "é", "日本", "🎹", "a🎹é日")) assertEquals(text, text.toByteArray(Charsets.UTF_8).size, RelayProtocol.utf8Length(text))
        assertFalse(RelayProtocol.HOST.matches("relay.example.dev:"))
        assertTrue(RelayProtocol.HOST.matches("10.0.2.2:8787"))
    }

    private fun flat(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { json.get(it) }
}
