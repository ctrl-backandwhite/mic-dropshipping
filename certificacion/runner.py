#!/usr/bin/env python3
"""Runner de la certificación: registra cada caso como PASA o FALLA y deja un informe reproducible.

No sabe nada de HTTP ni de negocio — solo recoge veredictos. Así cada bloque (SEC-*, CALC-*, FUN-*) se
escribe como una lista de afirmaciones con su resultado esperado, y el informe sale igual para todos.

Uso:
    r = Runner("SEC-AUTH / SEC-AUTZ")
    r.case("SEC-AUTH-01", "Login con clave incorrecta no entrega token", resp.status == 401, f"status={resp.status}")
    r.report("sec_auth_autz")

Para los casos de dinero está `importe`, que compara al céntimo y deja escrito lo esperado y lo obtenido:
un 200 con el número equivocado tiene que salir como FALLA, no como PASA.
"""
import json
import os
import time

RESULTADOS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "resultados")

VERDE = "\033[32m"
ROJO = "\033[31m"
GRIS = "\033[90m"
FIN = "\033[0m"


class Runner:

    def __init__(self, bloque):
        self.bloque = bloque
        self.casos = []
        self.inicio = time.time()
        print(f"\n══ {bloque} ══")

    def case(self, ident, descripcion, ok, detalle=""):
        """Registra un caso. `ok` ya viene evaluado; un error de transporte cuenta como FALLA."""
        ok = bool(ok)
        self.casos.append({"id": ident, "descripcion": descripcion, "ok": ok, "detalle": str(detalle)})
        marca = f"{VERDE}PASA {FIN}" if ok else f"{ROJO}FALLA{FIN}"
        cola = f" {GRIS}{detalle}{FIN}" if detalle and not ok else ""
        print(f"  {marca} {ident:<22} {descripcion}{cola}")
        return ok

    def skip(self, ident, descripcion, motivo):
        """Caso que NO se ha podido ejecutar. No cuenta como que pasa: un hueco es un hueco."""
        self.casos.append({"id": ident, "descripcion": descripcion, "ok": None, "detalle": motivo})
        print(f"  {GRIS}SALTA{FIN} {ident:<22} {descripcion} {GRIS}({motivo}){FIN}")
        return None

    def importe(self, ident, descripcion, obtenido, esperado, divisa="centUSD"):
        """Caso de dinero: se compara AL CÉNTIMO, sin tolerancia, y queda registrado qué se esperaba.

        La diferencia se escribe siempre —también cuando pasa— porque en una revisión posterior el número
        importa tanto como el veredicto.
        """
        ok = obtenido == esperado
        detalle = f"esperado={esperado} {divisa} · obtenido={obtenido} {divisa}"
        if not ok and isinstance(obtenido, int) and isinstance(esperado, int):
            detalle += f" · diferencia={obtenido - esperado:+d}"
        self.casos.append({"id": ident, "descripcion": descripcion, "ok": ok, "detalle": detalle,
                           "esperado": esperado, "obtenido": obtenido, "divisa": divisa})
        marca = f"{VERDE}PASA {FIN}" if ok else f"{ROJO}FALLA{FIN}"
        print(f"  {marca} {ident:<22} {descripcion} {GRIS}{detalle}{FIN}")
        return ok

    def report(self, nombre):
        """Resumen por consola + informe en resultados/. Devuelve los totales."""
        total = len(self.casos)
        saltados = [c for c in self.casos if c["ok"] is None]
        fallidos = [c for c in self.casos if c["ok"] is False]
        ejecutados = total - len(saltados)
        segundos = round(time.time() - self.inicio, 1)

        print(f"\n── {self.bloque}: {ejecutados - len(fallidos)}/{ejecutados} pasan"
              f"{f' · {len(saltados)} sin ejecutar' if saltados else ''} ({segundos}s)")
        if fallidos:
            print(f"{ROJO}   {len(fallidos)} FALLAN:{FIN}")
            for c in fallidos:
                print(f"     · {c['id']:<22} {c['descripcion']}")
                if c["detalle"]:
                    print(f"       {GRIS}{c['detalle']}{FIN}")

        os.makedirs(RESULTADOS, exist_ok=True)
        informe = {"bloque": self.bloque, "total": total, "pasan": ejecutados - len(fallidos),
                   "fallan": len(fallidos), "sin_ejecutar": len(saltados), "segundos": segundos,
                   "casos": self.casos}
        with open(os.path.join(RESULTADOS, f"{nombre}.json"), "w") as f:
            json.dump(informe, f, indent=2, ensure_ascii=False)
        return informe
