#!/usr/bin/env python3
"""Certificación funcional de punta a punta: el flujo del usuario, el del admin, y que AMBOS ENLACEN.

Por qué existe. Las dos mitades de la aplicación se certificaban por separado y por separado funcionaban:
el usuario compraba y el admin listaba pedidos. Lo que nadie comprobaba es la costura — que el pedido que
acaba de hacer un usuario aparezca en el admin con los MISMOS importes, que lo que el admin cambia ahí se
vea en «mis pedidos», y que el ciclo entero (aceptar → enviar → entregar) llegue de vuelta al comprador.
Una costura rota no da error en ninguno de los dos lados: simplemente el pedido no está.

Qué recorre:

  A. USUARIO    registro real → activación → login → catálogo → ficha → carrito → wallet → checkout
                → mis pedidos → factura
  B. COSTURA    el pedido del usuario aparece en el admin, con el mismo importe y el mismo comprador
  C. CICLO      admin acepta → envía → entrega, y CADA cambio se refleja en el pedido del usuario
  D. ADMIN      las 30 secciones del panel responden a un admin y están cerradas a un usuario normal

Solo local: el flujo crea pedidos y mueve saldo de verdad.

Uso:  python3 cert_e2e_usuario_admin.py
"""
import json
import sys
import time
import uuid

import harness as h

API = h.API
CLAVE = "Cert-E2E-2026!x"


class Acta:
    """Registro de lo comprobado. Guarda el detalle del fallo, que es lo único que sirve para arreglarlo."""

    def __init__(self):
        self.casos = []

    def mide(self, bloque, nombre, ok, detalle=""):
        self.casos.append((bloque, nombre, bool(ok), detalle))
        marca = "\033[32mPASA \033[0m" if ok else "\033[31mFALLA\033[0m"
        print("  %s %-46s %s" % (marca, nombre, ("\033[90m%s\033[0m" % detalle) if detalle else ""))
        return ok

    def fallos(self):
        return [c for c in self.casos if not c[2]]

    def resumen(self):
        print("\n===== %d casos · %d fallos =====" % (len(self.casos), len(self.fallos())))
        for bloque, nombre, _, detalle in self.fallos():
            print("  [%s] %s → %s" % (bloque, nombre, detalle))
        return 1 if self.fallos() else 0


acta = Acta()


def titulo(t):
    print("\n\033[1m══ %s ══\033[0m" % t)


def items_de(res):
    """Elementos de una respuesta de listado. Las dos APIs no coinciden: unas devuelven la lista pelada y
    otras la envuelven en un paginado con `items`. Certificar contra una sola forma rompía con la otra."""
    d = res.json
    if isinstance(d, list):
        return d
    if isinstance(d, dict):
        for k in ("items", "content", "data", "results"):
            v = d.get(k)
            if isinstance(v, list):
                return v
    return []


def importe(pedido):
    """Importe total del pedido, mirando los nombres que usan las dos APIs (no comparten DTO).

    Los céntimos van PRIMERO y son la fuente buena: el admin expone `totalCents` (entero, exacto) y la
    vista del usuario un decimal. Comparar por el decimal de cada lado arrastra redondeos; comparar el
    entero no. Sin mirar `totalCents`, el importe del admin se leía como ausente y la comparación entre
    las dos vistas —que es justo lo que este bloque certifica— daba un falso fallo.
    """
    for k in ("totalCents", "grandTotalCents", "amountCents"):
        v = pedido.get(k)
        if isinstance(v, int) and v > 0:
            return round(v / 100.0, 2)
    for k in ("totalUsd", "total", "grandTotal", "totalAmount", "amount", "totalPrice", "totalFormatted"):
        v = pedido.get(k)
        if isinstance(v, (int, float)) and v > 0:
            return round(float(v), 2)
        if isinstance(v, str):
            try:
                return round(float(v.replace(",", ".").replace("€", "").replace("$", "").strip()), 2)
            except ValueError:
                continue
    return None


# ── A. FLUJO DEL USUARIO ────────────────────────────────────────────────────────────────────────────

titulo("A · FLUJO DEL USUARIO")

