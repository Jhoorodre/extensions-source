package eu.kanade.tachiyomi.extension.pt.baozinho

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.MarkdownDescription
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.time.Instant

private const val CDN_URL = "https://cdn.baozinho.com"

// tagIds repetido combina por OU; separado por vírgula a API responde 500.
fun obrasUrl(
    apiUrl: String,
    page: Int,
    orderBy: String,
    query: String = "",
    status: String = "",
    formato: String = "",
    tagIds: List<Int> = emptyList(),
): HttpUrl = "$apiUrl/obras".toHttpUrl().newBuilder().apply {
    addQueryParameter("page", page.toString())
    addQueryParameter("orderBy", orderBy)
    query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("q", it) }
    status.takeIf { it.isNotEmpty() }?.let { addQueryParameter("status", it) }
    formato.takeIf { it.isNotEmpty() }?.let { addQueryParameter("formatoSlug", it) }
    tagIds.forEach { addQueryParameter("tagIds", it.toString()) }
}.build()

fun statusDe(situacao: String?) = when (situacao) {
    "em_lancamento" -> SManga.ONGOING
    "finalizado" -> SManga.COMPLETED
    "hiato" -> SManga.ON_HIATUS
    "cancelado" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

@Serializable
class TagDto(val id: Int, val nome: String, val obrasCount: Int = 0)

@Serializable
class FormatoDto(val nome: String, val slug: String)

@Serializable
class FiltrosDto(val tags: List<TagDto>, val formatos: List<FormatoDto>) {
    fun tagsComObras() = tags.filter { it.obrasCount > 0 }
}

@Serializable
class NomeDto(val nome: String)

@Serializable
class ObrasDto(
    val data: List<ObraDto>,
    private val page: Int,
    private val pages: Int,
) {
    val hasNextPage get() = page < pages

    fun toMangasPage() = MangasPage(data.map { it.toSManga() }, hasNextPage)
}

// Lógica pura em funções próprias: SManga.create() é um stub nos testes de unidade.
@Serializable
class ObraDto(
    val id: Int,
    private val nome: String,
    private val slug: String,
    private val imagem: String? = null,
    private val descricao: String? = null,
    private val titulosAlternativos: String? = null,
    @SerialName("status") private val situacao: String? = null,
    private val formato: NomeDto? = null,
    private val autor: NomeDto? = null,
    private val arte: String? = null,
    private val tags: List<NomeDto> = emptyList(),
) {
    fun descricaoMarkdown() = MarkdownDescription.Builder()
        .description(descricao)
        .altTitles(titulosAlternativos.orEmpty().split("|"), "Nomes alternativos")
        .build()

    fun generos() = (listOfNotNull(formato) + tags).joinToString { it.nome }

    fun toSManga() = SManga.create().apply {
        url = slug
        title = nome
        thumbnail_url = imagem?.let { "$CDN_URL/$it" }
        author = autor?.nome
        artist = arte
        description = descricaoMarkdown()
        genre = generos()
        status = statusDe(situacao)
    }
}

@Serializable
class CapituloDto(
    private val id: Int,
    private val numero: Float,
    private val nome: String,
    private val lancadoEm: String? = null,
    private val gratuito: Boolean = false,
) {
    fun bloqueadoPara(desbloqueados: Set<Int>) = !gratuito && id !in desbloqueados

    fun nomeExibido(desbloqueados: Set<Int> = emptySet()) = if (bloqueadoPara(desbloqueados)) "🔒 $nome" else nome

    val dataUpload get() = Instant.tryParse(lancadoEm)

    fun path(slug: String) = "$slug/capitulo-${numero.toString().removeSuffix(".0")}"

    fun toSChapter(slug: String, desbloqueados: Set<Int> = emptySet()) = SChapter.create().apply {
        url = path(slug)
        name = nomeExibido(desbloqueados)
        chapter_number = numero
        date_upload = dataUpload
        memo = buildJsonObject { put("id", id.toString()) }
    }
}

// desbloqueados == null: logado, mas a lista de liberados falhou; melhor mostrar de mais do que esconder o que a conta tem.
fun List<CapituloDto>.visiveis(ocultarPagos: Boolean, desbloqueados: Set<Int>? = emptySet()) = if (ocultarPagos && desbloqueados != null) filterNot { it.bloqueadoPara(desbloqueados) } else this

@Serializable
class CapituloPaginasDto(
    private val paginas: List<PaginaDto> = emptyList(),
) {
    fun toPages() = paginas.sortedBy { it.ordem }.mapIndexed { index, pagina -> Page(index, imageUrl = pagina.url) }
}

@Serializable
class PaginaDto(
    val url: String,
    val ordem: Int,
)

@Serializable
class TokensDto(val accessToken: String, val refreshToken: String)

@Serializable
class RefreshRequest(val refreshToken: String)

@Serializable
class DesbloqueadoDto(val capId: Int)

@Serializable
class UsuarioDto(val nome: String? = null)

// Só uma recusa clara derruba a sessão; rede, 5xx e 429 mantêm o token guardado.
fun sessaoRejeitada(code: Int, message: String?) = code == 401 || code == 403 || (code == 400 && message?.contains("token", ignoreCase = true) == true)
