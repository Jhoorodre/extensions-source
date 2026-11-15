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
import kotlin.time.Instant

private const val CDN_URL = "https://cdn.baozinho.com"

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
        status = when (situacao) {
            "em_lancamento" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
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
    val nomeExibido get() = if (gratuito) nome else "🔒 $nome"

    val dataUpload get() = Instant.tryParse(lancadoEm)

    fun path(slug: String) = "$slug/capitulo-${numero.toString().removeSuffix(".0")}"

    fun toSChapter(slug: String) = SChapter.create().apply {
        url = path(slug)
        name = nomeExibido
        chapter_number = numero
        date_upload = dataUpload
        memo = buildJsonObject { put("id", id.toString()) }
    }
}

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
