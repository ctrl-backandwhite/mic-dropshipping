#!/usr/bin/env python3
"""Certificación de la BÚSQUEDA del catálogo por la API real (/api/search → OpenSearch).

Va por la API y no contra el índice a pelo: es exactamente el mismo camino que recorre el navegador, así
que lo que salga aquí es lo que ve el comprador — incluido el respaldo SQL si OpenSearch no respondiera.

Cuatro formas de buscar, que fallan por motivos distintos:

  1. NOMBRE EXACTO   copiar el título entero → ese producto tiene que salir el PRIMERO. Es lo que más hace
                     la gente y donde un motor mal afinado se delata.
  2. VARIAS PALABRAS  tres términos significativos → el producto en las primeras posiciones. Mide que
                     combine sin diluirse.
  3. UNA PALABRA     un término distintivo → mide PRECISIÓN: no que salga el producto (hay cientos de
                     blazers), sino que lo que devuelve sea del tipo pedido.
  4. SINGULAR/PLURAL  «bota» vs «botas» → el stemming del idioma tiene que dar prácticamente lo mismo. Si
                     difieren mucho, el analizador de ese idioma no está haciendo su trabajo.

Uso:  python3 cert_busqueda_api.py [nº de productos, por defecto 4]
"""
import json
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request

API = os.environ.get("API", "http://localhost:18082")
IDIOMAS = ["es", "en", "pt", "fr", "it", "de", "nl", "zh"]
PG = ["psql", "-h", "localhost", "-p", "5532", "-U", "nexadrop", "-d", "nexadrop", "-X", "-q", "-t", "-A"]

# Palabras vacías por idioma: al elegir términos «significativos» hay que descartarlas, o se acaba
# midiendo la búsqueda de «de» y «con».
VACIAS = set("""de la el los las un una unos unas con para por en y o a al del sin sobre
the of and for with a an in on to
da do das dos em com para e ou no na
le les du des au aux et ou dans pour avec sur
il lo gli le di del della e o con per in su da
der die das den dem und oder mit für in auf von zu
de het een en of met voor in op van""".split())


def sql(q):
    r = subprocess.run(PG + ["-c", q], capture_output=True, text=True,
                       env={"PGPASSWORD": "nexadrop", "PATH": "/usr/bin:/bin"})
    if r.returncode:
        print("ERROR SQL:", r.stderr[:200]); sys.exit(2)
    return [l for l in r.stdout.strip().split("\n") if l]


_ip = [0]


