#!/usr/bin/env python3
"""Fase 0: deja preparados los actores con token y RECURSOS PROPIOS.

Los recursos propios son imprescindibles: sin un pedido/dirección/wallet que pertenezca de verdad a
user1, los casos de acceso a recursos ajenos no prueban nada (un 404 podría ser simplemente "no existe").
"""
import json
import os
import sys
import time

import harness as h

# Contraseña de los actores de prueba que este script CREA en el entorno local. No es una credencial
# de ningún entorno real, pero se deja parametrizable para no fijar una constante de acceso en el
# repositorio: CERT_PASS la sobreescribe.
PASS = os.environ.get("CERT_PASS", "CertPass123!")
OUT = "actores.json"


def crear_usuario(etiqueta, ts):
    email = f"cert-{etiqueta}-{ts}@example.com"
    r = h.register(email, PASS)
    if r.status not in (200, 201):
        print(f"  ! registro de {etiqueta} devolvió {r.status}: {r.body[:160]}")
        return None
    a = h.activate(email)
    if a is None or a.status not in (200, 204):
        print(f"  ! activación de {etiqueta}: {a.status if a else 'sin token'}")
    tok = h.token_of(email, PASS)
    if not tok:
        print(f"  ! login de {etiqueta} falló")
        return None
    uid = h.sql(f"SELECT id FROM users WHERE email='{email}'")
    print(f"  · {etiqueta}: {email}")
    return {"email": email, "password": PASS, "token": tok, "id": uid}


def main():
    ts = int(time.time())
    actores = {}

    print("Creando usuarios normales…")
    for etiqueta in ("u1", "u2"):
        a = crear_usuario(etiqueta, ts)
        if not a:
            sys.exit(f"No se pudo preparar {etiqueta}")
        actores[etiqueta] = a

    print("Preparando operador…")
    op = crear_usuario("op", ts)
    if op:
        # El rol lo asigna la plataforma, no el registro público.
        h.sql(f"UPDATE users SET role='OPERATOR' WHERE email='{op['email']}'")
        op["token"] = h.token_of(op["email"], PASS)
        op["role"] = "OPERATOR"
        actores["operator"] = op

    print("Comprobando admin…")
    admin_tok = h.token_of("admin@nx036.local", "Admin123!")
    if not admin_tok:
        sys.exit("No hay admin local disponible (admin@nx036.local)")
    actores["admin"] = {"email": "admin@nx036.local", "token": admin_tok,
                        "id": h.sql("SELECT id FROM users WHERE email='admin@nx036.local'")}

    print("Creando recursos propios de user1…")
    u1 = actores["u1"]
    r = h.call("POST", "/api/me/addresses", token=u1["token"], body={
        "fullName": "Cert Uno", "line1": "Calle Falsa 123", "city": "Madrid", "postalCode": "28001",
        "country": "ES", "phone": "+34600000001", "isDefault": True})
    u1["addressId"] = (r.json or {}).get("id") if r.status in (200, 201) else None
    print(f"  · dirección: {r.status} → {u1['addressId']}")

    prod = h.call("GET", "/api/catalog/products?size=1&lang=es", token=u1["token"])
    items = (prod.json or {}).get("items", [])
    actores["producto"] = items[0] if items else None
    print(f"  · producto de referencia: {(actores['producto'] or {}).get('slug')}")

    with open(OUT, "w") as f:
        json.dump(actores, f, indent=2)
    print(f"\nActores guardados en {OUT}")
    for k, v in actores.items():
        if isinstance(v, dict) and "token" in v:
            print(f"  {k:9} id={v.get('id','?')[:8]}… token={'sí' if v['token'] else 'NO'}")


if __name__ == "__main__":
    main()
