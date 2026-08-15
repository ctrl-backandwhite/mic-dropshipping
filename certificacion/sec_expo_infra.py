#!/usr/bin/env python3
"""Fase 1 — SEC-EXPO (qué se filtra en las respuestas) y SEC-INFRA (cabeceras, CORS, límites, webhooks).

Los dos bloques comparten script porque comparten naturaleza: no atacan la lógica, sino lo que el sistema
cuenta de más y lo que deja pasar en el borde.
"""
import json
import os
import urllib.parse

import harness as h
from runner import Runner

A = json.load(open("actores.json"))
U1, U2, OP, AD = A["u1"], A["u2"], A["operator"], A["admin"]
PROD = A.get("producto") or {}
PID, SLUG = PROD.get("id"), PROD.get("slug")

r = Runner("SEC-EXPO / SEC-INFRA")

# El limitador cuenta por IP y sus ventanas son LARGAS (el registro, una hora). Sin esto, la ráfaga que
# comprueba el propio límite —y cualquier ejecución anterior del guion— dejaba a los casos siguientes
# recibiendo 429 en vez de lo que medían. Cada caso que NO va sobre la cuota se lanza desde una IP recién
# inventada, así estrena cubo y mide lo que dice medir.
_ip = [0]


def desde_ip_nueva(extra=None):
    _ip[0] += 1
    cab = {"X-Forwarded-For": f"203.0.113.{_ip[0] % 250 + 1}"}
    if extra:
        cab.update(extra)
    return cab

# ── SEC-EXPO · Exposición de información ────────────────────────────────────
# El coste en CNY y el margen son el secreto comercial del negocio: si se filtran, cualquiera sabe lo que
# se gana por producto. Se buscan por NOMBRE DE CAMPO en el JSON crudo, no en un DTO ya mapeado.
FUGAS = ["costCny", "cost_cny", "priceCny", "margin", "marginPct", "margenCny", "supplierPrice",
         "basePriceCny", "costeCny"]

RESPUESTAS_USUARIO = [
    ("catálogo", f"/api/catalog/products?page=0&size=5&lang=es"),
    ("ficha", f"/api/catalog/products/{SLUG}?lang=es"),
    ("búsqueda", "/api/search?q=camisa&lang=es"),
    ("mis pedidos", "/api/me/orders"),
]
for nombre, ruta in RESPUESTAS_USUARIO:
    res = h.call("GET", ruta, token=U1["token"])
    cuerpo = res.body or ""
    filtrados = [c for c in FUGAS if f'"{c}"' in cuerpo]
    r.case(f"SEC-EXPO-01.{nombre}", f"el coste/margen no viaja en {nombre}",
           not filtrados, f"campos filtrados={filtrados}")

for nombre, ruta in [("listado", "/api/catalog/products?page=0&size=1&lang=es"),
                     ("búsqueda", "/api/search?q=a&lang=es")]:
    s = h.call("GET", ruta, headers=desde_ip_nueva()).status
    r.case(f"SEC-EXPO-02.{nombre}", f"el {nombre} no se puede enumerar sin cuenta",
           s == 401, f"status={s}")

# PII ajena: el pedido de otro no debe aparecer ni siquiera parcialmente en respuestas propias.
res = h.call("GET", "/api/me/orders", token=U2["token"])
r.case("SEC-EXPO-03", "los pedidos de otro usuario no aparecen en los propios",
       U1["email"] not in (res.body or ""), "email de user1 encontrado en la respuesta de user2")

# Errores: nunca traza ni SQL. Se provoca un error real y se mira lo que devuelve.
res = h.call("GET", "/api/me/orders/no-es-uuid", token=U1["token"])
cuerpo = (res.body or "").lower()
r.case("SEC-EXPO-04", "los errores no devuelven traza ni SQL",
       not any(t in cuerpo for t in ("exception", "at com.nexaplatform", "select ", "org.hibernate",
                                     "org.postgresql", "stacktrace")),
       f"status={res.status} body={(res.body or '')[:120]}")

# Endpoints de demo/mock: en un entorno real no deben existir. En local se comprueba que EXISTEN pero
# están cerrados a quien no es admin — que es la garantía que de verdad protege en producción.
for ruta in ["/api/admin/orders/demo", "/api/me/wallet/confirm-mock"]:
    s = h.call("POST", ruta, token=U1["token"], body={}).status
    r.case(f"SEC-EXPO-05{ruta.split('/')[-1]}", f"{ruta.split('/')[-1]} cerrado a un usuario normal",
           s in (401, 403, 400, 404), f"status={s}")

