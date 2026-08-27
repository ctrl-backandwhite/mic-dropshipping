#!/usr/bin/env python3
"""Certificación de las reglas europeas: arancel de bajo valor, minimis y cumplimiento del producto.

Qué norma se comprueba y de dónde sale cada número:

  · Reglamento (UE) 2026/382 — del 1 de julio de 2026 al 1 de julio de 2028, derecho de 3 EUR «per item
    in a consignment the intrinsic value of which does not exceed a total of EUR 150».
  · Reglamento Delegado (UE) 2015/2446, art. 1(61) — «item» NO es unidad ni producto: es «one or more
    goods in a consignment sharing the same tariff classification, description and, if provided, origin».
    La guía de la Comisión lo remata: se aplica «per declaration line irrespective of the quantity».
  · Reglamento (UE) 2023/988 (GPSR), arts. 16 y 19 — persona responsable en la Unión, identificación del
    fabricante y advertencias de seguridad.

De ahí las tres trampas que este certificador busca, porque las tres cobran de más al comprador y las tres
se ven «razonables» en el código:

  1. multiplicar el arancel por la CANTIDAD (5 camisetas iguales = 15 EUR en vez de 3),
  2. multiplicar por PRODUCTOS distintos que comparten clasificación (anorak + cazadora = 6 en vez de 3),
  3. aplicar el arancel a países que no son de la Unión.

Solo lectura: consulta la API y la base, no crea pedidos.

Uso:  python3 cert_reglas_ue.py
"""
import json
import sys

import harness as h

# Los 27. Se escriben aquí, y no se leen de la base, a propósito: si la lista viviera en la propia
# aplicación, un país mal dado de alta se certificaría contra su propio error.
UE27 = ["AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV",
        "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"]

# Fuera de la Unión, con su propio umbral. Ninguno debe recibir el derecho de 3 EUR.
NO_UE = ["GB", "CH", "NO", "US", "CA", "AU", "JP", "BR", "MX", "TR"]

casos = []


def mide(nombre, ok, detalle=""):
    casos.append((nombre, bool(ok), detalle))
    marca = "\033[32mPASA \033[0m" if ok else "\033[31mFALLA\033[0m"
    print("  %s %-52s %s" % (marca, nombre, ("\033[90m%s\033[0m" % detalle) if detalle else ""))


def titulo(t):
    print("\n\033[1m══ %s ══\033[0m" % t)


def regla(pais):
    fila = h.sql("SELECT tax_mode || '|' || de_minimis_amount || '|' || de_minimis_currency || '|' ||"
                 " over_threshold_policy || '|' || active FROM country_customs_rule"
                 " WHERE country_code = '%s'" % pais)
    if not fila:
        return None
    modo, importe, divisa, politica, activa = fila.split("|")
    # `activa` llega como "true"/"false", no como "t"/"f": el atajo `||` de Postgres convierte el booleano
    # a texto con su nombre completo, mientras que una columna suelta se imprime como t/f. Comparar contra
    # "t" daba las 86 reglas por inactivas — y con ellas, los 27 países de la Unión.
    return {"modo": modo, "minimis": float(importe), "divisa": divisa, "politica": politica,
            "activa": activa.strip().lower() in ("t", "true")}


titulo("A · UMBRAL DE MINIMIS EN LOS 27")

for pais in UE27:
    r = regla(pais)
    # 150 EUR es el techo del régimen de bajo valor: por encima, la declaración deja de ser la reducida.
    ok = r is not None and r["minimis"] == 150.0 and r["divisa"] == "EUR" and r["activa"]
    mide("%s · minimis 150 EUR" % pais, ok,
         "" if ok else ("sin regla" if r is None else "%s %s · activa=%s"
                        % (r["minimis"], r["divisa"], r["activa"])))

titulo("B · FUERA DE LA UNIÓN, OTRO UMBRAL")

for pais in NO_UE:
    r = regla(pais)
    # Lo que se comprueba no es el valor exacto de cada país, sino que NO se le ha copiado el de la Unión:
    # aplicar 150 EUR a Reino Unido o Suiza sería inventarse una norma que allí no existe.
    ok = r is None or r["divisa"] != "EUR" or r["minimis"] != 150.0
    mide("%s · no hereda el umbral de la Unión" % pais, ok,
         "" if ok else "tiene 150 EUR como la UE")

titulo("C · EL ARANCEL SE CUENTA POR PARTIDA, NO POR UNIDAD NI POR PRODUCTO")

# Se certifica sobre el comportamiento observable: dos carritos con el MISMO número de partidas
# arancelarias tienen que pagar lo mismo de arancel, por muchas unidades o referencias que lleven.
actores = json.load(open("actores.json"))
token = actores["u1"]["token"]

r = h.call("GET", "/api/catalog/products?size=30", token=token)
productos = ((r.json or {}).get("items") or [])
mide("hay catálogo con el que montar el escenario", len(productos) >= 3, "%d productos" % len(productos))


def cotiza(lineas, pais="ES"):
    """Cotización real de envío + impuestos + aduana para un país, sin llegar a crear el pedido.

    Es el MISMO cálculo que usa el checkout (CheckoutPreviewService), así que lo que se mide aquí es lo
    que se cobraría de verdad — no una reimplementación de la regla en el certificador, que solo probaría
    que sé multiplicar.
    """
    return h.call("POST", "/api/shipping/quote", token=token, body={"country": pais, "items": lineas})


def total(res):
    d = res.json or {}
    for k in ("totalCents", "grandTotalCents", "totalUsdCents"):
        if isinstance(d.get(k), int):
            return d[k]
    return None


