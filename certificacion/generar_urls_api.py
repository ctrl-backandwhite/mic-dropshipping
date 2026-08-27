#!/usr/bin/env python3
"""Genera las URLs semilla que ZAP debe atacar, leyéndolas del propio código.

Por qué no se usa la especificación OpenAPI: `/v3/api-docs` está detrás del servidor de autorización y
responde 302 hacia /login, así que ZAP no puede descargarla. Y sin semillas, su rastreador solo encuentra
las tres páginas públicas: el escaneo pasa en verde sin haber tocado la API.

Aquí se recorren las interfaces `*Api` y los controladores, se combinan el @RequestMapping de la clase con
el mapping de cada método, y se sustituyen los {id} por identificadores reales de los actores para que las
peticiones lleguen a la lógica en vez de morir en un 400 de conversión.
"""
import json
import os
import re
import sys

RAIZ = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "backend", "src", "main", "java")
SALIDA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "zap", "urls-api.txt")

CLASE = re.compile(r'@RequestMapping\("([^"]+)"\)')
METODO = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping\((?:value\s*=\s*)?"([^"]*)"\)')
SOLO = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping\s*$')


def rutas():
    encontradas = set()
    for base, _, ficheros in os.walk(RAIZ):
        for f in ficheros:
            if not f.endswith(".java"):
                continue
            ruta = os.path.join(base, f)
            texto = open(ruta, encoding="utf-8", errors="replace").read()
            if "@RequestMapping" not in texto and "Mapping(" not in texto:
                continue
            m = CLASE.search(texto)
            prefijo = m.group(1) if m else ""
            if not prefijo.startswith("/api"):
                continue
            encontradas.add(prefijo)
            for _, sufijo in METODO.findall(texto):
                if sufijo.startswith("/api"):
                    encontradas.add(sufijo)
                elif sufijo:
                    encontradas.add(prefijo.rstrip("/") + "/" + sufijo.lstrip("/"))
            for linea in texto.splitlines():
                if SOLO.search(linea.strip()):
                    encontradas.add(prefijo)
    return encontradas


def concretar(rutas_crudas):
    """Cambia los {parametros} por valores reales: una ruta con {id} sin resolver no prueba nada."""
    valores = {}
    try:
        actores = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "actores.json")))
        recursos = actores.get("recursos", {})
        producto = actores.get("producto") or {}
        valores = {
            "id": recursos.get("pedido") or producto.get("id") or "",
            "productId": producto.get("id") or "",
            "orderId": recursos.get("pedido") or "",
            "slug": producto.get("slug") or "",
        }
    except Exception:  # noqa: BLE001
        pass
    generico = valores.get("id") or "00000000-0000-0000-0000-000000000000"

    salida = set()
    for r in rutas_crudas:
        concreta = r
        for parametro in re.findall(r"\{(\w+)\}", r):
            concreta = concreta.replace("{" + parametro + "}", valores.get(parametro) or generico)
        if "{" in concreta:
            continue
        salida.add(concreta)
    return sorted(salida)


if __name__ == "__main__":
    base = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18082"
    finales = concretar(rutas())
    os.makedirs(os.path.dirname(SALIDA), exist_ok=True)
    with open(SALIDA, "w") as f:
        for r in finales:
            f.write(base + r + "\n")
    print(f"{len(finales)} URLs escritas en {SALIDA}")
