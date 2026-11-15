"""Teste de contrato contra a API real do Baozinho (somente leitura, sem login).

Cada teste protege uma suposição que a extensão faz sobre a API. Se algum falhar, o site mudou:
ajuste a extensão (e as fixtures em src/pt/baozinho/tests/resources) antes de publicar.

Uso: python3 -m unittest -v contract_test
"""
import json
import math
import re
import time
import unittest
import urllib.error
import urllib.request

API = "https://api.baozinho.com"
SITE = "https://baozinho.com"
CDN = "https://cdn.baozinho.com"
UA = "nox-contract-test (+https://github.com/Jhoorodre/extensions-source)"

# Mesmos valores de SortFilter e StatusFilter na extensão.
ORDER_BY = {"recentes", "atualizadas", "populares_semana", "populares_total", "rating", "alfabetica_az", "alfabetica_za"}
STATUS = {"em_lancamento", "finalizado", "hiato", "cancelado"}


def request(url, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"User-Agent": UA, "Accept": "application/json"}
    if data:
        headers["Content-Type"] = "application/json"
    time.sleep(0.2)  # poucos pedidos, sem pressa: é só um teste de rotina
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def api(path, **kw):
    status, raw = request(API + path, **kw)
    try:
        return status, json.loads(raw)
    except ValueError:
        return status, raw.decode("utf-8", "replace")


def allowed_values(message_list, field):
    """Lê 'orderBy must be one of the following values: a, b, c' da validação da API."""
    text = next(m for m in message_list if m.startswith(f"{field} must be one of"))
    return set(text.split(":", 1)[1].replace(" ", "").split(","))