correo = "e2e-%s@cert.test" % uuid.uuid4().hex[:10]
r = h.register(correo, CLAVE)
acta.mide("A", "un usuario nuevo puede registrarse", r.status in (200, 201), "status=%d" % r.status)

r = h.activate(correo)
acta.mide("A", "la cuenta se activa con el código del correo", r is not None and r.status in (200, 204),
          "status=%s" % (r.status if r else "sin código"))

token = h.token_of(correo, CLAVE)
acta.mide("A", "el usuario activado puede entrar", bool(token), "" if token else "no se obtuvo token")
if not token:
    print("\n\033[31mSin token no hay flujo que certificar.\033[0m")
    sys.exit(acta.resumen())

r = h.call("GET", "/api/catalog/products?size=5", token=token)
productos = ((r.json or {}).get("items") or [])
acta.mide("A", "el catálogo se lista", r.status == 200 and len(productos) > 0,
          "status=%d · %d productos" % (r.status, len(productos)))

r = h.call("GET", "/api/search?q=camiseta&size=5", token=token)
acta.mide("A", "la búsqueda responde", r.status == 200 and (r.json or {}).get("total", 0) > 0,
          "status=%d · %s hits" % (r.status, (r.json or {}).get("total")))

producto = productos[0] if productos else {}
slug = producto.get("slug")
r = h.call("GET", "/api/catalog/products/%s" % slug, token=token) if slug else h.Res(0, "", {})
ficha = r.json or {}
acta.mide("A", "la ficha del producto se abre", r.status == 200 and bool(ficha.get("id")),
          "status=%d · %s" % (r.status, str(ficha.get("title"))[:38]))

# La ficha NO puede filtrar el coste: es el margen del negocio.
crudo = json.dumps(ficha)
acta.mide("A", "la ficha no filtra coste ni margen",
          not any(k in crudo for k in ('"costCny"', '"marginPct"', '"basePriceCny"', '"supplierCost"')),
          "")

variantes = ficha.get("variants") or []
variante = next((v for v in variantes if v.get("id")), None)
acta.mide("A", "el producto tiene variante comprable", variante is not None,
          "%d variantes" % len(variantes))

# Wallet con saldo. El confirm-mock rechaza acreditar recargas reales (protección correcta, SEC-BIZ-03),
# así que el saldo se pone en la base con su apunte, igual que hace setup_recursos. Es montar el
# escenario, no una prueba: lo que se certifica es el checkout, no cómo entró el dinero.
uid = h.sql("SELECT id FROM users WHERE email='%s'" % correo)
# INSERT … ON CONFLICT, no UPDATE: a un usuario recién registrado la wallet todavía NO se le ha creado
# (no se siembra saldo por diseño), así que un UPDATE no tocaba ninguna fila y el checkout moría por saldo
# insuficiente — con la wallet respondiendo 200 y $0,00, que parecía correcto.
h.sql("INSERT INTO wallet (id, user_id, balance_usd_cents, currency_default, status, created_at, updated_at)"
      " VALUES (gen_random_uuid(), '%s', 500000, 'USD', 'ACTIVE', now(), now())"
      " ON CONFLICT (user_id) DO UPDATE SET balance_usd_cents = 500000, updated_at = now()" % uid)
h.sql("INSERT INTO wallet_transaction (id, wallet_id, kind, amount_usd_cents, balance_after_cents,"
      " status, description, created_at) SELECT gen_random_uuid(), w.id, 'RECHARGE', 500000, 500000,"
      " 'COMPLETED', 'cert e2e: saldo inicial', now() FROM wallet w WHERE w.user_id='%s'" % uid)
r = h.call("GET", "/api/me/wallet", token=token)
saldo = (r.json or {}).get("balanceFormatted")
centimos = (r.json or {}).get("balanceUsdCents") or 0
# Exigir saldo > 0, no solo un 200. Con «responde 200 y trae algo» el caso pasaba con $0,00 y el fallo
# aparecía dos líneas más abajo, en el checkout, disfrazado de error de negocio.
acta.mide("A", "la wallet muestra el saldo", r.status == 200 and centimos > 0,
          "status=%d · %s" % (r.status, saldo))

