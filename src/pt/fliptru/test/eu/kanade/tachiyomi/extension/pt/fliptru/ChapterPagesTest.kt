package eu.kanade.tachiyomi.extension.pt.fliptru

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // /comic/jornada-maligna-3zky/001 (older reader), first two pages
    private val olderReader = """<div id="page_771620" class="comic_page_image text-center" style="margin: auto; height: auto; min-height: 200px; width: 900px; max-width: 100%; position: relative; top: -0px"> <img src="https://media.fliptru.com.br/media/comic/jornada-maligna-3zky/o-dia-da-grande-dominacao-mundial/1.png" fetchpriority="high" decoding="async" alt="Página 1 de O dia da grande dominação mundial" class="img-fluid" width="900" height="7309" style="max-width: 100%; height: auto;"><div id="page_771617" class="comic_page_image text-center" style="margin: auto; height: auto; min-height: 200px; width: 900px; max-width: 100%; position: relative; top: -1px"> <img src="https://media.fliptru.com.br/media/comic/jornada-maligna-3zky/o-dia-da-grande-dominacao-mundial/2.png" loading="lazy" decoding="async" alt="Página 2 de O dia da grande dominação mundial" class="img-fluid" width="900" height="7529" style="max-width: 100%; height: auto;">"""

    // /comic/fora-da-lei-le8t/001 (carousel reader): an empty placeholder, then the page slides
    private val carouselReader = """<div id="page_empty" class="comic_page_image embla__slide"> &nbsp; </div><div id="page_1252784" class="comic-page-image embla__slide" data-track-url="/analytics/read/page/9479b129-c11e-4f3b-82aa-075c2f3c4c80" style="width: 600px;"> <img src="https://media.fliptru.com.br/media/comic/fora-da-lei-le8t/rumo-a-liberdade/1000162793_1794660272.png" fetchpriority="high" decoding="async" alt="Página 1 de Rumo a liberdade" class="page-align-right"> </div><div id="page_1252788" class="comic-page-image embla__slide" data-track-url="/analytics/read/page/bea6b0a9-7f12-49c7-811f-349068a25739" style="width: 600px;"> <img src="https://media.fliptru.com.br/media/comic/fora-da-lei-le8t/rumo-a-liberdade/1000162781_1794660273.png" loading="lazy" decoding="async" alt="Página 2 de Rumo a liberdade" class="page-align-left"> </div>"""

    @Test
    fun readsPagesFromTheOlderReader() {
        val urls = parsePageUrls(html(olderReader))

        assertEquals(2, urls.size)
        assertTrue(urls.all { it.startsWith("https://media.fliptru.com.br/") })
    }

    @Test
    fun readsPagesFromTheCarouselReaderAndSkipsItsEmptyPlaceholder() {
        val urls = parsePageUrls(html(carouselReader))

        assertEquals(2, urls.size)
        assertTrue(urls.all { "fora-da-lei-le8t" in it })
    }

    @Test
    fun prefersDataSrcAndDropsRepeatsAndNonHttpImages() {
        val document = html(
            """<div class="comic_page_image"><img data-src="https://a.test/1.jpg" src="https://a.test/blank.gif"></div>""" +
                """<div class="comic-page-image"><img src="https://a.test/1.jpg"></div>""" +
                """<div class="comic-page-image"><img src="https://a.test/2.jpg"></div>""" +
                """<div class="comic-page-image"><img src="data:image/gif;base64,AAAA"></div>""",
        )

        assertEquals(listOf("https://a.test/1.jpg", "https://a.test/2.jpg"), parsePageUrls(document))
    }
}
