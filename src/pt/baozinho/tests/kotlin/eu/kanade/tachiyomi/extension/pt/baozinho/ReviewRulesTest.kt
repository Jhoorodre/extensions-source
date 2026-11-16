package eu.kanade.tachiyomi.extension.pt.baozinho

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Guards the Keiyoushi reviewer's rules (PR #21064) on the source itself, so a later edit cannot bring the old patterns back.
class ReviewRulesTest {
    private val sourceDir = File("src/eu/kanade/tachiyomi/extension/pt/baozinho")

    private val sources = sourceDir.listFiles { file -> file.extension == "kt" }.orEmpty().associate { it.name to it.readText() }

    private fun assertNone(regex: Regex, why: String) {
        val hits = sources.filter { regex.containsMatchIn(it.value) }.keys
        assertTrue("sources found in $sourceDir", sources.isNotEmpty())
        assertTrue("$why (found in $hits)", hits.isEmpty())
    }

    @Test
    fun `http failures are never hand-made IOExceptions`() = assertNone(Regex("""IOException\("HTTP"""), "throw HttpException(code) instead")

    @Test
    fun `time comes from kotlin time, not the wall clock in millis`() = assertNone(Regex("""System\.currentTimeMillis"""), "use Clock.System.now()")

    @Test
    fun `auth headers are built from headersBuilder`() = assertNone(Regex("""\bheaders\.newBuilder\(\)"""), "use headersBuilder()")

    @Test
    fun `no custom exception classes`() = assertNone(Regex("""class\s+\w+\s*(\(.*\))?\s*:\s*(\w*Exception|IOException|RuntimeException)"""), "use standard exceptions")

    @Test
    fun `nothing blocks or touches the main thread`() = assertNone(Regex("""Dispatchers\.Main|runBlocking|Toast|uiScope|runWebView"""), "login happens on demand, off the main thread")

    @Test
    fun `turnstile comes from core`() {
        assertTrue(sources.values.any { "getTurnstileToken(" in it })
        assertFalse(sources.values.any { "turnstileHtml" in it })
    }
}
