package eu.kanade.tachiyomi.extension.pt.baozinho

import android.content.Context
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
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
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getLocalStorage
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

private class SessaoRejeitadaException : IOException("sessão rejeitada pelo site")

@Source
abstract class Baozinho :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl = "https://api.baozinho.com"

    private val preferences by getPreferencesLazy()

    private val hidePaid get() = preferences.getBoolean(HIDE_PAID_KEY, false)

    // O refresh token rotaciona a cada renovação; o de acesso vive pouco e fica só na memória.
    private val authLock = Mutex()
    private var accessToken: String? = null
    private var accessExpiresAt = 0L
    private val hasSession get() = preferences.getString(REFRESH_KEY, null) != null
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
            coroutineScope {
                val lista = async { client.get("$apiUrl/capitulos/obra/${obra.id}", Fetch.XHR).parseAs<List<CapituloDto>>() }
                val liberados = async { fetchDesbloqueados(obra.id) }
                val desbloqueados = liberados.await()
                lista.await().visiveis(hidePaid, desbloqueados).map { it.toSChapter(manga.url, desbloqueados.orEmpty()) }
            }
        } else {
            chapters
        }
        return SMangaUpdate(obra.toSManga(), newChapters)
    }

    // Capítulo pago que a conta não liberou não traz "paginas": a lista vazia faz o app mostrar o erro.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.memo["id"]?.stringOrNull ?: return emptyList()
        return getJsonComSessao<CapituloPaginasDto>("$apiUrl/capitulos/$id", Fetch.XHR.from(getChapterUrl(chapter))).toPages()
    }

    // null = logado mas não deu para saber; sem sessão não há nada liberado.
    private suspend fun fetchDesbloqueados(obraId: Int): Set<Int>? {
        if (!hasSession) return emptySet()
        return try {
            getJsonComSessao<List<DesbloqueadoDto>>("$apiUrl/capitulos/obra/$obraId/desbloqueados", Fetch.XHR).map { it.capId }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // O estado do usuário não pode vir do cache de 10 min do cliente.
    private suspend inline fun <reified T> getJsonComSessao(url: String, fetch: Fetch): T {
        var token = tokenDeAcesso()
        for (tentativa in 0..1) {
            val cabecalhos = token?.let { headers.newBuilder().set("Authorization", "Bearer $it").build() } ?: headers
            val response = client.get(url, cabecalhos, fetch, CacheControl.FORCE_NETWORK, ensureSuccess = false)
            if (response.code == 401 && token != null && tentativa == 0) {
                response.close()
                token = tokenDeAcesso(forcar = true)
                continue
            }
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP ${response.code}")
            }
            return response.parseAs<T>()
        }
        error("inalcançável")
    }

    private suspend fun tokenDeAcesso(forcar: Boolean = false): String? = authLock.withLock {
        if (!forcar && System.currentTimeMillis() < accessExpiresAt) return@withLock accessToken
        val refresh = preferences.getString(REFRESH_KEY, null) ?: return@withLock null
        try {
            salvarSessao(renovar(refresh)).accessToken
        } catch (_: SessaoRejeitadaException) {
            encerrarSessao()
            null
        }
    }

    private suspend fun renovar(refreshToken: String): TokensDto {
        val response = client.post("$apiUrl/auth/refresh", Fetch.XHR, RefreshRequest(refreshToken).toJsonRequestBody(), ensureSuccess = false)
        if (response.isSuccessful) return response.parseAs<TokensDto>()
        val code = response.code
        val corpo = response.use { it.body.string() }
        throw if (sessaoRejeitada(code, corpo)) SessaoRejeitadaException() else IOException("HTTP $code ao renovar a sessão")
    }

    // commit() e não apply(): o token novo precisa estar em disco antes de qualquer outra renovação.
    private fun salvarSessao(tokens: TokensDto): TokensDto {
        preferences.edit().putString(REFRESH_KEY, tokens.refreshToken).commit()
        accessToken = tokens.accessToken
        accessExpiresAt = System.currentTimeMillis() + ACCESS_TTL_MS
        return tokens
    }

    private fun encerrarSessao() {
        preferences.edit().remove(REFRESH_KEY).commit()
        accessToken = null
        accessExpiresAt = 0
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        val hidePaidPref = SwitchPreferenceCompat(context).apply {
            key = HIDE_PAID_KEY
            title = "Ocultar capítulos pagos"
            summary = "Esconde da lista os capítulos com 🔒 que a sua conta não liberou. Vale a partir da próxima atualização da obra."
            setDefaultValue(false)
        }

        // Menu de ações: o app só tem preferências com subclasses específicas, então uma lista serve de botão.
        val sessionPref = ListPreference(context).apply {
            key = SESSION_ACTION_KEY
            title = "Sessão do site"
            summary = statusSessao()
            entries = arrayOf("Importar sessão do site", "Sair (apagar a sessão guardada)")
            entryValues = arrayOf(ACTION_IMPORT, ACTION_LOGOUT)
            setOnPreferenceChangeListener { pref, action ->
                when (action) {
                    ACTION_IMPORT -> importarSessao(context, pref)
                    ACTION_LOGOUT -> {
                        encerrarSessao()
                        pref.summary = statusSessao()
                        toast(context, "Sessão removida da extensão.")
                    }
                }
                false
            }
        }

        listOf(hidePaidPref, sessionPref).forEach(screen::addPreference)
    }

    private fun statusSessao() = if (hasSession) {
        "Conectado. Toque para importar outra sessão ou sair."
    } else {
        "Não conectado. 1) Na tela da fonte, abra o WebView (ícone de globo) e entre no site. 2) Feche o WebView. 3) Toque aqui e escolha Importar."
    }

    // Ordem: ler R1 do WebView, renovar com R1, guardar R2 e só então apagar a cópia do WebView, para o site não reusar R1.
    private fun importarSessao(context: Context, pref: Preference) {
        uiScope.launch {
            toast(context, "Importando sessão…")
            val mensagem = try {
                val r1 = getLocalStorage(baseUrl, SITE_REFRESH_KEY)
                if (r1.isNullOrBlank()) {
                    "Nenhuma sessão encontrada no WebView. Entre no site pelo WebView, feche-o e tente de novo."
                } else {
                    val tokens = authLock.withLock { salvarSessao(renovar(r1)) }
                    limparSessaoDoWebView()
                    val nome = try {
                        val cabecalhos = headers.newBuilder().set("Authorization", "Bearer ${tokens.accessToken}").build()
                        client.get("$apiUrl/auth/me", cabecalhos, Fetch.XHR, CacheControl.FORCE_NETWORK).parseAs<UsuarioDto>().nome
                    } catch (_: IOException) {
                        null
                    }
                    "Conectado${nome?.let { " como $it" }.orEmpty()}."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SessaoRejeitadaException) {
                "O site recusou a sessão importada. Entre de novo no WebView e tente outra vez."
            } catch (e: Exception) {
                "Falha ao importar (${e.javaClass.simpleName}). Verifique a conexão e tente de novo."
            }
            pref.summary = statusSessao()
            toast(context, mensagem)
        }
    }

    private suspend fun limparSessaoDoWebView() {
        runCatching {
            runWebView<Unit>(timeout = 10.seconds) {
                onPageFinished {
                    evaluateJs("localStorage.removeItem('$SITE_ACCESS_KEY');localStorage.removeItem('$SITE_REFRESH_KEY')") { resolve(Unit) }
                }
                loadData(baseUrl, """<link rel="icon" href="data:,">""")
            }
        }
    }

    private fun toast(context: Context, texto: String) = Toast.makeText(context, texto, Toast.LENGTH_LONG).show()

    private companion object {
        const val POPULAR = "populares_total"
        const val HIDE_PAID_KEY = "hide_paid_chapters"
        const val REFRESH_KEY = "session_refresh_token"
        const val SESSION_ACTION_KEY = "session_action"
        const val ACTION_IMPORT = "import"
        const val ACTION_LOGOUT = "logout"
        const val SITE_ACCESS_KEY = "bz_access"
        const val SITE_REFRESH_KEY = "bz_refresh"
        const val ACCESS_TTL_MS = 10 * 60 * 1000L
    }
}
