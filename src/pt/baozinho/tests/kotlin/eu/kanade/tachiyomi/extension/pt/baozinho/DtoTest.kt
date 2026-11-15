package eu.kanade.tachiyomi.extension.pt.baozinho

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
}