# La dirección va en el PERFIL y al checkout se le pasa su id: el pedido no acepta una dirección suelta
# en el cuerpo. Es deliberado — así la dirección queda ligada al usuario y es auditable.
r = h.call("POST", "/api/me/addresses", token=token, body={
    "fullName": "Cert E2E", "line1": "Calle Castelví 7", "city": "Zaragoza", "postalCode": "50004",
    "country": "ES", "phone": "+34600000003", "isDefault": True})
direccion = (r.json or {}).get("id")
acta.mide("A", "el usuario guarda su dirección de envío", bool(direccion),
          "status=%d %s" % (r.status, r.body[:70] if not direccion else ""))

pedido_id = None
if variante and direccion:
    r = h.call("POST", "/api/me/orders/checkout", token=token, body={
        "shippingAddressId": direccion,
        "items": [{"productId": ficha["id"], "variantId": variante["id"], "quantity": 1}],
        "paymentMethod": "WALLET"})
    pedido = r.json or {}
    pedido_id = pedido.get("id")
    acta.mide("A", "el checkout crea el pedido", r.status in (200, 201) and bool(pedido_id),
              "status=%d %s" % (r.status, r.body[:90] if not pedido_id else ""))

total_usuario = None
if pedido_id:
    r = h.call("GET", "/api/me/orders/%s" % pedido_id, token=token)
    mio = r.json or {}
    total_usuario = importe(mio)
    acta.mide("A", "el pedido se ve en «mis pedidos»", r.status == 200 and mio.get("id") == pedido_id,
              "status=%d · total=%s" % (r.status, total_usuario))

    r = h.call("GET", "/api/me/orders", token=token)
    lista = items_de(r)
    acta.mide("A", "el pedido aparece en el listado del usuario",
              any(p.get("id") == pedido_id for p in lista), "%d pedidos" % len(lista))

    r = h.call("GET", "/api/me/billing/invoices", token=token)
    acta.mide("A", "el usuario consulta sus facturas", r.status == 200, "status=%d" % r.status)


# ── B. LA COSTURA: el pedido del usuario en el admin ────────────────────────────────────────────────

titulo("B · EL PEDIDO DEL USUARIO, VISTO DESDE EL ADMIN")

actores = json.load(open("actores.json"))
admin = actores["admin"]["token"]
usuario_normal = actores["u1"]["token"]

en_admin = {}
if pedido_id:
    r = h.call("GET", "/api/admin/orders/%s" % pedido_id, token=admin)
    en_admin = r.json or {}
    acta.mide("B", "el admin abre el pedido recién hecho", r.status == 200 and en_admin.get("id") == pedido_id,
              "status=%d" % r.status)

    total_admin = importe(en_admin)
    acta.mide("B", "el importe coincide en las dos vistas",
              total_usuario is not None and total_admin is not None and abs(total_usuario - total_admin) < 0.01,
              "usuario=%s · admin=%s" % (total_usuario, total_admin))

    crudo_admin = json.dumps(en_admin)
    acta.mide("B", "el admin sí identifica al comprador",
              correo in crudo_admin or str(uid) in crudo_admin, "")

    r = h.call("GET", "/api/admin/orders?size=20", token=admin)
    lista_admin = items_de(r)
    acta.mide("B", "el pedido sale en el listado del admin",
              any(p.get("id") == pedido_id for p in lista_admin),
              "%d pedidos listados" % len(lista_admin))

    r = h.call("GET", "/api/admin/orders/%s" % pedido_id, token=usuario_normal)
    acta.mide("B", "un usuario normal NO entra en el pedido por la vía del admin",
              r.status in (401, 403), "status=%d" % r.status)


# ── C. EL CICLO DE VIDA, DE VUELTA AL USUARIO ───────────────────────────────────────────────────────

titulo("C · LO QUE CAMBIA EL ADMIN LO VE EL USUARIO")


def estado(pedido):
    return (pedido.get("status") or pedido.get("state") or "").upper()