class ContratoApi(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        status, catalogo = api("/obras?page=1&orderBy=populares_total&limit=100")
        if status != 200:
            raise AssertionError(f"API inacessível ou mudou: GET /obras respondeu {status}")
        cls.catalogo = catalogo
        cls.obras = catalogo["data"]
        cls.tags = api("/tags")[1]
        cls.formatos = api("/formatos")[1]

    def test_listagem_tem_o_formato_esperado(self):
        c = self.catalogo
        for campo in ("data", "total", "page", "pages", "limit"):
            self.assertIn(campo, c)
        self.assertEqual(math.ceil(c["total"] / c["limit"]), c["pages"], "hasNextPage da extensão usa page < pages")
        self.assertTrue(self.obras, "catálogo vazio")
        for o in self.obras:
            for campo in ("id", "nome", "slug", "imagem", "status"):
                self.assertIn(campo, o, f"obra sem '{campo}'")

    def test_valores_de_orderby_nao_mudaram(self):
        status, d = api("/obras?orderBy=__invalido__")
        self.assertEqual(400, status)
        self.assertEqual(ORDER_BY, allowed_values(d["message"], "orderBy"))

    def test_valores_de_status_nao_mudaram(self):
        status, d = api("/obras?status=__invalido__")
        self.assertEqual(400, status)
        self.assertEqual(STATUS, allowed_values(d["message"], "status"))

    def test_parametros_de_filtro_continuam_aceitos(self):
        tag = self.tags[0]["id"]
        formato = self.formatos[0]["slug"]
        for query in ("q=a", "status=em_lancamento", f"formatoSlug={formato}", f"tagIds={tag}", "page=2&limit=5"):
            status, d = api(f"/obras?orderBy=populares_total&{query}")
            self.assertEqual(200, status, f"/obras?{query} -> {d}")

    def test_varias_tags_combinam_por_ou(self):
        # Se virasse E, a união (>= o maior grupo) viraria interseção (<= o menor).
        com_obras = sorted((t for t in self.tags if t["obrasCount"] > 0), key=lambda t: t["obrasCount"])
        menor, maior = com_obras[0], com_obras[-1]
        self.assertLess(menor["obrasCount"], maior["obrasCount"], "preciso de duas tags com tamanhos diferentes")
        _, d = api(f"/obras?orderBy=populares_total&tagIds={menor['id']}&tagIds={maior['id']}&limit=100")
        self.assertGreaterEqual(d["total"], maior["obrasCount"])

    def test_tags_e_formatos_tem_os_campos_usados(self):
        for t in self.tags:
            for campo in ("id", "nome", "obrasCount"):
                self.assertIn(campo, t)
        for f in self.formatos:
            for campo in ("id", "nome", "slug"):
                self.assertIn(campo, f)

    def test_detalhe_da_obra_tem_os_campos_usados(self):
        _, d = api("/obras/slug/" + self.obras[0]["slug"])
        for campo in ("id", "nome", "slug", "imagem", "descricao", "titulosAlternativos", "status", "formato", "tags", "_count"):
            self.assertIn(campo, d, f"detalhe sem '{campo}'")
        self.assertIsInstance(d["_count"]["capitulos"], int)
        self.assertTrue(d["formato"] is None or {"nome", "slug"} <= set(d["formato"]))
        self.assertTrue(d["autor"] is None or "nome" in d["autor"], "autor deve ser objeto com 'nome' ou null")
        self.assertTrue(d["arte"] is None or isinstance(d["arte"], str), "arte deve ser texto ou null")
        self.assertTrue(all("nome" in t for t in d["tags"]))

    def test_lista_de_capitulos_bate_com_a_contagem(self):
        for o in self.obras[:3]:
            _, detalhe = api("/obras/slug/" + o["slug"])
            _, caps = api(f"/capitulos/obra/{o['id']}")
            self.assertIsInstance(caps, list)
            self.assertEqual(detalhe["_count"]["capitulos"], len(caps), f"capítulos de {o['slug']}")
            for c in caps:
                for campo in ("id", "numero", "nome", "lancadoEm", "gratuito"):
                    self.assertIn(campo, c)
                self.assertIsInstance(c["numero"], (int, float))
                self.assertIsInstance(c["gratuito"], bool)

    def _capitulos(self):
        for o in self.obras:
            for c in api(f"/capitulos/obra/{o['id']}")[1]:
                yield o, c

    def test_capitulo_gratis_traz_paginas_ordenaveis(self):
        _, c = next((o, c) for o, c in self._capitulos() if c["gratuito"])
        _, d = api(f"/capitulos/{c['id']}")
        self.assertEqual(c["paginasCount"], len(d["paginas"]))
        for p in d["paginas"]:
            self.assertTrue(p["url"].startswith("https://"))
            self.assertIsInstance(p["ordem"], int)

    def test_capitulo_pago_anonimo_nao_traz_paginas(self):
        pago = next((c for _, c in self._capitulos() if not c["gratuito"]), None)
        if pago is None:
            self.skipTest("nenhum capítulo pago no catálogo agora")
        _, d = api(f"/capitulos/{pago['id']}")
        self.assertFalse(d.get("paginas"), "a extensão assume lista vazia para capítulo pago sem login")

    def test_capa_do_cdn_abre(self):
        status, _ = request(f"{CDN}/{self.obras[0]['imagem']}")
        self.assertEqual(200, status)

    def test_endpoints_autenticados_exigem_login(self):
        self.assertEqual(401, api(f"/capitulos/obra/{self.obras[0]['id']}/desbloqueados")[0])
        self.assertEqual(401, api("/auth/me")[0])

    def test_refresh_invalido_e_rejeitado_de_forma_reconhecivel(self):
        status, d = api("/auth/refresh", method="POST", body={"refreshToken": "token-invalido-de-teste"})
        corpo = json.dumps(d).lower() if not isinstance(d, str) else d.lower()
        # Mesma regra de sessaoRejeitada no Dto.kt: 401/403, ou 400 que cita o token.
        self.assertTrue(status in (401, 403) or (status == 400 and "token" in corpo), f"{status} {corpo[:120]}")

    def test_paginas_do_site_abertas_no_webview_existem(self):
        slug = self.obras[0]["slug"]
        self.assertEqual(200, request(f"{SITE}/obras/{slug}")[0])
        _, caps = api(f"/capitulos/obra/{self.obras[0]['id']}")
        numero = re.sub(r"\.0$", "", str(caps[-1]["numero"]))
        self.assertEqual(200, request(f"{SITE}/obras/{slug}/capitulo-{numero}")[0])


if __name__ == "__main__":
    unittest.main()
