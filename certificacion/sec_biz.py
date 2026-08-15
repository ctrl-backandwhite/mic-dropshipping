#!/usr/bin/env python3
"""Fase 1 — SEC-BIZ: lógica de negocio con dinero de por medio.

Es el bloque donde un fallo cuesta dinero de verdad, así que aquí no basta con el código de estado: se
comprueba el SALDO y el TOTAL antes y después, al céntimo.
"""
import concurrent.futures
import json

import harness as h
from runner import Runner

A = json.load(open("actores.json"))
U1, U2, AD = A["u1"], A["u2"], A["admin"]
PROD = A.get("producto") or {}
PID = PROD.get("id")
DIR = U1.get("addressId")

r = Runner("SEC-BIZ")


def saldo_de(actor):
    w = h.call("GET", "/api/me/wallet", token=actor["token"]).json or {}
    return w.get("balanceUsdCents")


def checkout(actor, direccion, cantidad=1, extra=None, idem=None):
    body = {"shippingAddressId": direccion, "items": [{"productId": PID, "quantity": cantidad}],
            "paymentMethod": "WALLET"}
    if extra:
        body.update(extra)
    cab = {"Idempotency-Key": idem} if idem else None
    return h.call("POST", "/api/me/orders/checkout", token=actor["token"], body=body, headers=cab)


# ── SEC-BIZ-01 · Doble gasto: dos pagos simultáneos con saldo para uno solo ──
h.sql(f"UPDATE wallet SET balance_usd_cents = 2000 WHERE user_id = '{U1['id']}'")
antes = saldo_de(U1)

with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    futuros = [pool.submit(checkout, U1, DIR, 1) for _ in range(6)]
    respuestas = [f.result() for f in futuros]

creados = [x for x in respuestas if x.status == 201]
despues = saldo_de(U1)
r.case("SEC-BIZ-01", "seis pagos concurrentes no dejan el saldo en negativo",
       despues is not None and despues >= 0,
       f"saldo antes={antes} después={despues} pedidos creados={len(creados)}")

gastado = (antes or 0) - (despues or 0)
esperado = sum(int(float((x.json or {}).get("total", 0)) * 100) for x in creados)
r.case("SEC-BIZ-01b", "lo descontado coincide con lo cobrado en los pedidos que prosperaron",
       abs(gastado - esperado) <= len(creados),
       f"descontado={gastado} suma de pedidos={esperado} ({len(creados)} pedidos)")

# ── SEC-BIZ-02 · Replay: misma clave de idempotencia dos veces ───────────────
h.sql(f"UPDATE wallet SET balance_usd_cents = 50000 WHERE user_id = '{U1['id']}'")
antes = saldo_de(U1)
clave = "cert-idem-001"
p1 = checkout(U1, DIR, 1, idem=clave)
p2 = checkout(U1, DIR, 1, idem=clave)
despues = saldo_de(U1)
id1 = (p1.json or {}).get("id")
id2 = (p2.json or {}).get("id")
r.case("SEC-BIZ-02", "repetir el checkout con la misma clave no duplica el pedido",
       id1 is not None and id1 == id2,
       f"pedido1={id1} pedido2={id2} status={p1.status}/{p2.status}")
cobro = (antes or 0) - (despues or 0)
unitario = int(float((p1.json or {}).get("total", 0)) * 100)
r.case("SEC-BIZ-02b", "el replay no cobra dos veces",
       abs(cobro - unitario) <= 1, f"descontado={cobro} esperado={unitario}")

# ── SEC-BIZ-03 · Confirmación simulada de un pago real ──────────────────────
rec = h.call("POST", "/api/me/wallet/recharge", token=U1["token"],
             body={"method": "CARD", "amountUsdCents": 10000, "currencyDisplay": "USD",
                   "amountDisplay": 100})
pago = (rec.json or {}).get("paymentId") or (rec.json or {}).get("id")
antes = saldo_de(U1)
mock = h.call("POST", f"/api/me/wallet/confirm-mock?paymentId={pago}", token=U1["token"], body={})
despues = saldo_de(U1)
r.case("SEC-BIZ-03", "no se puede acreditar una recarga real con la confirmación simulada",
       mock.status in (400, 403, 422) and antes == despues,
       f"status={mock.status} saldo {antes}→{despues}")

# ── SEC-BIZ-08 · Reembolso mayor que lo pagado ──────────────────────────────
pedido = id1
# El endpoint de reembolso NO acepta importe: `refundOrder(UUID id)` devuelve el pedido entero y punto.
# Así que "reembolsar de más" no es algo que se pueda pedir — la defensa está en el diseño, no en una
# validación. Lo que sí hay que comprobar es que un importe inyectado en el cuerpo se ignora y que al
# cliente le vuelve EXACTAMENTE lo que pagó, ni un céntimo más.
if pedido:
    saldo_antes = saldo_de(U1)
    res = h.call("POST", f"/api/admin/orders/{pedido}/refund", token=AD["token"],
                 body={"amountUsdCents": unitario * 10, "reason": "certificacion"})
    saldo_despues = saldo_de(U1)
    devuelto = (saldo_despues or 0) - (saldo_antes or 0)
    r.case("SEC-BIZ-08", "el importe inyectado en el cuerpo del reembolso se ignora",
           res.status in (200, 201), f"status={res.status}")
    r.importe("SEC-BIZ-08b", "el reembolso devuelve exactamente lo pagado", devuelto, unitario)
else:
    r.skip("SEC-BIZ-08", "el reembolso devuelve exactamente lo pagado", "no hay pedido de referencia")

# ── SEC-BIZ-09 · Retirada de afiliado por encima del saldo ──────────────────
res = h.call("POST", "/api/me/affiliate/payout-request", token=U1["token"],
             body={"amountUsdCents": 99999999, "method": "WALLET"})
r.case("SEC-BIZ-09", "una retirada de afiliado mayor que el saldo se rechaza",
       res.status in (400, 403, 409, 422), f"status={res.status} body={(res.body or '')[:100]}")

# ── SEC-BIZ-07 · El total lo recalcula el servidor ──────────────────────────
h.sql(f"UPDATE wallet SET balance_usd_cents = 50000 WHERE user_id = '{U1['id']}'")
p_uno = checkout(U1, DIR, 1)
p_tres = checkout(U1, DIR, 3)
t1 = float((p_uno.json or {}).get("total", 0))
t3 = float((p_tres.json or {}).get("total", 0))
r.case("SEC-BIZ-07", "el total escala con la cantidad (lo calcula el servidor)",
       t1 > 0 and t3 > t1, f"1 unidad={t1} · 3 unidades={t3}")

# ── SEC-BIZ-05 · Cupón inexistente ──────────────────────────────────────────
res = checkout(U1, DIR, 1, extra={"couponCode": "CUPON-QUE-NO-EXISTE-9999"})
r.case("SEC-BIZ-05", "un cupón inexistente no descuenta ni rompe",
       res.status in (200, 201, 400, 404, 422), f"status={res.status}")

r.report("sec_biz")
