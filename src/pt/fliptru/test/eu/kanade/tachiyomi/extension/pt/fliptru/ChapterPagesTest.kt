package eu.kanade.tachiyomi.extension.pt.fliptru

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression for "only 10 chapters show up" (project-nox-requests#148).
 *
 * The site lists 10 chapters per page; the rest is fetched by JS from the `data-url` of
 * `#chapters-load-more`, and those fragments have no `#chapterListContainer`.
 * The markup below mirrors the real responses for /comic/mezoi-era (21 chapters, 3 pages).
 */
class ChapterPagesTest {

    private fun item(n: Int) = """<div class="chapter-list-item"><div><a href="/comic/mezoi-era/%03d?origin=chapter-list">%03d - Capítulo $n</a></div></div>"""
        .format(n, n)

    private fun loadMore(page: Int) = """<div id="chapters-load-more"><a href="/comic/mezoi-era/chapters?order=asc&page=$page" data-url="/comic/mezoi-era/chapters?order=asc&page=$page">Carregar mais capítulos</a></div>"""

    private fun html(body: String): Document = Jsoup.parse(body, "https://fliptru.com.br/")

    private val firstPage = html(
        """<div id="chapterListContainer">${(1..10).joinToString("") { item(it) }}${loadMore(2)}</div>""",
    )

    private val fragments = mapOf(
        "/comic/mezoi-era/chapters?order=asc&page=2" to html((11..20).joinToString("") { item(it) } + loadMore(3)),
        "/comic/mezoi-era/chapters?order=asc&page=3" to html(item(21)),
    )

    @Test
    fun fetchesEveryPageOfChapters() = runBlocking {
        val chapters = fetchAllChapters(firstPage) { fragments.getValue(it) }

        assertEquals(21, chapters.size)
        assertEquals((1..21).map { it.toFloat() }.reversed(), chapters.map { it.number })
    }

    @Test
    fun parsesFragmentsThatLackTheListContainer() {
        assertEquals(10, parseChapterList(fragments.getValue("/comic/mezoi-era/chapters?order=asc&page=2")).size)
    }

    @Test
    fun readsTheNextPageUrlFromTheLoadMoreButton() {
        assertEquals("/comic/mezoi-era/chapters?order=asc&page=2", nextChaptersPageUrl(firstPage))
        assertNull(nextChaptersPageUrl(fragments.getValue("/comic/mezoi-era/chapters?order=asc&page=3")))
    }

    @Test
    fun stopsWhenTheLoadMoreButtonPointsBackToAPageAlreadyFetched() = runBlocking {
        val loop = html(item(11) + loadMore(2))
        var requests = 0

        val chapters = fetchAllChapters(firstPage) {
            requests++
            loop
        }

        assertEquals(1, requests)
        assertEquals(11, chapters.size)
    }

    @Test
    fun singlePageListNeedsNoExtraRequest() = runBlocking {
        val single = html("""<div id="chapterListContainer">${(1..4).joinToString("") { item(it) }}</div>""")

        val chapters = fetchAllChapters(single) { error("must not fetch") }

        assertEquals(4, chapters.size)
    }
}
