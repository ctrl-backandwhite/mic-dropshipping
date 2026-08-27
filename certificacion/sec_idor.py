#!/usr/bin/env python3
"""Fase 1 — SEC-IDOR: user2 intenta alcanzar los recursos de user1.

Todos los recursos que se cruzan EXISTEN y son de user1 (los crea setup_recursos.py). Eso es lo que da
valor al caso: un 404 aquí significa "no es tuyo", no "no existe". Se acepta 403 o 404 —ocultar la
existencia es una defensa legítima—, pero **nunca** un 200 con el contenido ajeno.
"""
import json

import harness as h
from runner import Runner

A = json.load(open("actores.json"))
U1, U2 = A["u1"], A["u2"]
R = A.get("recursos", {})

r = Runner("SEC-IDOR")


def ajeno(ident, descripcion, metodo, ruta, recurso, body=None):
    """El recurso es de user1; user2 no debe poder tocarlo."""
    if not recurso:
        return r.skip(ident, descripcion, "el recurso de user1 no se pudo crear")
    res = h.call(metodo, ruta, token=U2["token"], body=body)
    negado = res.status in (401, 403, 404)
    return r.case(ident, descripcion, negado, f"status={res.status} body={(res.body or '')[:110]}")


pedido = R.get("pedido")
ajeno("SEC-IDOR-01", "user2 no lee el pedido de user1",
      "GET", f"/api/me/orders/{pedido}", pedido)

ajeno("SEC-IDOR-02", "user2 no cancela el pedido de user1",
      "POST", f"/api/me/orders/{pedido}/cancel", pedido, body={})

ajeno("SEC-IDOR-02b", "user2 no ve el seguimiento del pedido de user1",
      "GET", f"/api/me/orders/{pedido}/tracking", pedido)

direccion = U1.get("addressId")
ajeno("SEC-IDOR-03a", "user2 no edita la dirección de user1",
      "PUT", f"/api/me/addresses/{direccion}", direccion,
      body={"fullName": "Secuestrada", "line1": "X", "city": "Madrid", "postalCode": "28001",
            "country": "ES", "phone": "+34600000009"})

ajeno("SEC-IDOR-03b", "user2 no borra la dirección de user1",
      "DELETE", f"/api/me/addresses/{direccion}", direccion)

# El pago de la recarga de user1: confirmarlo desde otra cuenta acreditaría saldo ajeno.
pago_u1 = h.sql(f"SELECT p.id FROM payment p JOIN users u ON u.id = p.user_id"
                f" WHERE u.email = '{U1['email']}' ORDER BY p.created_at DESC LIMIT 1")
ajeno("SEC-IDOR-04", "user2 no confirma la recarga de wallet de user1",
      "POST", f"/api/me/wallet/recharge/{pago_u1}/confirm", pago_u1, body={})

ajeno("SEC-IDOR-05", "user2 no descarga la factura del pedido de user1",
      "GET", f"/api/me/orders/{pedido}/invoice.pdf", pedido)

clave = R.get("apiKey")
ajeno("SEC-IDOR-06", "user2 no revoca la clave de API de user1",
      "DELETE", f"/api/me/api-keys/{clave}", clave)

codigo = R.get("codigoAfiliado")
# El parámetro `active` va en la query y es obligatorio: sin él la petición muere en la validación (400) y
# el caso no probaría nada. Se envía para que la petición llegue de verdad a la comprobación de propiedad.
ajeno("SEC-IDOR-07", "user2 no desactiva el código de afiliado de user1",
      "POST", f"/api/me/affiliate/codes/{codigo}/toggle?active=false", codigo, body={})

sourcing = R.get("sourcing")
ajeno("SEC-IDOR-09a", "user2 no lee la petición de sourcing de user1",
      "GET", f"/api/me/sourcing/requests/{sourcing}", sourcing)
ajeno("SEC-IDOR-09b", "user2 no cancela la petición de sourcing de user1",
      "POST", f"/api/me/sourcing/requests/{sourcing}/cancel", sourcing, body={})
ajeno("SEC-IDOR-09c", "user2 no borra la petición de sourcing de user1",
      "DELETE", f"/api/me/sourcing/requests/{sourcing}", sourcing)

# Favoritos y carrito guardado no llevan id ajeno en la ruta: se resuelven por el usuario del token. Lo
# que hay que comprobar es que a user2 no le aparezca lo de user1.
fav_u2 = h.call("GET", "/api/me/favorites/ids", token=U2["token"])
ids_u2 = fav_u2.json if isinstance(fav_u2.json, list) else (fav_u2.json or {}).get("ids", [])
r.case("SEC-IDOR-08a", "los favoritos de user1 no aparecen en los de user2",
       R.get("favorito") is None or (A.get("producto") or {}).get("id") not in (ids_u2 or []),
       f"favoritos de u2 = {ids_u2}")

cart_u2 = h.call("GET", "/api/me/saved-cart", token=U2["token"])
lineas_u2 = cart_u2.json if isinstance(cart_u2.json, list) else []
r.case("SEC-IDOR-08b", "el carrito guardado de user1 no aparece en el de user2",
       len(lineas_u2) == 0, f"líneas en el carrito guardado de u2 = {len(lineas_u2)}")

# Facturas. Son DOS superficies distintas y hasta ahora no se comprobaba ninguna: el caso consultaba
# `invoice.order_id` y `invoice.number`, columnas que no existen —esa tabla es la de las suscripciones—,
# así que la consulta salía vacía y el caso se saltaba solo, en silencio, en cada certificación.
#
#   · la factura del PEDIDO va por el id del pedido      /api/me/orders/{id}/invoice.pdf
#   · la factura del PLAN va por el número de la factura  /api/me/billing/invoices/{number}/invoice.pdf
#
# Las dos claves son adivinables —un UUID se filtra, y los números de factura son correlativos—, así que
# en ambas el servidor tiene que comprobar quién pide, no confiar en que nadie sepa la clave.
pedido_u1 = R.get("pedido")
if pedido_u1:
    ajeno("SEC-IDOR-11", "user2 no descarga la factura del PEDIDO de user1",
          "GET", f"/api/me/orders/{pedido_u1}/invoice.pdf", pedido_u1)
else:
    r.skip("SEC-IDOR-11", "user2 no descarga la factura del PEDIDO de user1",
           "user1 no llegó a tener pedido en este montaje")

numero = h.sql(f"SELECT stripe_invoice_id FROM invoice WHERE user_id = '{U1['id']}'"
               f" AND stripe_invoice_id IS NOT NULL ORDER BY created_at DESC LIMIT 1")
if not numero:
    # Si user1 no tiene suscripción, sirve la de CUALQUIER otro usuario: lo que se prueba es justamente
    # que user2 no pueda bajarse una factura que no es suya.
    numero = h.sql(f"SELECT stripe_invoice_id FROM invoice WHERE user_id <> '{U2['id']}'"
                   f" AND stripe_invoice_id IS NOT NULL ORDER BY created_at DESC LIMIT 1")
if numero:
    ajeno("SEC-IDOR-11b", "user2 no descarga la factura del PLAN de otro usuario",
          "GET", f"/api/me/billing/invoices/{numero}/invoice.pdf", numero)
else:
    r.skip("SEC-IDOR-11b", "user2 no descarga la factura del PLAN de otro usuario",
           "no hay ninguna factura de plan en la base")

r.report("sec_idor")
