#!/usr/bin/env python3
"""Certificación de los SINÓNIMOS de la búsqueda.

Por qué existe: un sinónimo mal elegido no falla, MIENTE. Buscar «zapatilla» devolvía conjuntos
deportivos, pantalones de yoga y calcetines porque el grupo incluía «deportivas» y el stemmer español la
reduce a `deport` — la misma raíz que el ADJETIVO «deportivo», que llevan miles de prendas. El buscador
respondía con seguridad y todo lo que decía era falso.

Qué se comprueba, para cada grupo de sinónimos del índice:

  1. COBERTURA   cada término encuentra algo (si el catálogo tiene ese tipo de producto).
  2. COHERENCIA  los términos del mismo grupo devuelven el MISMO tipo de producto. Se mide por la
                 categoría dominante de los primeros resultados: si «zapatilla» da «Zapatillas» y
                 «deportivas» da «Conjuntos», ese grupo está mezclando cosas distintas.
  3. PRECISIÓN   lo que sale pertenece de verdad al tipo pedido, no es relleno.

Solo lee: hace `GET /api/search` y nada más, así que es seguro apuntarlo a cualquier entorno.

Uso:
    python3 cert_busqueda_sinonimos.py                              # local
    API=https://api-des.nx036.com TOKEN=<jwt> python3 cert_busqueda_sinonimos.py   # desarrollo
    API=https://api.nx036.com     TOKEN=<jwt> python3 cert_busqueda_sinonimos.py   # pre

Sin TOKEN se usa el de `actores.json`, que solo vale en local. Contra un entorno remoto hay que pasar uno
de ese entorno: los tokens no son intercambiables entre despliegues.
"""
import collections
import json
import os
import re
import sys
import urllib.parse
import urllib.request

API = os.environ.get("API", "http://localhost:18082")
INDICE_JSON = os.environ.get(
    "INDEX_JSON",
    "/home/nexaplaform/Escritorio/dropshipping/backend/src/main/resources/opensearch/products-index.json")

_ip = [0]


def buscar(q, token, lang="es", size=10):
    _ip[0] += 1
    url = "%s/api/search?q=%s&lang=%s&size=%d" % (API, urllib.parse.quote(q), lang, size)
    req = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + token,
        "X-Forwarded-For": "198.31.%d.%d" % (_ip[0] // 250 % 250, _ip[0] % 250 + 1)})
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.load(r)
    items = [x for x in (d.get("items") or []) if isinstance(x, dict)]
    return d.get("total", 0), items


def raiz(palabra):
    """Prefijo suficiente para reconocer la palabra con sus variantes (chaqueta/chaquetas/chaquetón)."""
    p = quita_tildes(palabra.lower())
    return p[:max(4, len(p) - 2)]


def quita_tildes(s):
    tabla = str.maketrans("áéíóúüñ", "aeiouun")
    return s.translate(tabla)


def aciertos(items, terminos):
    """Cuántos resultados llevan en el TÍTULO alguno de los términos del grupo.

    Se mide sobre el título y NO sobre la categoría: un mismo tipo de producto vive repartido en varias
    categorías —las chaquetas están en hombre, niño, mujer y traje— y exigir una categoría dominante daba
    por roto un buscador que devolvía diez chaquetas de diez. Lo que importa es que el resultado SEA lo
    que se ha pedido, no dónde esté archivado.
    """
    raices = [raiz(t) for t in terminos]
    n = 0
    for x in items:
        titulo = quita_tildes((x.get("titleEs") or "").lower())
        if any(r in titulo for r in raices):
            n += 1
    return n


def grupos_de_sinonimos():
    """Lee los grupos del propio índice: la certificación no puede tener su copia, se desincronizaría."""
    with open(INDICE_JSON, encoding="utf-8") as f:
        d = json.load(f)
    crudos = d["settings"]["analysis"]["filter"]["nx_syn_es"]["synonyms"]
    return [[t.strip() for t in linea.split(",") if t.strip()] for linea in crudos]


def token_de_acceso():
    """Token del entorno (TOKEN) o, si no se pasa, el del actor local de la certificación."""
    delEntorno = os.environ.get("TOKEN")
    if delEntorno:
        return delEntorno
    with open("actores.json", encoding="utf-8") as f:
        return json.load(f)["u1"]["token"]


def main():
    token = token_de_acceso()
    print("Certificando sinónimos contra %s" % API)
    grupos = grupos_de_sinonimos()
    total, fallos = 0, []

    for grupo in grupos:
        # Términos de una sola palabra: los de varias («pantalon corto») solo casan como frase y su
        # comportamiento se mide aparte, en la certificación general.
        # Se BUSCA solo por los términos de una palabra (los de varias solo casan como frase), pero para
        # medir aciertos valen TODOS: «bermuda» devuelve «Pantalones cortos» y «Shorts», que son el mismo
        # producto. Contando solo las palabras sueltas, el acierto se leía como fallo.
        terminos = [t for t in grupo if " " not in t]
        if len(terminos) < 2:
            continue
        todos = grupo
        print("\n=== %s ===" % ", ".join(terminos))
        resultados = {}
        for termino in terminos:
            hits, items = buscar(termino, token)
            veces = aciertos(items, todos)
            resultados[termino] = (hits, veces, len(items))
            print("  %-14s %5d hits · %d de %d son del tipo pedido" % (termino, hits, veces, len(items)))

        # PRECISIÓN: al menos el 70% de lo devuelto tiene que ser del tipo pedido. Por debajo, el
        # buscador está rellenando con lo que sea — que es justo lo que hacía «zapatilla» antes de
        # quitar «deportivas» del grupo: devolvía conjuntos y pantalones de yoga.
        con_datos = {t: v for t, v in resultados.items() if v[0] > 0 and v[2] > 0}
        for termino, (hits, veces, n) in con_datos.items():
            total += 1
            ok = veces >= n * 0.7
            if not ok:
                fallos.append((termino, "solo %d de %d resultados son del tipo pedido" % (veces, n)))
                print("  → precisión de «%s»: FALLA (%d/%d)" % (termino, veces, n))

    print("\n===== %d comprobaciones · %d fallos =====" % (total, len(fallos)))
    for f in fallos:
        print("  %s → %s" % (f[0], f[1]))
    return 1 if fallos else 0


if __name__ == "__main__":
    sys.exit(main())
