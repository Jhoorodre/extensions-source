package eu.kanade.tachiyomi.extension.pt.baozinho

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class Baozinho : KeiSource() {

    private val apiUrl = "https://api.baozinho.com"

    override fun getMangaUrl(manga: SManga) = "$baseUrl/obras/${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/obras/${chapter.url}"

    override suspend fun getPopularManga(page: Int) = getObras(page, "populares_total")

    override suspend fun getLatestUpdates(page: Int) = getObras(page, "atualizadas")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = getObras(page, "populares_total", query)

    private suspend fun getObras(page: Int, orderBy: String, query: String = ""): MangasPage {
        val url = "$apiUrl/obras".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("orderBy", orderBy)
            .apply { if (query.isNotBlank()) addQueryParameter("q", query) }
            .build()
        return client.get(url, Fetch.XHR).parseAs<ObrasDto>().toMangasPage()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.takeIf { it.firstOrNull() == "obras" }?.getOrNull(1)
        return slug?.takeIf { it.isNotEmpty() }?.let { fetchObra(it).toSManga() }
    }

    private suspend fun fetchObra(slug: String) = client.get("$apiUrl/obras/slug/$slug", Fetch.XHR).parseAs<ObraDto>()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // A listagem de capítulos usa o id numérico da obra, que só vem no detalhe.
        val obra = fetchObra(manga.url)
        val newChapters = if (fetchChapters) {
            client.get("$apiUrl/capitulos/obra/${obra.id}", Fetch.XHR)
                .parseAs<List<CapituloDto>>()
                .map { it.toSChapter(manga.url) }
        } else {
            chapters
        }
        return SMangaUpdate(obra.toSManga(), newChapters)
    }

    // Capítulo pago não traz "paginas" sem login: a lista vazia faz o app mostrar o erro.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.memo["id"]?.stringOrNull ?: return emptyList()
        return client.get("$apiUrl/capitulos/$id", Fetch.XHR.from(getChapterUrl(chapter)))
            .parseAs<CapituloPaginasDto>()
            .toPages()
    }
}
