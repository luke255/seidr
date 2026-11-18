package eu.bradshaw.seidr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExportTest {
    private val known = mapOf(
        "https://music.example.org" to "<server>", "music.example.org" to "<server>",
        "alice" to "<user>", "bobby" to "<kid>", "Alice's Fold" to "<device>",
    )

    private fun r(s: String) = LogExport.redact(s, known)

    @Test fun knownValuesGoWholeAndIgnoringCase() {
        assertEquals("playing for <user> from <device>", r("playing for Alice from Alice's Fold"))
        assertEquals("<kid> and bobbysox", r("bobby and bobbysox"))
        assertEquals("connect to <server>/rest/ping.view", r("connect to https://music.example.org/rest/ping.view"))
    }

    @Test fun authParamsAndTokensAreHidden() {
        val out = r("GET https://music.example.org/rest/stream.view?id=12&u=alice&t=abc123&s=salt99&v=1.16.1")
        assertEquals("GET <server>/rest/stream.view?id=12&u=<user>&t=<hidden>&s=<hidden>&v=1.16.1", out)
        assertEquals("Authorization: <hidden> x", r("Authorization: Bearer abc.def x"))
        assertEquals("jwt <token> end", r("jwt eyJhbGciOiJIUzI1.eyJzdWIiOiJ4In0.sig-abc end"))
        assertEquals("password=<hidden>", r("?password=hunter2").removePrefix("?"))
    }

    @Test fun unknownHostsAndAddressesAreHidden() {
        assertEquals("failed: https://<host>/rest", r("failed: https://other.ts.net:4533/rest"))
        assertEquals("https://<host>/x", r("https://user:pw@host.lan/x"))
        assertEquals("Failed to connect to <ip>:4533", r("Failed to connect to 192.168.1.20:4533"))
        assertEquals("from /<ip> port", r("from /fe80::1c2b:3aff:fe4d:5e6f port"))
        assertEquals("addr <ip>", r("addr 2001:db8:85a3:0:0:8a2e:370:7334"))
        assertEquals("mail <email>", r("mail someone@example.com"))
    }

    @Test fun githubAndOrdinaryLogTextStay() {
        val line = "11-18 12:34:56.789  1234  5678 I Sync    : pass: ok=3 unchanged=40 failed=0 v1.6.12"
        assertEquals(line, r(line))
        assertEquals("https://github.com/luke255/seidr/releases", r("https://github.com/luke255/seidr/releases"))
        assertEquals("at eu.bradshaw.seidr.Foo.bar(Foo.kt:12)", r("at eu.bradshaw.seidr.Foo.bar(Foo.kt:12)"))
        assertEquals("projectM::Engine::render", r("projectM::Engine::render"))
    }

    @Test fun nothingSecretSurvivesARealisticLine() {
        val out = r("W Playback: error on 42: HttpDataSourceException: https://music.example.org/rest/stream.view?u=alice&p=enc:7365637265&f=json from 10.0.0.5")
        for (secret in listOf("alice", "music.example.org", "7365637265", "10.0.0.5")) assertFalse(secret, secret in out)
        assertTrue(out.contains("error on 42"))
    }
}
