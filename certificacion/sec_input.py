#!/usr/bin/env python3
"""Fase 1 — SEC-INPUT: entradas hostiles.

La regla que se aplica en todo el bloque: **un 500 es un FALLO**, aunque la petición fuese absurda. Un
error no controlado es superficie: revela trazas, tumba hilos y suele significar que la entrada llegó más
adentro de lo que debía. Lo correcto es 400/404/415.
"""
import json

import harness as h
from runner import Runner

A = json.load(open("actores.json"))
U1 = A["u1"]
PROD = A.get("producto") or {}
PID, SLUG = PROD.get("id"), PROD.get("slug")
DIR = U1.get("addressId")

r = Runner("SEC-INPUT")


def checkout(items, extra=None):
    body = {"shippingAddressId": DIR, "items": items, "paymentMethod": "WALLET"}
    if extra:
        body.update(extra)
    return h.call("POST", "/api/me/orders/checkout", token=U1["token"], body=body)


# ── Cantidades ──────────────────────────────────────────────────────────────
for i, cant in enumerate([0, -1, -999999], 1):
    res = checkout([{"productId": PID, "quantity": cant}])
    r.case(f"SEC-INPUT-01.{i}", f"cantidad {cant} se rechaza", res.status == 400, f"status={res.status}")

for i, cant in enumerate([2147483647, 1000000000], 1):
    res = checkout([{"productId": PID, "quantity": cant}])
    r.case(f"SEC-INPUT-02.{i}", f"cantidad desbordante {cant} se rechaza sin romper el importe",
           res.status == 400, f"status={res.status} body={(res.body or '')[:90]}")

# ── El precio lo pone el servidor ───────────────────────────────────────────
# Se inyecta un precio ridículo (0,01) junto a la línea. Lo que se comprueba es que el cobro NO es ese:
# el total tiene que salir del catálogo, no del cuerpo de la petición. El importe llega en unidades de la
# divisa activa, no en céntimos — de ahí que la comparación se haga contra el precio inyectado.
res = checkout([{"productId": PID, "quantity": 1, "unitPriceCents": 1, "price": 0.01}])
cobrado = (res.json or {}).get("total") or (res.json or {}).get("totalUsdCents") or 0
r.case("SEC-INPUT-03", "un precio enviado por el cliente se ignora",
       res.status != 201 or float(cobrado) > 1.0,
       f"status={res.status} total={cobrado} (inyectado 0.01)")

# ── Inyección SQL ───────────────────────────────────────────────────────────
CARGAS = ["' OR '1'='1", "'; DROP TABLE users;--", "1' UNION SELECT null,null--", "%' OR 1=1--"]
for i, carga in enumerate(CARGAS, 1):
    res = h.call("GET", f"/api/search?q={h.urllib.parse.quote(carga)}&lang=es", token=U1["token"])
    r.case(f"SEC-INPUT-04.{i}", "inyección SQL en la búsqueda no rompe ni filtra",
           res.status in (200, 400) and "SQL" not in (res.body or "") and "syntax" not in (res.body or ""),
           f"status={res.status} body={(res.body or '')[:90]}")

usuarios_antes = h.sql("SELECT count(*) FROM users")
r.case("SEC-INPUT-04.tabla", "la tabla de usuarios sigue existiendo tras las inyecciones",
       usuarios_antes.isdigit() and int(usuarios_antes) > 0, f"users={usuarios_antes}")

# ── XSS almacenado ──────────────────────────────────────────────────────────
XSS = "<script>alert(document.cookie)</script>"
res = h.call("POST", "/api/contact", body={"name": XSS, "email": "xss@example.com",
                                           "subject": "cert", "message": XSS},
             headers={"X-Altcha": h.solve_captcha() or ""})
guardado = h.sql("SELECT message FROM contact_message ORDER BY created_at DESC LIMIT 1")
r.case("SEC-INPUT-05", "el XSS no se guarda como script ejecutable",
       res.status in (400, 422) or "<script>" not in (guardado or ""),
       f"status={res.status} guardado={(guardado or '')[:70]}")

# ── SSRF ────────────────────────────────────────────────────────────────────
INTERNAS = ["http://localhost:18082/actuator/health", "http://127.0.0.1:5432",
            "http://169.254.169.254/latest/meta-data/", "http://[::1]:18082/"]
for i, url in enumerate(INTERNAS, 1):
    res = h.call("POST", "/api/me/shops", token=U1["token"],
                 body={"platform": "SHOPIFY", "name": "cert", "shopUrl": url, "accessToken": "x"})
    r.case(f"SEC-INPUT-06.{i}", f"SSRF a {url.split('/')[2]} bloqueado",
           res.status in (400, 403, 422), f"status={res.status} body={(res.body or '')[:80]}")

# ── JSON y Content-Type ─────────────────────────────────────────────────────
res = h.call("POST", "/api/me/orders/checkout", token=U1["token"], raw_body="{esto no es json")
r.case("SEC-INPUT-07a", "JSON malformado devuelve 400, no 500", res.status == 400, f"status={res.status}")

res = h.call("POST", "/api/me/orders/checkout", token=U1["token"], raw_body="texto plano",
             headers={"Content-Type": "text/plain"})
r.case("SEC-INPUT-07b", "Content-Type erróneo devuelve 415/400, no 500",
       res.status in (400, 415), f"status={res.status}")

# ── UUID inválido ───────────────────────────────────────────────────────────
for i, malo in enumerate(["no-es-uuid", "../../etc/passwd", "00000000"], 1):
    res = h.call("GET", f"/api/me/orders/{h.urllib.parse.quote(malo, safe='')}", token=U1["token"])
    r.case(f"SEC-INPUT-08.{i}", f"UUID inválido ({malo[:14]}) no provoca 500",
           res.status in (400, 404), f"status={res.status}")

# ── Paginación ──────────────────────────────────────────────────────────────
for i, q in enumerate(["page=-1&size=10", "page=0&size=1000000000", "page=999999999&size=10",
                       "page=abc&size=xyz"], 1):
    res = h.call("GET", f"/api/catalog/products?{q}&lang=es", token=U1["token"])
    n = len(((res.json or {}).get("items") or []))
    r.case(f"SEC-INPUT-09.{i}", f"paginación absurda ({q}) queda acotada",
           res.status in (200, 400) and n <= 200, f"status={res.status} items={n}")

# ── Path traversal ──────────────────────────────────────────────────────────
for i, carga in enumerate(["../../../../etc/passwd", "..%2f..%2f..%2fetc%2fpasswd"], 1):
    res = h.call("GET", f"/api/me/billing/invoices/{carga}/invoice.pdf", token=U1["token"])
    r.case(f"SEC-INPUT-10.{i}", "path traversal en facturas rechazado",
           res.status in (400, 403, 404) and "root:" not in (res.body or ""), f"status={res.status}")

# ── Cabeceras hostiles ──────────────────────────────────────────────────────
HOSTILES = [{"X-Currency": "'; DROP--"}, {"Accept-Language": "\x00\x01basura"},
            {"X-Forwarded-For": "999.999.999.999"}, {"X-Currency": "A" * 500}]
for i, cab in enumerate(HOSTILES, 1):
    res = h.call("GET", "/api/catalog/products?page=0&size=1&lang=es", token=U1["token"], headers=cab)
    r.case(f"SEC-INPUT-11.{i}", f"cabecera hostil ({list(cab)[0]}) no rompe la respuesta",
           res.status in (200, 400), f"status={res.status}")

r.report("sec_input")
