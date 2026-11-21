package eu.kanade.tachiyomi.extension.pt.montetai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MonteTaiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun cdnImagesUseMediaRateLimitTier() {
        assertFalse(
            isDynamicSiteRequest(
                "https://cdn.montetaiscanlator.xyz/manga/mediocretoons/obra-teste/1-mediocretoons-12345/001.webp".toHttpUrl(),
                "montetaiscanlator.xyz",
            ),
        )
    }

    @Test
    fun wordpressUploadsUseMediaRateLimitTier() {
        assertFalse(
            isDynamicSiteRequest(
                "https://montetaiscanlator.xyz/wp-content/uploads/2024/12/capa-obra.gif".toHttpUrl(),
                "montetaiscanlator.xyz",
            ),
        )
    }

    @Test
    fun externalImageHostsUseMediaRateLimitTier() {
        assertFalse(
            isDynamicSiteRequest(
                "https://img.example.net/chapter/001.webp".toHttpUrl(),
                "montetaiscanlator.xyz",
            ),
        )
    }

    @Test
    fun ajaxAndPagesOnBaseHostUseSiteRateLimitTier() {
        assertTrue(
            isDynamicSiteRequest(
                "https://montetaiscanlator.xyz/wp-admin/admin-ajax.php".toHttpUrl(),
                "montetaiscanlator.xyz",
            ),
        )
        assertTrue(
            isDynamicSiteRequest(
                "https://montetaiscanlator.xyz/manga/obra-teste/".toHttpUrl(),
                "montetaiscanlator.xyz",
            ),
        )
        assertTrue(
            isDynamicSiteRequest(
                "https://montetaiscanlator.xyz/manga/obra-teste/capitulo-1/".toHttpUrl(),
                "montetaiscanlator.xyz",
            ),
        )
    }

    @Test
    fun rateLimitFollowsDynamicBaseHost() {
        val baseHost = "mirror.example.org"
        assertTrue(
            isDynamicSiteRequest(
                "https://mirror.example.org/wp-admin/admin-ajax.php".toHttpUrl(),
                baseHost,
            ),
        )
        assertFalse(
            isDynamicSiteRequest(
                "https://cdn.mirror.example.org/001.webp".toHttpUrl(),
                baseHost,
            ),
        )
        assertFalse(
            isDynamicSiteRequest(
                "https://montetaiscanlator.xyz/wp-admin/admin-ajax.php".toHttpUrl(),
                baseHost,
            ),
        )
    }

    @Test
    fun chapterListDtoDecodesRealResponseShape() {
        val payload = """{"success":true,"data":{"rows":[{"chapter_id":1001,"url":"https:\/\/montetaiscanlator.xyz\/manga\/obra-teste\/capitulo-17\/","title":"Capitulo 17","meta":"6 horas atr\u00e1s","search":"capitulo 17"}]}}"""
        val dto = json.decodeFromString<ChapterListDto>(payload)

        assertEquals(1, dto.data.rows.size)
        val row = dto.data.rows[0]
        assertEquals("Capitulo 17", row.title)
        assertEquals("6 horas atrás", row.date)
        assertEquals("/manga/obra-teste/capitulo-17/", row.url.toHttpUrl().encodedPath)
    }

    @Test
    fun chapterListDtoRejectsMissingMandatoryUrl() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<ChapterListDto>(
                """{"success":true,"data":{"rows":[{"title":"Capitulo 17","meta":"6 horas atrás"}]}}""",
            )
        }
    }

    @Test
    fun configureClientUsesSeparateSiteAndMediaRateLimits() {
        val source = readSource()
        assertTrue(
            Regex(
                """rateLimit\(\s*3\s*,\s*1\.seconds\s*\)\s*\{[^}]*isDynamicSiteRequest\([^)]*\)\s*\}\s*\.rateLimit\(\s*10\s*,\s*1\.seconds\s*\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(source),
        )
    }

    @Test
    fun chapterPostUsesFetchXhrFromMangaPath() {
        assertTrue(readSource().contains("Fetch.XHR.from(mangaPath)"))
    }

    @Test
    fun chapterPostStreamsJsonWithoutBodyString() {
        assertFalse(readSource().contains(".body.string()"))
    }

    @Test
    fun sourceDoesNotHardcodeSiteHost() {
        assertFalse(readSource().contains("\"montetaiscanlator.xyz\""))
    }

    private fun readSource(): String =
        File("src/eu/kanade/tachiyomi/extension/pt/montetai/MonteTai.kt").readText()
}
