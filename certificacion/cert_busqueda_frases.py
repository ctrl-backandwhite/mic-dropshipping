#!/usr/bin/env python3
"""Certificación de la búsqueda por FRASE y por NOMBRE COMPLETO en los 8 idiomas.

Qué mide y por qué así
----------------------
Toma productos reales del catálogo, lee su título en cada idioma de la base de datos y busca ESE MISMO
TÍTULO. El producto tiene que salir, y salir el PRIMERO. Es la prueba que más se parece a lo que hace la
gente: copiar un nombre y pegarlo en el buscador.

Tres formas de la misma consulta, porque fallan por motivos distintos:
  1. título completo  → «he copiado el nombre entero»
  2. frase del medio  → el orden de las palabras cuenta (match_phrase)
  3. dos palabras     → no hace falta escribirlo entero

Va directo a OpenSearch replicando la consulta de `ProductSearchService.matching()` campo por campo y peso
por peso. Se hace así a propósito: lo que se mide es la RELEVANCIA del índice, y ninguna capa por encima
puede arreglar un motor que no coloca primero el producto cuyo nombre exacto se ha tecleado. Si se cambia
la consulta del servicio, hay que cambiarla aquí — y que haga falta es la señal de que este fichero está
midiendo lo que dice medir.

Uso:  python3 cert_busqueda_frases.py [nº de productos, por defecto 5]
"""
import json
import os
import subprocess
import sys
import urllib.request

# La versión va aparte porque el índice físico la lleva en el nombre: subir el esquema (analizadores,
# mapping) obliga a crear un índice nuevo. Si esto queda clavado a una versión vieja, la certificación
# mediría un índice que ya nadie consulta y saldría verde sin probar nada.
INDICE = os.environ.get("OS_INDEX", "products-" + os.environ.get("OS_INDEX_VERSION", "v3"))
OS_URL = os.environ.get("OS_URL", "http://localhost:9400") + "/" + INDICE + "/_search"
PG = {"host": os.environ.get("PGHOST", "localhost"), "port": os.environ.get("PGPORT", "5532"),
      "user": os.environ.get("PGUSER", "nexadrop"), "db": os.environ.get("PGDATABASE", "nexadrop"),
      "pass": os.environ.get("PGPASSWORD", "nexadrop")}

# Mismos idiomas que INDEXED_LANGS del servicio. El chino incluido: tiene campo propio (titleZh, cjk).
IDIOMAS = ["es", "en", "pt", "fr", "it", "de", "nl", "zh"]
MOST_TERMS = "2<75%"


def sql(q):
    r = subprocess.run(["psql", "-h", PG["host"], "-p", PG["port"], "-U", PG["user"], "-d", PG["db"],
                        "-X", "-q", "-t", "-A", "-c", q],
                       capture_output=True, text=True,
                       env={"PGPASSWORD": PG["pass"], "PATH": "/usr/bin:/bin"})
    if r.returncode != 0:
        print("ERROR SQL:", r.stderr[:300])
        sys.exit(2)
    return [l for l in r.stdout.strip().split("\n") if l]


def campo_titulo(lang):
    """Igual que normalizeLang() + capitalize() del servicio."""
    return "title" + lang.capitalize()


def buscar(consulta, lang, size=10):
    campo = campo_titulo(lang)
    cuerpo = {
        "size": size, "_source": [campo],
        "query": {"bool": {
            "must": [{"bool": {"minimum_should_match": 1, "should": [
                {"match_phrase": {campo: {"query": consulta, "boost": 10}}},
                {"match": {campo: {"query": consulta, "minimum_should_match": MOST_TERMS, "boost": 8}}},
                {"match": {"titleAll": {"query": consulta, "minimum_should_match": MOST_TERMS, "boost": 3}}},
                {"match": {"titleZh": {"query": consulta, "minimum_should_match": MOST_TERMS, "boost": 3}}},
                {"match": {"attrs": {"query": consulta, "minimum_should_match": MOST_TERMS, "boost": 2}}},
                {"match": {"variants": {"query": consulta, "minimum_should_match": MOST_TERMS, "boost": 1.5}}},
                {"match": {"categoryName": {"query": consulta, "minimum_should_match": MOST_TERMS, "boost": 1}}},
            ]}}],
            "filter": [{"term": {"status": "ACTIVE"}}, {"term": {"hasImage": True}}]}},
    }
    req = urllib.request.Request(OS_URL, data=json.dumps(cuerpo).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.load(r)
    return [h["_id"] for h in d["hits"]["hits"]], d["hits"]["total"]["value"]


def main():
    cuantos = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    filas = sql("""
        SELECT p.id::text || '|' || p.slug
        FROM product p
        WHERE p.status = 'ACTIVE'
          AND (SELECT count(DISTINCT language) FROM product_translation WHERE product_id = p.id) >= 8
          AND EXISTS (SELECT 1 FROM product_image i WHERE i.product_id = p.id AND i.cdn_url IS NOT NULL)
        ORDER BY p.created_at DESC LIMIT %d;""" % cuantos)
    if not filas:
        print("Sin productos traducidos a los 8 idiomas: no hay nada que certificar.")
        return 2

    total, fallos = 0, []
    for fila in filas:
        pid, slug = fila.split("|", 1)
        print("\n=== %s ===" % slug[:70])
        for lang in IDIOMAS:
            t = sql("SELECT title FROM product_translation WHERE product_id='%s' AND language='%s';"
                    % (pid, lang))
            if not t or not t[0].strip():
                continue
            titulo = t[0].strip()
            pal = titulo.split()
            casos = [("titulo completo", titulo)]
            if len(pal) >= 4:
                casos.append(("frase del medio", " ".join(pal[1:4])))
            if len(pal) >= 2:
                casos.append(("dos palabras", " ".join(pal[:2])))
            for nombre, consulta in casos:
                total += 1
                try:
                    ids, hits = buscar(consulta, lang)
                except Exception as e:  # noqa: BLE001 — un fallo de red es un caso fallido, no un aborto
                    fallos.append((slug, lang, nombre, consulta, "ERROR %s" % e))
                    print("  %-2s %-16s ERROR %s" % (lang, nombre, e))
                    continue
                if not ids:
                    fallos.append((slug, lang, nombre, consulta, "SIN RESULTADOS"))
                    print("  %-2s %-16s SIN RESULTADOS     «%s»" % (lang, nombre, consulta[:42]))
                elif ids[0] != pid:
                    pos = (ids.index(pid) + 1) if pid in ids else 0
                    det = "posicion %d de %d" % (pos, hits) if pos else "NO en top10 (%d hits)" % hits
                    fallos.append((slug, lang, nombre, consulta, det))
                    print("  %-2s %-16s %-20s «%s»" % (lang, nombre, det, consulta[:42]))
                else:
                    print("  %-2s %-16s OK 1.º (%d hits)" % (lang, nombre, hits))

    print("\n===== %d casos, %d fallos =====" % (total, len(fallos)))
    for f in fallos:
        print("  [%s] %-16s %-22s «%s»" % (f[1], f[2], f[4], f[3][:45]))
    return 1 if fallos else 0


if __name__ == "__main__":
    sys.exit(main())