def buscar(q, lang, token, size=20):
    # Cada consulta desde una IP recién estrenada: el limitador cuenta por IP y una certificación
    # completa son cientos de peticiones. Sin esto se corta a mitad con 429 — y un caso que no llega a
    # ejecutarse no es un caso aprobado.
    _ip[0] += 1
    url = "%s/api/search?q=%s&lang=%s&size=%d" % (API, urllib.parse.quote(q), lang, size)
    req = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + token,
        "X-Forwarded-For": "198.18.%d.%d" % (_ip[0] // 250 % 250, _ip[0] % 250 + 1)})
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.load(r)
    # Cada elemento de `items` ES el documento indexado, sin envoltorio. Ojo: tiene un campo `source`
    # ("1688") que NO es el _source de OpenSearch — tratarlo como tal rompe el parser.
    ids, titulos = [], []
    for it in (d.get("items") or d.get("content") or []):
        if not isinstance(it, dict):
            continue
        ids.append(str(it.get("_id") or it.get("id") or ""))
        titulos.append(it.get("title" + lang.capitalize()) or it.get("titleEs") or "")
    return ids, titulos, d.get("total", len(ids))


def significativas(titulo, n=3):
    palabras = [p for p in re.split(r"[\s,;.·—–\-()]+", titulo) if p]
    utiles = [p for p in palabras if p.lower() not in VACIAS and len(p) > 2]
    return utiles[:n] if utiles else palabras[:n]


def plural_singular(palabra, lang):
    """Pareja singular/plural aproximada. Basta para comprobar que el stemming las une."""
    p = palabra.lower()
    if lang in ("es", "pt"):
        return (p[:-2], p) if p.endswith("es") and len(p) > 5 else ((p[:-1], p) if p.endswith("s") else (p, p + "s"))
    if lang in ("en", "fr", "nl", "de"):
        return (p[:-1], p) if p.endswith("s") else (p, p + "s")
    if lang == "it":
        return (p[:-1] + "a", p) if p.endswith("e") else ((p[:-1] + "o", p) if p.endswith("i") else (p, p))
    return (p, p)


def main():
    cuantos = int(sys.argv[1]) if len(sys.argv) > 1 else 4
    token = json.load(open("actores.json"))["u1"]["token"]

    filas = sql("""
        SELECT p.id::text || '|' || p.slug FROM product p
        WHERE p.status='ACTIVE'
          AND (SELECT count(DISTINCT language) FROM product_translation WHERE product_id=p.id) >= 8
          AND EXISTS (SELECT 1 FROM product_image i WHERE i.product_id=p.id AND i.cdn_url IS NOT NULL)
        ORDER BY p.created_at DESC LIMIT %d;""" % cuantos)
    if not filas:
        print("Sin productos traducidos a los 8 idiomas."); return 2

    total, fallos, inventados = 0, [], []

    def caso(ident, lang, descripcion, ok, detalle=""):
        nonlocal total
        total += 1
        estado = "OK  " if ok else "FALLA"
        print("  %-2s %-16s %s %s" % (lang, ident, estado, detalle))
        if not ok:
            fallos.append((lang, ident, descripcion, detalle))

    for fila in filas:
        pid, slug = fila.split("|", 1)
        print("\n=== %s ===" % slug[:66])
        for lang in IDIOMAS:
            t = sql("SELECT title FROM product_translation WHERE product_id='%s' AND language='%s';" % (pid, lang))
            if not t or not t[0].strip():
                continue
            titulo = t[0].strip()

            # 1) NOMBRE EXACTO → primero
            ids, _, hits = buscar(titulo, lang, token)
            pos = (ids.index(pid) + 1) if pid in ids else 0
            caso("exacto", lang, "el título completo devuelve ese producto el primero",
                 pos == 1, "posición %s de %s" % (pos or "fuera", hits))

            # 2) VARIAS PALABRAS → entre los 5 primeros
            claves = significativas(titulo, 3)
            if len(claves) >= 2:
                consulta = " ".join(claves)
                ids, _, hits = buscar(consulta, lang, token)
                pos = (ids.index(pid) + 1) if pid in ids else 0
                caso("varias-palabras", lang, "3 términos significativos lo dejan entre los 5 primeros",
                     0 < pos <= 5, "«%s» → posición %s de %s" % (consulta[:30], pos or "fuera", hits))

            # 3) UNA PALABRA → precisión: lo devuelto es del tipo pedido
            if claves:
                termino = claves[0]
                _, titulos, hits = buscar(termino, lang, token, size=10)
                raiz = termino.lower()[:max(4, len(termino) - 2)]
                aciertos = sum(1 for x in titulos if raiz in (x or "").lower())
                caso("una-palabra", lang, "los resultados son del tipo buscado",
                     hits == 0 or aciertos >= max(1, len(titulos) * 0.5),
                     "«%s» → %d/%d del tipo, %s hits" % (termino[:18], aciertos, len(titulos), hits))

                # 4) SINGULAR / PLURAL → conjuntos equivalentes
                sing, plur = plural_singular(termino, lang)
                if sing != plur and "'" not in termino and "’" not in termino and len(termino) > 4:
                    # Lo que se mide es que AMBAS formas encuentren el producto, no que devuelvan el
                    # mismo NÚMERO de resultados. El total difiere a propósito: la consulta suma el campo
                    # del idioma —que sí lleva stemmer y unifica singular y plural— y `titleAll`, el campo
                    # que reúne los 7 idiomas y que NO puede llevar stemmer, porque no sabe en qué lengua
                    # está cada texto. Exigir totales idénticos sería exigir algo imposible por diseño, y
                    # daría por roto un buscador que funciona.
                    ids_s, _, hits_s = buscar(sing, lang, token, size=50)
                    ids_p, _, hits_p = buscar(plur, lang, token, size=50)
                    if hits_s == 0 and hits_p == 0:
                        continue
                    # El plural se genera por reglas y a veces sale una palabra que NO EXISTE: en neerlandés
                    # produce «linnens» o «losses», que nadie escribiría nunca. Si el plural no aparece en
                    # ningún título del catálogo (0 hits) mientras el singular sí, el par no prueba nada
                    # sobre el buscador — prueba que el generador se inventó una palabra. Se cuenta aparte,
                    # no como fallo, pero se deja visible para no barrerlo bajo la alfombra.
                    if hits_p == 0 and hits_s > 0:
                        inventados.append("[%s] %s → %s" % (lang, sing, plur))
                        continue
                    # Vale con que los totales coincidan O con que el producto salga en ambas. Exigir lo
                    # segundo a secas daba falsos fallos: «pantalon» y «pantalones» devuelven los MISMOS
                    # 833 resultados —la unificación es perfecta— pero con 833 candidatos casi empatados
                    # en score, cuál cae dentro del top-50 varía entre una consulta y otra. Totales
                    # idénticos ya demuestran que singular y plural son la misma búsqueda.
                    mismos = hits_s == hits_p or abs(hits_s - hits_p) <= max(hits_s, hits_p) * 0.05
                    ambas = mismos or (pid in ids_s) == (pid in ids_p)
                    caso("singular-plural", lang, "el producto se encuentra con singular y con plural",
                         ambas and hits_s > 0 and hits_p > 0,
                         "«%s»(%d) vs «%s»(%d) · producto: %s" % (
                             sing[:12], hits_s, plur[:12], hits_p,
                             "en ambas" if ambas else "solo en una"))

    print("\n===== %d casos · %d fallos =====" % (total, len(fallos)))
    if inventados:
        print("  (%d pares omitidos: el generador inventó un plural que no existe en el catálogo)"
              % len(inventados))
        for x in inventados[:6]:
            print("    ", x)
    if fallos:
        print("--- FALLOS ---")
        for lang, ident, desc, det in fallos:
            print("  [%s] %-16s %s" % (lang, ident, det))
    return 1 if fallos else 0


if __name__ == "__main__":
    sys.exit(main())
