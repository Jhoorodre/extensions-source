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
    fun `no hand-made IOExceptions with custom messages`() = assertNone(Regex("""\bIOException\("""), "throw HttpException(code); the reviewer wants no custom errors")

    // The maintainer's pattern: the only error this source creates is HttpException(code); everything else is rethrown as is.
    @Test
    fun `the only exception ever created is HttpException`() {
        val thrown = Regex("""\bthrow\s+(\w+)""")
        val allowed = setOf("HttpException", "e", "it")
        val offenders = sources.flatMap { (file, text) -> thrown.findAll(text).map { file to it.groupValues[1] } }.filter { it.second !in allowed }
        assertTrue("sources found", sources.isNotEmpty())
        assertTrue("throw only HttpException(code) or rethrow: $offenders", offenders.isEmpty())
        assertTrue(sources.values.any { "throw HttpException(" in it })
    }

    @Test
    fun `no custom exception classes`() = assertNone(Regex("""class\s+\w+\s*(\(.*\))?\s*:\s*(\w*Exception|IOException|RuntimeException)"""), "use standard exceptions")

    @Test
    fun `nothing blocks or touches the main thread`() = assertNone(Regex("""Dispatchers\.Main|runBlocking|Toast|uiScope|runWebView"""), "login happens on demand, off the main thread")

    @Test
    fun `turnstile comes from core`() {
        assertTrue(sources.values.any { "getTurnstileToken(" in it })
        assertFalse(sources.values.any { "turnstileHtml" in it })
    }

    @Test
    fun `filters have no placeholder header`() = assertNone(Regex("""Filter\.Header"""), "the reviewer asked for no filler UI")

    @Test
    fun `every declared identifier is in english`() {
        // Declared names only: user-facing strings and the API's @SerialName keys stay in Portuguese.
        val portuguese = Regex(
            "obra|cap[ií]tulo|capitulo|senha|entrar|sess[aã]o|desbloque|credencia|filtro|carreg|nome|imagem|descri[cç][aã]o|autor|lan[cç]ad|gratuito|p[aá]ginas|titulo|t[ií]tulo|numero|n[uú]mero|ausente|recusad|verifica[cç]",
            RegexOption.IGNORE_CASE,
        )
        val declared = Regex("""\b(?:val|var|fun|class|object)\s+(?:[\w<>]+\.)?(\w+)""")
        val offenders = sources.flatMap { (file, text) -> declared.findAll(text).map { file to it.groupValues[1] } }
            .filter { portuguese.containsMatchIn(it.second) }
        assertTrue("Portuguese identifiers: $offenders", offenders.isEmpty())
    }

    @Test
    fun `auth headers start from the source's headersBuilder`() {
        assertTrue(sources["Baozinho.kt"]!!.contains("headersBuilder()"))
    }
}
