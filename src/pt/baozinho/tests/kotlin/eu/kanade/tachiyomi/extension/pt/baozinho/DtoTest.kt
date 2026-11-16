package eu.kanade.tachiyomi.extension.pt.baozinho

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// Fixtures are real responses from api.baozinho.com (tests/resources).
// SManga/SChapter.create() are stubs on the JVM, so only the pure logic is tested here.
class DtoTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            // jsonInstance comes from Injekt; the unit-test JVM needs one registered.
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/$name")).readText()

    private val slug = "o-conceito-da-reencarnacao"
    private val api = "https://api.baozinho.com"

    @Test
    fun `genres join format and tags`() {
        val work = fixture("obra.json").parseAs<WorkDto>()

        assertEquals("Manhwa, Ação, Fantasia, Reencarnação, Volta no Tempo", work.genres())
    }

    @Test
    fun `alternative titles appear as a list in the description`() {
        val description = fixture("obra.json").parseAs<WorkDto>().descriptionMarkdown().orEmpty()

        assertTrue(description.contains("Nomes alternativos"))
        listOf("Standard of Reincarnation", "Hwansaengui Jeongseok", "O Padrão de Reencarnação").forEach {
            assertTrue("missing $it", description.contains(it))
        }
        assertFalse("the API's | separator must not leak", description.contains(" | "))
    }

    @Test
    fun `listing has next page when page is less than pages`() {
        val page = fixture("obras.json").parseAs<WorksDto>()

        assertEquals(2, page.data.size)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `paid chapter gets a lock and a url without trailing zero`() {
        val chapter = fixture("capitulos.json").parseAs<List<ChapterDto>>()[0]

        assertEquals("🔒 Capítulo 41", chapter.displayName())
        assertEquals("$slug/capitulo-41", chapter.path(slug))
        assertTrue("date must be parsed", chapter.uploadDate > 0)
    }

    @Test
    fun `free chapter has no lock`() {
        val chapter = fixture("capitulos.json").parseAs<List<ChapterDto>>()[1]

        assertEquals("Capítulo 3", chapter.displayName())
        assertEquals("$slug/capitulo-3", chapter.path(slug))
    }

    @Test
    fun `pages come sorted by order`() {
        val pages = fixture("capitulo_gratis.json").parseAs<ChapterPagesDto>().toPages()

        assertEquals(
            listOf("79477ec6dab49534", "ff5530b042484372", "9b7a24538a54a816"),
            pages.map { it.imageUrl!!.substringAfterLast('/').substringBefore('.') },
        )
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
    }

    @Test
    fun `paid chapter without pages becomes an empty list`() {
        assertTrue(fixture("capitulo_pago.json").parseAs<ChapterPagesDto>().toPages().isEmpty())
    }

    @Test
    fun `default listing url sends no empty filters`() {
        val url = worksUrl(api, page = 1, orderBy = "populares_total")

        assertEquals("$api/obras?page=1&orderBy=populares_total", url.toString())
    }

    @Test
    fun `url combines search and filters`() {
        val url = worksUrl(api, page = 2, orderBy = "rating", query = "murim", status = "em_lancamento", format = "manhwa", tagIds = listOf(1))

        assertEquals("murim", url.queryParameter("q"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("rating", url.queryParameter("orderBy"))
        assertEquals("em_lancamento", url.queryParameter("status"))
        assertEquals("manhwa", url.queryParameter("formatoSlug"))
        assertEquals(listOf("1"), url.queryParameterValues("tagIds"))
    }

    @Test
    fun `several tags become repeated parameters and never a comma list`() {
        val url = worksUrl(api, page = 1, orderBy = "populares_total", tagIds = listOf(1, 28))

        // The API answers 500 for tagIds=1,28; only the repeated form works (and it combines with OR).
        assertEquals(listOf("1", "28"), url.queryParameterValues("tagIds"))
        assertFalse(url.toString().contains("%2C") || url.toString().contains(","))
    }

    @Test
    fun `blank query sends no q`() {
        assertNull(worksUrl(api, page = 1, orderBy = "populares_total", query = "   ").queryParameter("q"))
    }

    @Test
    fun `API status maps to Mihon status`() {
        assertEquals(SManga.ONGOING, parseStatus("em_lancamento"))
        assertEquals(SManga.COMPLETED, parseStatus("finalizado"))
        assertEquals(SManga.ON_HIATUS, parseStatus("hiato"))
        assertEquals(SManga.CANCELLED, parseStatus("cancelado"))
        assertEquals(SManga.UNKNOWN, parseStatus(null))
    }

    @Test
    fun `hide paid removes only the locked ones`() {
        val chapters = fixture("capitulos.json").parseAs<List<ChapterDto>>()

        assertEquals(listOf("🔒 Capítulo 41", "Capítulo 3"), chapters.visible(hidePaid = false).map { it.displayName() })
        assertEquals(listOf("Capítulo 3"), chapters.visible(hidePaid = true).map { it.displayName() })
    }

    @Test
    fun `filters drop tags without works and keep the formats`() {
        val filters = FiltersDto(
            tags = fixture("tags.json").parseAs(),
            formats = fixture("formatos.json").parseAs(),
        )

        val tags = filters.tagsWithWorks()

        assertEquals(33, tags.size)
        assertTrue(tags.none { it.worksCount == 0 })
        assertTrue(tags.any { it.name == "Ação" && it.id == 1 })
        assertEquals(7, filters.formats.size)
    }

    // Filter data is cached on disk for 3 days in the shape written by versions that named these fields in Portuguese.
    @Test
    fun `filter data cached by an older version still parses`() {
        val cached = """{"tags":[{"id":1,"nome":"Ação","obrasCount":16}],"formatos":[{"nome":"Manhwa","slug":"manhwa"}]}"""

        val filters = cached.parseAs<FiltersDto>()

        assertEquals("Ação", filters.tags.single().name)
        assertEquals(16, filters.tags.single().worksCount)
        assertEquals("manhwa", filters.formats.single().slug)
    }

    @Test
    fun `paid chapter unlocked by the account has no lock`() {
        val paid = fixture("capitulos.json").parseAs<List<ChapterDto>>()[0]

        assertEquals("🔒 Capítulo 41", paid.displayName())
        assertEquals("Capítulo 41", paid.displayName(unlocked = setOf(2043)))
        assertTrue(paid.isLockedFor(setOf(1, 2)))
        assertFalse(paid.isLockedFor(setOf(2043)))
    }

    @Test
    fun `hide paid respects the account's unlocked chapters`() {
        val chapters = fixture("capitulos.json").parseAs<List<ChapterDto>>()

        assertEquals(2, chapters.visible(hidePaid = true, unlocked = setOf(2043)).size)
    }

    @Test
    fun `when the unlocked chapters are unknown nothing is hidden`() {
        val chapters = fixture("capitulos.json").parseAs<List<ChapterDto>>()

        // Logged in but /desbloqueados failed: better to show too much than to hide what the account owns.
        assertEquals(2, chapters.visible(hidePaid = true, unlocked = null).size)
    }

    @Test
    fun `without a session unlocked is unknown only when credentials exist`() {
        assertNull(unlockedWithoutSession(hasCredentials = true))
        assertEquals(emptySet<Int>(), unlockedWithoutSession(hasCredentials = false))
    }

    // desbloqueados.json was inferred from the site's JS (capId, expiraEm), not captured from a real session.
    @Test
    fun `unlocked list becomes a set of ids`() {
        val ids = fixture("desbloqueados.json").parseAs<List<UnlockedDto>>().map { it.chapterId }.toSet()

        assertEquals(setOf(2043, 9999), ids)
    }

    @Test
    fun `tokens and user are read ignoring extra fields`() {
        val tokens = """{"accessToken":"a","refreshToken":"r","user":{"id":1}}""".parseAs<TokensDto>()
        val user = """{"id":7,"nome":"Fulano","email":"x@y.z"}""".parseAs<UserDto>()

        assertEquals("a" to "r", tokens.accessToken to tokens.refreshToken)
        assertEquals("Fulano", user.name)
    }

    @Test
    fun `refresh body only carries the refreshToken`() {
        assertEquals("""{"refreshToken":"tok"}""", RefreshRequest("tok").toJsonString())
    }

    @Test
    fun `only a clear rejection drops the session`() {
        assertTrue(isSessionRejected(401, null))
        assertTrue(isSessionRejected(403, "Forbidden"))
        assertTrue(isSessionRejected(400, "Refresh token inválido"))
        // Network drops, server errors and rate limits must not sign the user out.
        assertFalse(isSessionRejected(400, "property x should not exist"))
        assertFalse(isSessionRejected(429, "Too many requests"))
        assertFalse(isSessionRejected(500, "Internal server error"))
        assertFalse(isSessionRejected(502, null))
        assertFalse(isSessionRejected(200, null))
    }

    @Test
    fun `login body keeps the API's Portuguese key for the password`() {
        assertEquals(
            """{"email":"a@b.c","senha":"s3nha","turnstileToken":"tk"}""",
            LoginRequest("a@b.c", "s3nha", "tk").toJsonString(),
        )
    }

    @Test
    fun `login failure is classified so the user knows what to fix`() {
        assertEquals(LoginFailure.CREDENTIALS, classifyLoginFailure(401, "Credenciais inválidas"))
        assertEquals(LoginFailure.CREDENTIALS, classifyLoginFailure(400, null))
        assertEquals(LoginFailure.VERIFICATION, classifyLoginFailure(400, "Falha na verificação do Turnstile"))
        assertEquals(LoginFailure.VERIFICATION, classifyLoginFailure(403, "captcha inválido"))
        assertEquals(LoginFailure.OTHER, classifyLoginFailure(500, "Internal server error"))
        assertEquals(LoginFailure.OTHER, classifyLoginFailure(429, "Too many requests"))
    }

    @Test
    fun `a failed login is not retried until the backoff window passes`() {
        val failedAt = Instant.fromEpochMilliseconds(1_000)
        val retryAfter = 5.minutes

        assertTrue(shouldRetryLogin(lastFailureAt = null, now = failedAt, retryAfter = retryAfter))
        assertFalse(shouldRetryLogin(failedAt, failedAt + retryAfter - 1.milliseconds, retryAfter))
        assertTrue(shouldRetryLogin(failedAt, failedAt + retryAfter, retryAfter))
    }

    // Reviewer: HTTP failures are thrown as the library's HttpException, not as a hand-made IOException("HTTP ...").
    @Test
    fun `an unexpected login status becomes an HttpException with the code`() {
        val error = loginError(500, "boom")

        assertTrue(error is HttpException)
        assertEquals(500, (error as HttpException).code)
    }

    @Test
    fun `a rejected login keeps a message the user can act on`() {
        val credentials = loginError(401, "invalid credentials")
        val verification = loginError(403, "turnstile failed")

        assertFalse(credentials is HttpException)
        assertTrue(credentials.message!!.contains("E-mail ou senha"))
        assertTrue(verification.message!!.contains("anti-robô"))
    }

    @Test
    fun `a failed session renewal is an HttpException with the code`() {
        assertEquals(503, (refreshError(503) as HttpException).code)
    }

    @Test
    fun `password summary never shows the value`() {
        assertEquals("Não definida", passwordSummary("", connected = false))
        assertFalse(passwordSummary("s3nha", connected = false).contains("s3nha"))
        assertTrue(passwordSummary("s3nha", connected = true).contains("Conectado"))
        assertFalse(passwordSummary("s3nha", connected = false).contains("Conectado"))
    }

    @Test
    fun `details are fetched only when requested or when the id is unknown`() {
        assertFalse(needsDetails(fetchDetails = false, knownId = 39))
        assertTrue(needsDetails(fetchDetails = true, knownId = 39))
        // Library entries saved by older versions have no id in memo yet.
        assertTrue(needsDetails(fetchDetails = false, knownId = null))
    }

    @Test
    fun `a chapter only triggers a login when no pages came back without a session and credentials exist`() {
        assertFalse(shouldLoginForPages(pagesEmpty = false, hadSession = false, hasCredentials = true))
        assertFalse(shouldLoginForPages(pagesEmpty = true, hadSession = true, hasCredentials = true))
        assertFalse(shouldLoginForPages(pagesEmpty = true, hadSession = false, hasCredentials = false))
        assertTrue(shouldLoginForPages(pagesEmpty = true, hadSession = false, hasCredentials = true))
    }
}