# Actuator: solo health. El resto revela configuración, beans, variables de entorno…
for expuesto, ruta in [(True, "/actuator/health"), (False, "/actuator/env"), (False, "/actuator/beans"),
                       (False, "/actuator/configprops"), (False, "/actuator/mappings")]:
    # SIN seguir la redirección: un 302 hacia /login ES un acceso denegado, pero si el cliente la sigue
    # acaba en la página de login con 200 y el caso parece un endpoint abierto de par en par.
    s = h.call("GET", ruta, seguir_redirecciones=False).status
    ok = (s == 200) if expuesto else (s in (301, 302, 401, 403, 404))
    r.case(f"SEC-EXPO-06{ruta.split('/')[-1]}",
           f"actuator/{ruta.split('/')[-1]} {'accesible' if expuesto else 'cerrado'}", ok, f"status={s}")

# Claves de API: al listarlas nunca debe volver el secreto completo.
res = h.call("GET", "/api/me/api-keys", token=U1["token"])
r.case("SEC-EXPO-07", "el secreto de la clave de API no se devuelve al listarla",
       "clientSecret" not in (res.body or "") or '"clientSecret":null' in (res.body or ""),
       f"body={(res.body or '')[:140]}")

# ── SEC-INFRA · Cabeceras, CORS, límites ────────────────────────────────────
res = h.call("GET", "/api/catalog/products?page=0&size=1&lang=es", token=U1["token"])
cab = {k.lower(): v for k, v in (res.headers or {}).items()}
for cabecera, esperado in [("x-content-type-options", "nosniff"),
                           ("x-frame-options", None),
                           ("content-security-policy", None),
                           ("referrer-policy", None)]:
    presente = cabecera in cab and (esperado is None or esperado in cab[cabecera].lower())
    r.case(f"SEC-INFRA-01.{cabecera}", f"cabecera {cabecera} presente", presente,
           f"valor={cab.get(cabecera)}")

res = h.call("GET", "/api/catalog/products?page=0&size=1&lang=es", token=U1["token"],
             headers={"Origin": "https://sitio-malicioso.example"})
acao = {k.lower(): v for k, v in (res.headers or {}).items()}.get("access-control-allow-origin")
r.case("SEC-INFRA-02", "un origen no autorizado no recibe permiso CORS",
       acao is None or "sitio-malicioso" not in acao, f"allow-origin={acao}")

# Métodos no permitidos: 405, nunca 500 ni un comportamiento inesperado.
for metodo in ["DELETE", "PUT", "PATCH"]:
    s = h.call(metodo, "/api/catalog/products?page=0&size=1&lang=es", token=U1["token"], body={},
               headers=desde_ip_nueva()).status
    r.case(f"SEC-INFRA-06.{metodo}", f"{metodo} sobre el catálogo devuelve 405",
           s in (404, 405), f"status={s}")

# Rate limit: la política del escaparate es "storefront.web" — 100 peticiones por minuto y por IP
# (RateLimitFilter.publicRule). Con 80 no se llegaba al tope y el caso fallaba sin que hubiera nada roto:
# medía por debajo del límite que decía comprobar. Se lanzan 120 desde una IP recién estrenada.
cab_rafaga = desde_ip_nueva()
codigos = [h.call("GET", "/api/catalog/products?page=0&size=1&lang=es", token=U1["token"],
                  headers=cab_rafaga).status for _ in range(120)]
r.case("SEC-INFRA-03", "el rate limit corta una ráfaga",
       429 in codigos, f"códigos={sorted(set(codigos))}")

# CAPTCHA obligatorio en los formularios públicos: sin la cabecera no deben pasar.
res = h.call("POST", "/api/auth/register", headers=desde_ip_nueva(), body={
    "email": f"sin-captcha-{urllib.parse.quote(SLUG or 'x')}@example.com", "password": os.environ.get("CERT_PASS", "CertPass123!"),
    "firstName": "Sin", "lastName": "Captcha", "acceptTerms": True, "country": "ES"})
r.case("SEC-INFRA-04", "el registro exige CAPTCHA", res.status in (400, 403, 428),
       f"status={res.status} body={(res.body or '')[:110]}")

# Webhooks: sin firma válida no se procesan.
for nombre, ruta in [("stripe", "/api/webhooks/stripe"), ("yunexpress", "/api/webhooks/yunexpress")]:
    s = h.call("POST", ruta, raw_body='{"evento":"falso"}', headers=desde_ip_nueva()).status
    # 503 cuando la pasarela está deshabilitada en el entorno: el evento NO se procesa, que es lo que
    # el caso comprueba. Lo que NO se admite es un 2xx, que significaría haberlo aceptado sin firma.
    r.case(f"SEC-INFRA-05.{nombre}", f"el webhook de {nombre} rechaza una firma ausente",
           s in (400, 401, 403, 503), f"status={s}")

r.report("sec_expo_infra")
