package eu.kanade.tachiyomi.extension.pt.baozinho

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl

@Source
abstract class Baozinho :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl = "https://api.baozinho.com"

    private val preferences by getPreferencesLazy()

    private val hidePaid get() = preferences.getBoolean(HIDE_PAID_KEY, false)

    override fun getMangaUrl(manga: SManga) = "$baseUrl/obras/${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/obras/${chapter.url}"

    override suspend fun getPopularManga(page: Int) = fetchObras(obrasUrl(apiUrl, page, POPULAR))

    override suspend fun getLatestUpdates(page: Int) = fetchObras(obrasUrl(apiUrl, page, "atualizadas"))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = obrasUrl(
            apiUrl,
            page,
            orderBy = filters.firstInstanceOrNull<SortFilter>()?.value ?: POPULAR,
            query = query,
            status = filters.firstInstanceOrNull<StatusFilter>()?.value.orEmpty(),
            formato = filters.firstInstanceOrNull<FormatoFilter>()?.value.orEmpty(),
            tagIds = filters.firstInstanceOrNull<TagFilter>()?.state.orEmpty().filter { it.state }.map { it.id },
        )
        return fetchObras(url)
    }

    private suspend fun fetchObras(url: HttpUrl) = client.get(url, Fetch.XHR).parseAs<ObrasDto>().toMangasPage()

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val tags = async { client.get("$apiUrl/tags", Fetch.XHR).parseAs<List<TagDto>>() }
        val formatos = async { client.get("$apiUrl/formatos", Fetch.XHR).parseAs<List<FormatoDto>>() }
        FiltrosDto(tags.await(), formatos.await()).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filtros = data?.parseAs<FiltrosDto>()
        val filters = mutableListOf<Filter<*>>(SortFilter(), StatusFilter())
        if (filtros == null) {
            filters += Filter.Header("Formato e gêneros carregam em segundo plano: reabra os filtros em instantes")
        } else {
            filters += FormatoFilter(filtros.formatos)
            filters += TagFilter(filtros.tagsComObras())
        }
        return FilterList(filters)
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
                .visiveis(hidePaid)
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = HIDE_PAID_KEY
            title = "Ocultar capítulos pagos"
            summary = "Esconde da lista os capítulos com 🔒. Vale a partir da próxima atualização da obra."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private companion object {
        const val POPULAR = "populares_total"
        const val HIDE_PAID_KEY = "hide_paid_chapters"
    }
}
