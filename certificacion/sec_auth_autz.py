#!/usr/bin/env python3
"""Fase 1 — bloques SEC-AUTH (autenticación) y SEC-AUTZ (autorización por rol)."""
import base64
import json
import time

import harness as h
from runner import Runner

A = json.load(open("actores.json"))
U1, U2, OP, AD = A["u1"], A["u2"], A["operator"], A["admin"]

r = Runner("SEC-AUTH / SEC-AUTZ")

# ── SEC-AUTH ────────────────────────────────────────────────────────────────
resp_mala = h.login(U1["email"], "ClaveIncorrecta1!")
resp_inexistente = h.login(f"no-existe-{int(time.time())}@example.com", "ClaveIncorrecta1!")

r.case("SEC-AUTH-01", "Login con contraseña incorrecta no entrega token",
       resp_mala.status in (400, 401) and "token" not in (resp_mala.body or ""),
       f"status={resp_mala.status} body={resp_mala.body[:120]}")

r.case("SEC-AUTH-02", "Usuario inexistente responde IGUAL que clave incorrecta (sin enumeración)",
       resp_inexistente.status == resp_mala.status,
       f"inexistente={resp_inexistente.status} vs clave-mala={resp_mala.status}")

reg_dup = h.register(U1["email"], "OtraClave123!")
r.case("SEC-AUTH-03", "Registro con email ya existente no revela que la cuenta existe",
       reg_dup.status in (200, 201, 202, 204) or "exist" not in (reg_dup.body or "").lower(),
       f"status={reg_dup.status} body={reg_dup.body[:140]}")

tok = U1["token"]
partes = tok.split(".")
manipulado = partes[0] + "." + partes[1] + ".firmaInventadaXXXX"
r.case("SEC-AUTH-06", "Token con firma manipulada se rechaza",
       h.call("GET", "/api/me/orders", token=manipulado).status == 401)


def con_payload(tok_original, cambios):
    """Reconstruye el token con el payload alterado (misma firma) — no debe colar."""
    p = tok_original.split(".")
    relleno = "=" * (-len(p[1]) % 4)
    carga = json.loads(base64.urlsafe_b64decode(p[1] + relleno))
    carga.update(cambios)
    nueva = base64.urlsafe_b64encode(json.dumps(carga).encode()).decode().rstrip("=")
    return f"{p[0]}.{nueva}.{p[2]}"


r.case("SEC-AUTH-08", "Token con authorities inflado a ADMIN se rechaza",
       h.call("GET", "/api/admin/users?page=0&size=1",
              token=con_payload(tok, {"authorities": ["ROLE_ADMIN"]})).status in (401, 403))

r.case("SEC-AUTH-07", "Token caducado se rechaza",
       h.call("GET", "/api/me/orders", token=con_payload(tok, {"exp": 1000000000})).status == 401)

r.case("SEC-AUTH-15", "Sin token, los recursos propios están cerrados",
       h.call("GET", "/api/me/orders").status == 401)

# ── SEC-AUTZ ────────────────────────────────────────────────────────────────
ADMIN_GET = ["/api/admin/users?page=0&size=1", "/api/admin/dashboard/summary",
             "/api/admin/pricing/rules", "/api/admin/wallets?page=0&size=1",
             "/api/admin/catalog/products?page=0&size=1", "/api/admin/partners",
             "/api/admin/promotions", "/api/admin/subscription-plans"]

for i, ruta in enumerate(ADMIN_GET, 1):
    s_user = h.call("GET", ruta, token=U1["token"]).status
    r.case(f"SEC-AUTZ-01.{i}", f"usuario normal NO entra en {ruta.split('?')[0]}",
           s_user == 403, f"status={s_user}")

for i, ruta in enumerate(ADMIN_GET[:4], 1):
    s_anon = h.call("GET", ruta).status
    r.case(f"SEC-AUTZ-02.{i}", f"anónimo NO entra en {ruta.split('?')[0]}",
           s_anon == 401, f"status={s_anon}")

# El operador es soporte: puede mover pedidos, no puede tocar dinero ni gestión.
PROHIBIDO_OPERADOR = [("POST", "/api/admin/orders/00000000-0000-0000-0000-000000000000/refund"),
                      ("POST", "/api/admin/orders/00000000-0000-0000-0000-000000000000/cancel"),
                      ("POST", "/api/admin/orders/bulk-refund"),
                      ("POST", "/api/admin/orders/bulk-cancel"),
                      ("POST", "/api/admin/orders/import"),
                      ("POST", "/api/admin/orders/demo"),
                      ("POST", "/api/admin/orders")]
for i, (m, ruta) in enumerate(PROHIBIDO_OPERADOR, 1):
    s = h.call(m, ruta, token=OP["token"], body={}).status
    r.case(f"SEC-AUTZ-03.{i}", f"OPERATOR no puede {m} {ruta.split('/')[-1]}",
           s == 403, f"status={s}")

for i, ruta in enumerate(["/api/admin/pricing/rules", "/api/admin/users?page=0&size=1",
                          "/api/admin/dashboard/summary", "/api/admin/wallets?page=0&size=1"], 1):
    s = h.call("GET", ruta, token=OP["token"]).status
    r.case(f"SEC-AUTZ-06.{i}", f"OPERATOR no ve {ruta.split('?')[0]}", s == 403, f"status={s}")

prod = A.get("producto") or {}
pid = prod.get("id")
if pid:
    for actor, nombre in ((U1, "usuario"), (OP, "operador")):
        s = h.call("GET", f"/api/catalog/products/{pid}/margin-estimate", token=actor["token"]).status
        r.case(f"SEC-AUTZ-07.{nombre}", f"{nombre} NO puede ver el margen", s == 403, f"status={s}")
    s_admin = h.call("GET", f"/api/catalog/products/{pid}/margin-estimate", token=AD["token"]).status
    r.case("SEC-AUTZ-07.admin", "el admin SÍ puede ver el margen", s_admin == 200, f"status={s_admin}")

s = h.call("POST", "/api/me/sourcing/requests/00000000-0000-0000-0000-000000000000/quotes",
           token=U1["token"], body={"price": 1}).status
r.case("SEC-AUTZ-09", "un cliente no puede inyectar cotizaciones de sourcing", s == 403, f"status={s}")

s = h.call("GET", "/api/v1/partner/catalog/products?page=0&size=1", token=U1["token"]).status
r.case("SEC-AUTZ-11", "token de usuario no vale en la API de partners", s in (401, 403), f"status={s}")

s = h.call("GET", "/api/v1/partner/catalog/products?page=0&size=1").status
r.case("SEC-AUTZ-13", "API de partners sin credencial", s == 401, f"status={s}")

r.report("sec_auth_autz")
