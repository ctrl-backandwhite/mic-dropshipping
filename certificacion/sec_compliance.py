#!/usr/bin/env python3
"""SEC-COMP — superficie de cumplimiento del Reglamento (UE) 2023/988.

Los endpoints de cumplimiento son un caso raro: publican A PROPÓSITO datos personales del titular del
negocio (nombre, dirección postal, correo), porque el art. 16.3 obliga a ello. Eso los convierte en una
superficie que hay que vigilar por partida doble:

  - lo público tiene que ser SOLO lo que la ley manda publicar, y nada más (ni teléfono interno, ni quién
    lo editó, ni el estado de completitud, que es información de gestión);
  - lo de gestión —estado del catálogo, listado de referencias incompletas, alta de advertencias— tiene
    que estar cerrado a quien no sea administrador, porque revela el tamaño del catálogo y sus huecos.

Un endpoint que publica datos personales por mandato legal es exactamente donde no conviene descubrir un
IDOR o un campo de más seis meses después.
"""
import json

import harness as h
from runner import Runner

A = json.load(open("actores.json"))
U1, OP, AD = A["u1"], A["operator"], A["admin"]
PROD = A.get("producto") or {}
SLUG = PROD.get("slug")

r = Runner("SEC-COMP")

PUBLICA = "/api/compliance/responsible-person"
ADMIN = "/api/admin/compliance"

# ── Lo público: accesible sin cuenta, pero sin contar de más ────────────────
# La norma quiere que el comprador pueda ver quién responde del producto, así que exigir cuenta lo
# incumpliría. 200 con datos o 204 si aún no hay operador publicable: cualquier otra cosa es un fallo.
res = h.call("GET", PUBLICA + "?lang=es")
r.case("SEC-COMP-01", "el operador económico es consultable sin cuenta (art. 16.3)",
       res.status in (200, 204), f"status={res.status}")

# Campos de GESTIÓN que no deben viajar al público. `complete` dice si al negocio le faltan datos y
# `enabled` si están decidiendo publicarlo: son estado interno, no información para el comprador.
if res.status == 200:
    cuerpo = res.body or ""
    filtrados = [c for c in ("updatedBy", "updatedAt", "id") if f'"{c}"' in cuerpo]
    r.case("SEC-COMP-02", "el bloque público no expone metadatos de gestión",
           not filtrados, f"campos filtrados={filtrados}")
else:
    r.skip("SEC-COMP-02", "el bloque público no expone metadatos de gestión",
           "no hay operador publicable todavía (204)")

# ── Lo de gestión: cerrado a quien no es admin ──────────────────────────────
for etiqueta, ruta in [("estado", f"{ADMIN}/status?lang=es"),
                       ("incompletos", f"{ADMIN}/products/missing-manufacturer?page=0&size=5&lang=es"),
                       ("operador", f"{ADMIN}/responsible-person?lang=es"),
                       ("figuras", f"{ADMIN}/operator-roles?lang=es")]:
    for quien, actor in [("anónimo", None), ("usuario", U1), ("operador", OP)]:
        s = h.call("GET", ruta, token=actor["token"] if actor else None).status
        esperado = (401,) if actor is None else (401, 403)
        r.case(f"SEC-COMP-03.{etiqueta}.{quien}",
               f"{etiqueta} de cumplimiento cerrado a {quien}",
               s in esperado, f"status={s}")

# El admin sí entra: si esto fallara, el panel no podría corregir nada y el caso anterior estaría
# midiendo un endpoint roto en lugar de uno protegido.
s = h.call("GET", f"{ADMIN}/status?lang=es", token=AD["token"]).status
r.case("SEC-COMP-04", "el admin sí consulta el estado de cumplimiento", s == 200, f"status={s}")

# ── Escritura: solo admin, y validando ──────────────────────────────────────
CUERPO = {"enabled": False, "name": "X", "addressLine": "Y", "postalCode": "50001", "city": "Z",
          "country": "ES", "email": "a@b.com", "role": "IMPORTER"}
for quien, actor in [("anónimo", None), ("usuario", U1), ("operador", OP)]:
    s = h.call("PUT", f"{ADMIN}/responsible-person?lang=es", token=actor["token"] if actor else None,
               body=CUERPO).status
    r.case(f"SEC-COMP-05.{quien}", f"{quien} no puede cambiar el operador económico",
           s in (401, 403), f"status={s}")

# Inyección en el país: el campo alimenta un bloque que se publica en la ficha y en la factura.
for valor in ["<script>alert(1)</script>", "ES'; DROP TABLE product;--", "ESPAÑA", "E", "ESP"]:
    malo = dict(CUERPO, country=valor)
    s = h.call("PUT", f"{ADMIN}/responsible-person?lang=es", token=AD["token"], body=malo).status
    r.case(f"SEC-COMP-06.{valor[:14]}", "un país que no es ISO-2 se rechaza",
           s == 400, f"status={s} valor={valor[:20]}")

# El código de advertencia acaba en una clave de negocio: no debe admitir cualquier cosa.
# La categoría se saca de la ficha del producto de los actores, no de un identificador fijo: así el caso
# sigue valiendo aunque cambie la taxonomía.
CAT = None
if SLUG:
    ficha = h.call("GET", f"/api/catalog/products/{SLUG}?lang=es", token=AD["token"])
    try:
        CAT = json.loads(ficha.body or "{}").get("categoryId")
    except ValueError:
        CAT = None
if CAT:
    for valor in ["'; DROP TABLE x;--", "../../etc", "código con espacios", "<b>x</b>"]:
        s = h.call("PUT", f"{ADMIN}/categories/{CAT}/warnings", token=AD["token"],
                   body={"code": valor, "texts": {"es": "texto"}}).status
        r.case(f"SEC-COMP-07.{valor[:12]}", "un código de advertencia hostil se rechaza",
               s == 400, f"status={s}")
else:
    r.skip("SEC-COMP-07", "un código de advertencia hostil se rechaza", "sin categoría en actores.json")

# ── El coste del proveedor no puede colarse por el bloque nuevo de la ficha ──
if SLUG:
    res = h.call("GET", f"/api/catalog/products/{SLUG}?lang=es", token=U1["token"])
    cuerpo = res.body or ""
    fugas = [c for c in ("costCny", "basePrice", "priceCny", "margin", "retailUsd") if f'"{c}":' in cuerpo
             and f'"{c}":null' not in cuerpo]
    r.case("SEC-COMP-08", "la ficha con bloque de cumplimiento sigue sin filtrar coste ni margen",
           not fugas, f"campos filtrados={fugas}")
else:
    r.skip("SEC-COMP-08", "la ficha no filtra coste", "sin producto en actores.json")

r.report("sec_compliance")
