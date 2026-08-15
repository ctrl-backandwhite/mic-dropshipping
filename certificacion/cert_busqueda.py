#!/usr/bin/env python3
"""Certificación de la búsqueda multiidioma del escaparate.

Para cada escenario: se lanza la búsqueda real contra /api/catalog/products y se comprueba que
(a) hay resultados y (b) los N primeros contienen alguna de las palabras esperadas en el título.
Lo que no cumple (b) se marca como posible falso positivo, con el título completo para juzgarlo.
"""
import json
import sys
import unicodedata
import urllib.parse
import urllib.request

API = "http://localhost:18082/api/catalog/products"
TOK = open(sys.argv[1]).read().strip()
TOP = 10

# (término, idioma, palabras que DEBEN aparecer en el título de un resultado correcto, mínimo esperado)
CASES = [
    ("botas",            "es", ["bota", "botin", "botines"],                      5),
    ("camisas",          "es", ["camisa", "blusa"],                               5),
    ("camisa",           "es", ["camisa", "blusa"],                               5),
    ("shorts",           "es", ["short", "pantalon corto", "bermuda"],            3),
    ("ropa intima",      "es", ["interior", "intima", "lenceria", "sujetador",
                                "braga", "calzon", "panti", "bragui", "body"],    3),
    ("vestidos",         "es", ["vestido"],                                       5),
    ("zapatillas",       "es", ["zapatilla", "deportiva", "sneaker", "tenis"],    3),
    ("chaqueta",         "es", ["chaqueta", "campera", "casaca", "blazer"],       5),
    ("bolso",            "es", ["bolso", "cartera", "bandolera", "mochila"],      5),
    ("pantalones vaqueros", "es", ["vaquero", "jean", "pantalon", "denim"],       5),
    ("sudadera",         "es", ["sudadera", "hoodie", "buzo"],                    3),
    ("calcetines",       "es", ["calcetin", "media"],                             3),
    ("abrigo",           "es", ["abrigo", "chaqueton", "tapado", "parka"],        5),
    ("falda",            "es", ["falda", "pollera"],                              5),
    # ── multiidioma / morfología ───────────────────────────────────────────
    ("cana alta",        "es", ["cana", "caña"],                                  3),  # sin acento
    ("japones",          "es", ["japon"],                                         2),  # sin acento
    ("boots",            "es", ["boot", "bota"],                                  2),  # término inglés en front ES
    ("dress",            "en", ["dress", "vestido"],                              3),
    ("chemise",          "fr", ["chemise", "camisa"],                             2),
    ("凉鞋",              "zh", ["凉鞋", "sandalia"],                               2),
    ("botines",          "es", ["botin", "bota"],                                 3),
]


def norm(s):
    s = unicodedata.normalize("NFD", (s or "").lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


def search(q, lang):
    url = f"{API}?q={urllib.parse.quote(q)}&lang={lang}&size={TOP}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {TOK}"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


fails = 0
for q, lang, expected, minimum in CASES:
    try:
        data = search(q, lang)
    except Exception as e:  # noqa: BLE001
        print(f"FALLA  {q!r} ({lang}): error de petición {e}")
        fails += 1
        continue
    items = data.get("items", data.get("content", []))
    titles = [(it.get("title") or "") for it in items]
    exp = [norm(e) for e in expected]
    good = [t for t in titles if any(e in norm(t) for e in exp)]
    bad = [t for t in titles if not any(e in norm(t) for e in exp)]
    ok = len(items) >= minimum and not bad
    status = "OK   " if ok else ("PARCIAL" if good else "FALLA")
    if not ok:
        fails += 1
    print(f"{status}  {q!r} ({lang}): {len(items)} resultados, {len(good)} correctos, {len(bad)} dudosos")
    for t in bad:
        print(f"         ⚠ {t[:78]}")

print()
print(f"{'TODO CORRECTO' if fails == 0 else str(fails) + ' escenario(s) con hallazgos'} — {len(CASES)} escenarios")
sys.exit(1 if fails else 0)
