#!/usr/bin/env python3
"""Fase 0-bis: crea RECURSOS REALES de user1 (y los mínimos de user2) para poder cruzar los IDOR.

Sin esto, un 404 al pedir el recurso de otro no prueba nada: podría ser simplemente que no existe. Cada
recurso que no se consiga crear se anota como tal, y el bloque SEC-IDOR marcará su caso como NO EJECUTADO
en lugar de darlo por bueno — un caso que no se puede ejecutar no es un caso que pasa.
"""
import json

import harness as h

A = json.load(open("actores.json"))
U1, U2 = A["u1"], A["u2"]
recursos = A.get("recursos", {})


def paso(nombre, res, extraer=None):
    """Registra el resultado de crear un recurso. Devuelve el id si se pudo."""
    ok = res is not None and res.status in (200, 201, 202, 204)
    valor = None
    if ok and extraer:
        try:
            valor = extraer(res.json)
        except Exception:  # noqa: BLE001
            valor = None
    estado = "OK" if ok else f"NO ({res.status if res else 'sin respuesta'})"
    detalle = "" if ok else f" · {(res.body or '')[:110]}" if res else ""
    print(f"  · {nombre:<26} {estado}{detalle}")
    return valor


prod = A.get("producto") or {}
pid = prod.get("id")
slug = prod.get("slug")

print("Recursos de user1…")

# Favorito y carrito guardado: van por usuario, pero dan superficie de lectura cruzada.
recursos["favorito"] = paso("favorito", h.call("POST", f"/api/me/favorites/{pid}", token=U1["token"]),
                            lambda j: pid)

ficha = h.call("GET", f"/api/catalog/products/{slug}?lang=es", token=U1["token"])
variantes = ((ficha.json or {}).get("variants") or [])
vid = variantes[0].get("id") if variantes else None
print(f"  · variante de referencia    {vid}")

fj = ficha.json or {}
# El PUT recibe UNA línea suelta (upsert), no una lista ni un objeto con "items".
recursos["savedCart"] = paso("carrito guardado", h.call("PUT", "/api/me/saved-cart", token=U1["token"], body={
    "productId": pid, "variantId": vid, "slug": slug, "title": fj.get("title") or "Cert",
    "unitPriceSource": 10, "sourceCurrency": "CNY", "quantity": 1}), lambda j: True)

# Clave de API del propio usuario (superficie de partner).
recursos["apiKey"] = paso("clave de API", h.call("POST", "/api/me/api-keys", token=U1["token"],
                                                 body={"name": "cert-u1"}),
                          lambda j: (j or {}).get("clientId"))

# Petición de sourcing.
recursos["sourcing"] = paso("sourcing", h.call("POST", "/api/me/sourcing/requests", token=U1["token"], body={
    "url": "https://detail.1688.com/offer/123456.html", "titleHint": "cert",
    "notes": "certificacion"}), lambda j: (j or {}).get("id"))

# Alta de afiliado y código propio.
h.call("POST", "/api/me/affiliate/join", token=U1["token"], body={})
recursos["codigoAfiliado"] = paso("código de afiliado",
                                  h.call("POST", "/api/me/affiliate/codes", token=U1["token"],
                                         body={"code": f"CERT{U1['id'][:6].upper()}"}),
                                  lambda j: (j or {}).get("id"))

# Wallet: hace falta saldo para poder pagar un pedido y así tener un pedido REAL de user1.
rec = h.call("POST", "/api/me/wallet/recharge", token=U1["token"],
             body={"method": "CARD", "amountUsdCents": 50000, "currencyDisplay": "USD", "amountDisplay": 500})
pago = paso("recarga de wallet", rec, lambda j: (j or {}).get("paymentId") or (j or {}).get("id"))
if pago:
    conf = h.call("POST", f"/api/me/wallet/recharge/{pago}/confirm", token=U1["token"], body={})
    if conf.status not in (200, 201, 204):
        # En local el pago es simulado: la vía de acreditación es el confirm de mock.
        conf = h.call("POST", f"/api/me/wallet/confirm-mock?paymentId={pago}", token=U1["token"], body={})
    paso("confirmación de recarga", conf)
saldo = h.call("GET", "/api/me/wallet", token=U1["token"])
print(f"  · saldo tras recarga        {(saldo.json or {}).get('balanceFormatted') or (saldo.json or {})}")

# El confirm-mock RECHAZA acreditar una recarga real (protección correcta: SEC-BIZ-03). Para que user1
# tenga un pedido de verdad —imprescindible en los IDOR— se acredita el saldo en la base LOCAL, con su
# apunte en el libro para no dejar la wallet descuadrada. Esto es preparación de escenario, no una prueba.
if (saldo.json or {}).get("balanceUsdCents", 0) < 10000:
    h.sql(f"INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents, currency_default, status,"
          f" created_at, updated_at) SELECT gen_random_uuid(), '{U1['id']}', 0, 0, 'USD', 'ACTIVE', now(),"
          f" now() WHERE NOT EXISTS (SELECT 1 FROM wallet WHERE user_id='{U1['id']}')")
    h.sql(f"UPDATE wallet SET balance_usd_cents = 50000, updated_at = now() WHERE user_id='{U1['id']}'")
    h.sql("INSERT INTO wallet_transaction (id, wallet_id, kind, amount_usd_cents, balance_after_cents,"
          " status, description, created_at) SELECT gen_random_uuid(), w.id, 'RECHARGE', 50000, 50000,"
          f" 'COMPLETED', 'certificacion: saldo inicial', now() FROM wallet w WHERE w.user_id='{U1['id']}'")
    saldo = h.call("GET", "/api/me/wallet", token=U1["token"])
    print(f"  · saldo acreditado (local)  {(saldo.json or {}).get('balanceFormatted')}")

# Pedido de user1 (el recurso más valioso para los IDOR).
ped = h.call("POST", "/api/me/orders/checkout", token=U1["token"], body={
    "shippingAddressId": U1.get("addressId"),
    "items": [{"productId": pid, "variantId": vid, "quantity": 1}],
    "paymentMethod": "WALLET"})
recursos["pedido"] = paso("pedido", ped, lambda j: (j or {}).get("id"))

print("\nRecursos de user2 (para simetría)…")
r2 = h.call("POST", "/api/me/addresses", token=U2["token"], body={
    "fullName": "Cert Dos", "line1": "Gran Via 1", "city": "Madrid", "postalCode": "28013",
    "country": "ES", "phone": "+34600000002", "isDefault": True})
U2["addressId"] = paso("dirección de u2", r2, lambda j: (j or {}).get("id"))

A["recursos"] = recursos
A["u2"] = U2
with open("actores.json", "w") as f:
    json.dump(A, f, indent=2)

print("\nResumen de recursos de user1:")
for k, v in recursos.items():
    print(f"  {k:<18} {'—' if not v else str(v)[:40]}")