if pedido_id:
    inicial = estado(en_admin)
    for accion, esperado in (("forward", ("FORWARDED", "PROCESSING", "ACCEPTED", "PURCHASED")),
                             ("ship", ("SHIPPED", "IN_TRANSIT")),
                             ("deliver", ("DELIVERED", "COMPLETED"))):
        r = h.call("POST", "/api/admin/orders/%s/%s" % (pedido_id, accion), token=admin, body={})
        movido = r.status in (200, 201, 202, 204)
        acta.mide("C", "el admin puede %s el pedido" % accion, movido,
                  "status=%d %s" % (r.status, r.body[:80] if not movido else ""))
        if not movido:
            break
        # Lo que importa no es que el admin reciba 200, sino que el COMPRADOR lo vea.
        v = h.call("GET", "/api/me/orders/%s" % pedido_id, token=token).json or {}
        acta.mide("C", "tras «%s» el usuario ve el nuevo estado" % accion,
                  estado(v) in esperado, "usuario ve «%s» (esperado %s)" % (estado(v), "/".join(esperado)))

    v = h.call("GET", "/api/me/orders/%s" % pedido_id, token=token).json or {}
    acta.mide("C", "el estado final ya no es el inicial", estado(v) != inicial,
              "de «%s» a «%s»" % (inicial, estado(v)))


# ── D. EL PANEL DE ADMINISTRACIÓN, SECCIÓN A SECCIÓN ────────────────────────────────────────────────

titulo("D · EL ADMIN COMPLETO")

SECCIONES = [
    ("panel · métricas", "/api/admin/dashboard/metrics"),
    ("panel · series", "/api/admin/dashboard/series"),
    ("panel · últimos pedidos", "/api/admin/dashboard/recent-orders"),
    ("pedidos", "/api/admin/orders?size=5"),
    ("catálogo", "/api/admin/catalog/products?size=5"),
    ("categorías", "/api/admin/catalog/categories"),
    ("proveedores", "/api/admin/catalog/suppliers"),
    ("usuarios", "/api/admin/users?size=5"),
    ("wallets", "/api/admin/wallets?size=5"),
    ("afiliados", "/api/admin/affiliates?size=5"),
    ("afiliados · pagos pendientes", "/api/admin/affiliates/payouts/pending"),
    ("promociones", "/api/admin/promotions"),
    ("reglas de precio", "/api/admin/pricing/rules"),
    ("regla de MOQ", "/api/admin/pricing/moq-rule"),
    ("impuestos", "/api/admin/tax-rates"),
    ("reglas de aduana", "/api/admin/customs-rules"),
    ("cumplimiento · responsable", "/api/admin/compliance/responsible-person"),
    ("cumplimiento · estado", "/api/admin/compliance/status"),
    ("cumplimiento · sin fabricante", "/api/admin/compliance/products/missing-manufacturer"),
    ("almacenes", "/api/admin/warehouses"),
    ("planes", "/api/admin/subscription-plans"),
    ("suscripciones", "/api/admin/billing/subscriptions"),
    ("compras", "/api/admin/purchases?size=5"),
    ("compras · en riesgo", "/api/admin/purchases/at-risk"),
    ("operadores · informe", "/api/admin/operators/report"),
    ("partners · apps", "/api/admin/partners/apps"),
    ("partners · conexiones", "/api/admin/partners/shop-connections"),
    ("mentores", "/api/admin/mentors"),
    ("academia", "/api/admin/academy/courses"),
    ("newsletter", "/api/admin/newsletter"),
    ("grupos de producto", "/api/admin/product-groups"),
    ("webhooks", "/api/admin/webhooks/subscriptions"),
]


for nombre, ruta in SECCIONES:
    r = h.call("GET", ruta, token=admin)
    # 404 en un GET de listado es una ruta que no existe: o el panel enseña algo que la API no sirve, o
    # la ruta cambió y esta certificación se quedó atrás. Las dos cosas hay que verlas.
    acta.mide("D", "admin · %s" % nombre, r.status == 200, "status=%d %s" % (r.status, r.body[:60]
                                                                            if r.status != 200 else ""))

titulo("D · CERRADO A QUIEN NO ES ADMIN")

for nombre, ruta in SECCIONES[:12]:
    r = h.call("GET", ruta, token=usuario_normal)
    acta.mide("D", "un usuario normal no ve %s" % nombre, r.status in (401, 403), "status=%d" % r.status)


sys.exit(acta.resumen())
