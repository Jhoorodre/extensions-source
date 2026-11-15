package eu.kanade.tachiyomi.extension.pt.baozinho

import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
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

        assertEquals("🔒 Capítulo 41", chapter.nomeExibido())
        assertEquals("$slug/capitulo-41", chapter.path(slug))
        assertTrue("data deve ser lida", chapter.dataUpload > 0)
    }

    @Test
    fun `capitulo gratis fica sem cadeado`() {
        val chapter = fixture("capitulos.json").parseAs<List<CapituloDto>>()[1]

        assertEquals("Capítulo 3", chapter.nomeExibido())
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

        assertEquals(listOf("🔒 Capítulo 41", "Capítulo 3"), chapters.visiveis(ocultarPagos = false).map { it.nomeExibido() })
        assertEquals(listOf("Capítulo 3"), chapters.visiveis(ocultarPagos = true).map { it.nomeExibido() })
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

    @Test
    fun `capitulo pago desbloqueado pela conta nao ganha cadeado`() {
        val pago = fixture("capitulos.json").parseAs<List<CapituloDto>>()[0]

        assertEquals("🔒 Capítulo 41", pago.nomeExibido())
        assertEquals("Capítulo 41", pago.nomeExibido(desbloqueados = setOf(2043)))
        assertTrue(pago.bloqueadoPara(setOf(1, 2)))
        assertFalse(pago.bloqueadoPara(setOf(2043)))
    }

    @Test
    fun `ocultar pagos respeita os desbloqueados da conta`() {
        val chapters = fixture("capitulos.json").parseAs<List<CapituloDto>>()

        assertEquals(2, chapters.visiveis(ocultarPagos = true, desbloqueados = setOf(2043)).size)
    }

    @Test
    fun `se nao der para saber os desbloqueados nada e escondido`() {
        val chapters = fixture("capitulos.json").parseAs<List<CapituloDto>>()

        // Logado mas /desbloqueados falhou: melhor mostrar de mais do que esconder o que a conta tem.
        assertEquals(2, chapters.visiveis(ocultarPagos = true, desbloqueados = null).size)
    }

    // desbloqueados.json foi inferido do JS do site (capId, expiraEm), não capturado de uma sessão real.
    @Test
    fun `lista de desbloqueados vira conjunto de ids`() {
        val ids = fixture("desbloqueados.json").parseAs<List<DesbloqueadoDto>>().map { it.capId }.toSet()

        assertEquals(setOf(2043, 9999), ids)
    }

    @Test
    fun `tokens e usuario sao lidos ignorando campos extras`() {
        val tokens = """{"accessToken":"a","refreshToken":"r","user":{"id":1}}""".parseAs<TokensDto>()
        val usuario = """{"id":7,"nome":"Fulano","email":"x@y.z"}""".parseAs<UsuarioDto>()

        assertEquals("a" to "r", tokens.accessToken to tokens.refreshToken)
        assertEquals("Fulano", usuario.nome)
    }

    @Test
    fun `corpo do refresh so leva o refreshToken`() {
        assertEquals("""{"refreshToken":"tok"}""", RefreshRequest("tok").toJsonString())
    }

    @Test
    fun `so rejeicao clara de autenticacao derruba a sessao`() {
        assertTrue(sessaoRejeitada(401, null))
        assertTrue(sessaoRejeitada(403, "Forbidden"))
        assertTrue(sessaoRejeitada(400, "Refresh token inválido"))
        // Queda de rede, erro do servidor e limite de requisições não podem deslogar o usuário.
        assertFalse(sessaoRejeitada(400, "property x should not exist"))
        assertFalse(sessaoRejeitada(429, "Too many requests"))
        assertFalse(sessaoRejeitada(500, "Internal server error"))
        assertFalse(sessaoRejeitada(502, null))
        assertFalse(sessaoRejeitada(200, null))
    }

    @Test
    fun `corpo do login leva e-mail, senha e o token do turnstile`() {
        assertEquals(
            """{"email":"a@b.c","senha":"s3nha","turnstileToken":"tk"}""",
            LoginRequest("a@b.c", "s3nha", "tk").toJsonString(),
        )
    }

    @Test
    fun `pagina do turnstile leva a sitekey e expoe o token`() {
        val html = turnstileHtml("SITEKEY-123")

        assertTrue(html.contains("sitekey: 'SITEKEY-123'"))
        assertTrue(html.contains("https://challenges.cloudflare.com/turnstile/v0/api.js"))
        assertTrue(html.contains("window.__tk = t"))
    }

    @Test
    fun `falha de login e classificada para o usuario saber o que corrigir`() {
        assertEquals(FalhaLogin.CREDENCIAIS, classificarFalhaLogin(401, "Credenciais inválidas"))
        assertEquals(FalhaLogin.CREDENCIAIS, classificarFalhaLogin(400, null))
        assertEquals(FalhaLogin.VERIFICACAO, classificarFalhaLogin(400, "Falha na verificação do Turnstile"))
        assertEquals(FalhaLogin.VERIFICACAO, classificarFalhaLogin(403, "captcha inválido"))
        assertEquals(FalhaLogin.OUTRA, classificarFalhaLogin(500, "Internal server error"))
        assertEquals(FalhaLogin.OUTRA, classificarFalhaLogin(429, "Too many requests"))
    }

    @Test
    fun `login automatico so uma vez por sessao do app e so com credenciais`() {
        assertTrue(deveLogarSozinho(temCredenciais = true, jaTentou = false))
        assertFalse(deveLogarSozinho(temCredenciais = true, jaTentou = true))
        assertFalse(deveLogarSozinho(temCredenciais = false, jaTentou = false))
    }

    @Test
    fun `resumo da senha nunca mostra o valor`() {
        assertEquals("Não definida", resumoSenha(""))
        assertFalse(resumoSenha("s3nha").contains("s3nha"))
    }
}
