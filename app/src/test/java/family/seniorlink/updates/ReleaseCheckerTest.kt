package family.seniorlink.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ReleaseCheckerTest {
    private val download = "https://github.com/Horkyze/SeniorLink/releases/download/v0.1.10/SeniorLink-0.1.10-debug.apk"

    @Test fun `selects highest version numerically including published pilot prereleases`() {
        val releases = "[${release("v0.1.3")}, ${release("v0.1.10", extra = ",\"prerelease\":true")}, ${release("v0.1.9")}]"
        assertEquals(AppUpdate("0.1.10", download), ReleaseChecker.selectUpdate(releases, "0.1.2"))
    }

    @Test fun `does not offer installed or older releases`() {
        val releases = "[${release("v0.1.2")}, ${release("v0.1.1")}]"
        assertNull(ReleaseChecker.selectUpdate(releases, "0.1.2"))
        assertNull(ReleaseChecker.selectUpdate(releases, "1.0.0"))
        assertNull(ReleaseChecker.selectUpdate(releases, "invalid"))
        assertNull(ReleaseChecker.selectUpdate("[]", "0.1.2"))
    }

    @Test fun `skips drafts malformed entries and releases without downloadable APKs`() {
        val releases = """[
            ${release("v2.0.0", draft = true)},
            ${release("not-a-version")},
            {"tag_name":"v3.0.0","draft":false,"assets":[]},
            ${release("v4.0.0", name = "SHA256SUMS")},
            ${release("v5.0.0", state = "starter")},
            {"unexpected":"entry"},
            ${release("v0.1.10")}
        ]"""
        assertEquals(AppUpdate("0.1.10", download), ReleaseChecker.selectUpdate(releases, "0.1.2"))
    }

    @Test fun `only offers HTTPS downloads from this repository`() {
        listOf(
            "http://github.com/Horkyze/SeniorLink/releases/download/v0.1.10/app.apk",
            "https://example.com/app.apk",
            "https://github.com.evil.example/Horkyze/SeniorLink/releases/download/app.apk",
            "https://github.com/another/repo/releases/download/v0.1.10/app.apk",
            "https://user@github.com/Horkyze/SeniorLink/releases/download/v0.1.10/app.apk",
            "intent://download",
        ).forEach { url ->
            assertNull(url, ReleaseChecker.selectUpdate("[${release("v0.1.10", url = url)}]", "0.1.2"))
        }
    }

    @Test fun `semantic precedence handles prereleases metadata and multi digit components`() {
        val ascending = listOf(
            "0.1.2-alpha", "0.1.2-alpha.1", "0.1.2-alpha.beta", "0.1.2-beta",
            "0.1.2-beta.2", "0.1.2-beta.11", "0.1.2-rc.1", "0.1.2", "0.1.10", "0.10.0", "1.0.0",
        )
        ascending.zipWithNext().forEach { (older, newer) ->
            assertTrue("$older < $newer", ReleaseVersion.parse(older)!! < ReleaseVersion.parse(newer)!!)
            assertTrue("$newer > $older", ReleaseVersion.parse(newer)!! > ReleaseVersion.parse(older)!!)
        }
        assertEquals(0, ReleaseVersion.parse("v0.1.2+build.2")!!.compareTo(ReleaseVersion.parse("0.1.2+build.1")!!))
        assertTrue(ReleaseVersion.parse("9999999999999999999999.0.0")!! > ReleaseVersion.parse("2.0.0")!!)
        listOf("", "1.2", "1.2.3.4", "01.2.3", "1.2.3-01", "1.2.3-", "1.2.3+", "release-1.2.3").forEach {
            assertNull(it, ReleaseVersion.parse(it))
        }
    }

    @Test fun `network request runs off caller thread and releases the connection`() = runBlocking {
        val caller = Thread.currentThread()
        val connection = FakeConnection(body = "[${release("v0.1.10")}]")
        val checker = ReleaseChecker {
            assertNotEquals(caller, Thread.currentThread())
            connection
        }
        assertEquals(AppUpdate("0.1.10", download), checker.latest("0.1.2"))
        assertTrue(connection.disconnected)
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(5_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
    }

    @Test fun `HTTP failures and malformed or oversized responses are silent`() = runBlocking {
        for (code in listOf(301, 403, 404, 429, 500)) {
            val connection = FakeConnection(code = code)
            assertNull(ReleaseChecker { connection }.latest("0.1.2"))
            assertTrue(connection.disconnected)
            assertFalse(connection.inputOpened)
        }
        for (body in listOf("not JSON", "{}", " ".repeat(ReleaseChecker.MAX_RESPONSE_BYTES + 1))) {
            val connection = FakeConnection(body = body)
            assertNull(ReleaseChecker { connection }.latest("0.1.2"))
            assertTrue(connection.disconnected)
        }
        assertNull(ReleaseChecker { throw IOException("offline") }.latest("0.1.2"))
        val timeout = FakeConnection(failure = java.net.SocketTimeoutException())
        assertNull(ReleaseChecker { timeout }.latest("0.1.2"))
        assertTrue(timeout.disconnected)
    }

    @Test fun `cancellation propagates and closes the connection`() = runBlocking {
        val connection = FakeConnection(failure = CancellationException("closed"))
        try {
            ReleaseChecker { connection }.latest("0.1.2")
            fail("Cancellation must not be swallowed")
        } catch (_: CancellationException) {
            assertTrue(connection.disconnected)
        }
    }

    private fun release(
        tag: String, draft: Boolean = false, name: String = "SeniorLink-0.1.10-debug.apk",
        state: String = "uploaded", url: String = download, extra: String = "",
    ) = """{"tag_name":"$tag","draft":$draft,"assets":[{"name":"$name","state":"$state","browser_download_url":"$url"}]$extra}"""

    private class FakeConnection(
        private val code: Int = 200, private val body: String = "[]", private val failure: Exception? = null,
    ) : HttpURLConnection(URL(ReleaseChecker.RELEASES_URL)) {
        var disconnected = false
        var inputOpened = false
        override fun getResponseCode() = code
        override fun getInputStream(): ByteArrayInputStream {
            inputOpened = true
            failure?.let { throw it }
            return ByteArrayInputStream(body.toByteArray())
        }
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }
}
