package eu.bradshaw.seidr.sync

import eu.bradshaw.seidr.sync.DownloadRules.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRulesTest {
    @Test fun `media is written, appended only on a 206 resume`() {
        assertEquals(Start.Write(append = false), DownloadRules.start(200, "audio/flac", 0))
        assertEquals(Start.Write(append = true), DownloadRules.start(206, "audio/flac", 500))
        // If-Range said the file changed: the server sends it all again.
        assertEquals(Start.Write(append = false), DownloadRules.start(200, "audio/flac", 500))
        assertEquals(Start.Write(append = false), DownloadRules.start(200, null, 0))
    }

    @Test fun `a 200 error envelope is never saved as a track`() {
        // What Navidrome 0.64 sends for a missing id.
        assertEquals(Start.Fail("server sent an error, not the file"), DownloadRules.start(200, "application/xml", 0))
        assertEquals(Start.Fail("server sent an error, not the file"), DownloadRules.start(200, "application/json; charset=utf-8", 0))
        assertEquals(Start.Fail("server sent an error, not the file"), DownloadRules.start(206, "text/xml", 10))
    }

    @Test fun `416 on a resume restarts, other errors fail`() {
        assertEquals(Start.Restart, DownloadRules.start(416, "text/plain", 53294501))
        assertEquals(Start.Fail("HTTP 416"), DownloadRules.start(416, "text/plain", 0))
        assertEquals(Start.Fail("HTTP 500"), DownloadRules.start(500, "text/plain", 10))
    }

    @Test fun `error message is read from either envelope`() {
        val xml = """<subsonic-response status="failed"><error code="70" message="data not found"></error></subsonic-response>"""
        val json = """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"data not found"}}}"""
        assertEquals("data not found", DownloadRules.envelopeMessage(xml))
        assertEquals("data not found", DownloadRules.envelopeMessage(json))
        assertNull(DownloadRules.envelopeMessage("nope"))
    }

    @Test fun `finished file must match both sizes`() {
        assertNull(DownloadRules.finish(expected = 1000, total = 1000, length = 1000))
        assertNull(DownloadRules.finish(expected = 0, total = -1, length = 1234))
        assertEquals("incomplete: 900 of 1000 bytes", DownloadRules.finish(1000, 1000, 900))
        assertEquals("got 229 bytes, expected 1000", DownloadRules.finish(1000, 229, 229))
        assertEquals("got 229 bytes, expected 1000", DownloadRules.finish(1000, -1, 229))
    }
}