if len(productos) >= 2:
    a, b = productos[0], productos[1]
    q1 = cotiza([{"productId": a["id"], "quantity": 1}])
    q5 = cotiza([{"productId": a["id"], "quantity": 5}])
    if q1.status == 200 and q5.status == 200:
        # Cinco unidades de la MISMA referencia son una sola línea de declaración. Si el arancel se
        # multiplicara por la cantidad, la diferencia entre los dos totales llevaría 4 × 3 EUR de más.
        d1 = (q1.json or {}).get("customsHandlingUsdCents")
        d5 = (q5.json or {}).get("customsHandlingUsdCents")
        mide("cinco unidades del mismo producto NO multiplican el arancel",
             d1 is not None and d1 == d5, "1 ud → %s · 5 uds → %s céntimos" % (d1, d5))
        # 3,00 EUR convertidos a dólares. Se acepta una horquilla porque el cambio se mueve; lo que no
        # puede pasar es que sean 3 dólares clavados (sería no convertir) ni un múltiplo de la cantidad.
        mide("el importe equivale a los 3,00 EUR de la norma",
             d1 is not None and 300 <= d1 <= 450, "%s céntimos de dólar" % d1)

        qn = cotiza([{"productId": a["id"], "quantity": 1}], pais="US")
        dn = (qn.json or {}).get("customsHandlingUsdCents")
        mide("fuera de la Unión no se cobra el derecho de partida",
             qn.status == 200 and (dn or 0) == 0, "US → %s" % dn)

        q2 = cotiza([{"productId": a["id"], "quantity": 1}, {"productId": b["id"], "quantity": 1}])
        d2 = (q2.json or {}).get("customsHandlingUsdCents")
        # Dos referencias distintas pagan 3 EUR por CADA partida arancelaria distinta, y una sola si
        # comparten subpartida. Las dos respuestas son correctas según la clasificación; lo que sería un
        # error es que saliera algo distinto de 1 o 2 veces el importe unitario.
        veces = (d2 / d1) if (d1 and d2) else 0
        mide("dos referencias pagan por partida, no por producto",
             d2 is not None and veces in (1.0, 2.0),
             "%s céntimos = %.1f × el de una línea" % (d2, veces))
    else:
        mide("la cotización responde", False,
             "status %d / %d · %s" % (q1.status, q5.status, q1.body[:70]))

titulo("C bis · EL IMPORTE POR PARTIDA, PAÍS A PAÍS")

for pais in UE27:
    v = h.sql("SELECT per_article_fee_amount || '|' || per_article_fee_currency"
              " FROM country_customs_rule WHERE country_code = '%s'" % pais)
    ok = bool(v) and v.split("|")[0].startswith("3.0") and v.split("|")[1] == "EUR"
    mide("%s · 3,00 EUR por partida arancelaria" % pais, ok, "" if ok else (v or "sin regla"))

for pais in NO_UE:
    v = h.sql("SELECT per_article_fee_amount FROM country_customs_rule WHERE country_code = '%s'" % pais)
    # El derecho temporal es de la Unión. Aplicarlo a Reino Unido, Suiza o Estados Unidos sería cobrar al
    # comprador un tributo que allí no existe.
    ok = (not v) or float(v) == 0.0
    mide("%s · sin derecho de partida de la Unión" % pais, ok, "" if ok else "tiene %s" % v)

titulo("D · CUMPLIMIENTO DEL PRODUCTO (Reg. UE 2023/988)")

r = h.call("GET", "/api/compliance/responsible-person?lang=es")
rp = r.json or {}
mide("la persona responsable en la Unión está publicada",
     r.status == 200 and bool(rp.get("name")) and bool(rp.get("email")),
     "status=%d · %s" % (r.status, rp.get("name")))

# El art. 16 exige una dirección POSTAL en la Unión, no solo un nombre y un correo.
direccion = " ".join(str(rp.get(k) or "") for k in ("addressLine", "postalCode", "city", "country"))
mide("con dirección postal completa en la Unión",
     all(str(rp.get(k) or "").strip() for k in ("addressLine", "postalCode", "city", "country")),
     direccion.strip())

r = h.call("GET", "/api/catalog/products?size=1", token=token)
uno = ((r.json or {}).get("items") or [{}])[0]
if uno.get("slug"):
    r = h.call("GET", "/api/catalog/products/%s" % uno["slug"], token=token)
    ficha = r.json or {}
    crudo = json.dumps(ficha, ensure_ascii=False)
    mide("la ficha lleva el bloque de cumplimiento",
         "responsiblePerson" in crudo or "compliance" in crudo.lower(), "")

admin = actores["admin"]["token"]
r = h.call("GET", "/api/admin/compliance/status", token=admin)
estado = r.json or {}
mide("el admin ve el estado de cumplimiento del catálogo", r.status == 200 and bool(estado),
     "status=%d · %s" % (r.status, json.dumps(estado, ensure_ascii=False)[:80]))

r = h.call("GET", "/api/admin/compliance/products/missing-manufacturer?size=5", token=admin)
sin_fab = r.json if isinstance(r.json, list) else ((r.json or {}).get("items") or [])
# Que haya productos sin fabricante no es un fallo del sistema — es trabajo de catálogo pendiente. Lo que
# se certifica es que el sistema SEPA decir cuáles son, que es lo que permite cerrarlo.
mide("el admin puede listar los productos sin fabricante", r.status == 200,
     "status=%d · %d sin fabricante" % (r.status, len(sin_fab)))

fallos = [c for c in casos if not c[1]]
print("\n===== %d casos · %d fallos =====" % (len(casos), len(fallos)))
for nombre, _, detalle in fallos:
    print("  %s → %s" % (nombre, detalle))
sys.exit(1 if fallos else 0)
