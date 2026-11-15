package eu.kanade.tachiyomi.extension.pt.baozinho

import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

// Fixtures são respostas reais de api.baozinho.com (tests/resources).
// SManga/SChapter.create() são stubs na JVM, então só a lógica pura é testada aqui.
class DtoTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            // jsonInstance vem do Injekt; no teste da JVM é preciso registrar um.
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/$name")).readText()

    private val slug = "o-conceito-da-reencarnacao"

    @Test
    fun `generos juntam formato e tags`() {
        val obra = fixture("obra.json").parseAs<ObraDto>()

        assertEquals("Manhwa, Ação, Fantasia, Reencarnação, Volta no Tempo", obra.generos())
    }

    @Test
    fun `nomes alternativos aparecem como lista na descricao`() {
        val description = fixture("obra.json").parseAs<ObraDto>().descricaoMarkdown().orEmpty()

        assertTrue(description.contains("Nomes alternativos"))
        listOf("Standard of Reincarnation", "Hwansaengui Jeongseok", "O Padrão de Reencarnação").forEach {
            assertTrue("faltou $it", description.contains(it))
        }
        assertFalse("o separador | da API não deve vazar", description.contains(" | "))
    }

    @Test
    fun `listagem tem proxima pagina quando page menor que pages`() {
        val page = fixture("obras.json").parseAs<ObrasDto>()

        assertEquals(2, page.data.size)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `capitulo pago ganha cadeado e url sem ponto zero`() {
        val chapter = fixture("capitulos.json").parseAs<List<CapituloDto>>()[0]

        assertEquals("🔒 Capítulo 41", chapter.nomeExibido)
        assertEquals("$slug/capitulo-41", chapter.path(slug))
        assertTrue("data deve ser lida", chapter.dataUpload > 0)
    }

    @Test
    fun `capitulo gratis fica sem cadeado`() {
        val chapter = fixture("capitulos.json").parseAs<List<CapituloDto>>()[1]

        assertEquals("Capítulo 3", chapter.nomeExibido)
        assertEquals("$slug/capitulo-3", chapter.path(slug))
    }

    @Test
    fun `paginas saem ordenadas por ordem`() {
        val pages = fixture("capitulo_gratis.json").parseAs<CapituloPaginasDto>().toPages()

        assertEquals(
            listOf("79477ec6dab49534", "ff5530b042484372", "9b7a24538a54a816"),
            pages.map { it.imageUrl!!.substringAfterLast('/').substringBefore('.') },
        )
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
    }

    @Test
    fun `capitulo pago sem paginas vira lista vazia`() {
        assertTrue(fixture("capitulo_pago.json").parseAs<CapituloPaginasDto>().toPages().isEmpty())
    }

    private val api = "https://api.baozinho.com"

    @Test
    fun `url da listagem padrao nao manda filtros vazios`() {
        val url = obrasUrl(api, page = 1, orderBy = "populares_total")

        assertEquals("$api/obras?page=1&orderBy=populares_total", url.toString())
    }

    @Test
    fun `url combina busca e filtros`() {
        val url = obrasUrl(api, page = 2, orderBy = "rating", query = "murim", status = "em_lancamento", formato = "manhwa", tagIds = listOf(1))

        assertEquals("murim", url.queryParameter("q"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("rating", url.queryParameter("orderBy"))
        assertEquals("em_lancamento", url.queryParameter("status"))
        assertEquals("manhwa", url.queryParameter("formatoSlug"))
        assertEquals(listOf("1"), url.queryParameterValues("tagIds"))
    }

    @Test
    fun `varias tags viram parametros repetidos e nunca lista com virgula`() {
        val url = obrasUrl(api, page = 1, orderBy = "populares_total", tagIds = listOf(1, 28))

        // A API responde 500 para tagIds=1,28; só o formato repetido funciona (e combina por OU).
        assertEquals(listOf("1", "28"), url.queryParameterValues("tagIds"))
        assertFalse(url.toString().contains("%2C") || url.toString().contains(","))
    }

    @Test
    fun `busca em branco nao manda q`() {
        assertEquals(null, obrasUrl(api, page = 1, orderBy = "populares_total", query = "   ").queryParameter("q"))
    }

    @Test
    fun `status da API vira status do Mihon`() {
        assertEquals(SManga.ONGOING, statusDe("em_lancamento"))
        assertEquals(SManga.COMPLETED, statusDe("finalizado"))
        assertEquals(SManga.ON_HIATUS, statusDe("hiato"))
        assertEquals(SManga.CANCELLED, statusDe("cancelado"))
        assertEquals(SManga.UNKNOWN, statusDe(null))
    }

    @Test
    fun `ocultar pagos remove so os bloqueados`() {
        val chapters = fixture("capitulos.json").parseAs<List<CapituloDto>>()

        assertEquals(listOf("🔒 Capítulo 41", "Capítulo 3"), chapters.visiveis(ocultarPagos = false).map { it.nomeExibido })
        assertEquals(listOf("Capítulo 3"), chapters.visiveis(ocultarPagos = true).map { it.nomeExibido })
    }

    @Test
    fun `filtros descartam tags sem obras e mantem os formatos`() {
        val filtros = FiltrosDto(
            tags = fixture("tags.json").parseAs(),
            formatos = fixture("formatos.json").parseAs(),
        )

        val tags = filtros.tagsComObras()

        assertEquals(33, tags.size)
        assertTrue(tags.none { it.obrasCount == 0 })
        assertTrue(tags.any { it.nome == "Ação" && it.id == 1 })
        assertEquals(7, filtros.formatos.size)
    }
}
