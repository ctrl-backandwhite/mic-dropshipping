# Verificación de seguridad — NX036

Todo se lanza desde aquí, con el stack local levantado:

```bash
./verificar.sh            # todo: pentest + estático + ZAP + dependencias
./verificar.sh pentest    # solo los bloques SEC-*  (~1 min)
./verificar.sh estatico   # SpotBugs + Find Security Bugs  (~3 min)
./verificar.sh zap        # ZAP autenticado contra API y front  (~5 min)
./verificar.sh deps       # OWASP Dependency-Check (CVE)
```

**Solo contra local.** Hay casos destructivos —borrados, reembolsos, doble gasto— que no deben tocar PRE.
El script se niega a arrancar si `API` no apunta a localhost.

## Qué hay montado

| Pieza | Para qué |
|---|---|
| `verificar.sh` | Punto de entrada único |
| `harness.py` | Cliente HTTP, resolución del CAPTCHA, alta de actores |
| `setup_actores.py` | Los 6 actores con token |
| `setup_recursos.py` | Recursos **reales** de user1 (pedido, dirección, wallet, clave de API, sourcing, afiliado) |
| `runner.py` | Registro de casos: PASA / FALLA / SIN EJECUTAR, e informes en `resultados/` |
| `sec_*.py` | Los bloques del plan |
| `generar_urls_api.py` | Extrae las rutas de la API del código para dárselas a ZAP |
| `PLAN_PENTEST_CERT.md` | El plan: casos, actores y resultados esperados |

## Dos reglas que sostienen todo esto

**1. Un caso que no se puede ejecutar NO cuenta como aprobado.** Por eso existe `runner.skip`: si el
recurso de user1 no se pudo crear, el caso sale como SIN EJECUTAR. Un hueco disfrazado de verde es peor
que un fallo, porque nadie vuelve a mirarlo.

**2. Los importes se comprueban al céntimo.** `runner.importe` compara contra un valor calculado a mano.
Un 200 con el número equivocado es un FALLO. Así se coló el arancel de la UE cobrado por producto en vez
de por partida: la suite entera en verde mientras el cliente pagaba de más.

## Cosas que aprendimos por las malas

- **ZAP sin token y sin semillas no prueba nada.** Alcanzaba tres páginas públicas y terminaba en verde.
  Por eso `verificar.sh zap` le inyecta el `Authorization` de un actor real y le pasa las 136 rutas que
  `generar_urls_api.py` saca del código. La especificación OpenAPI no sirve: `/v3/api-docs` responde 302
  hacia `/login`.
- **Los tests de concurrencia no pueden ir dentro de una transacción de test.** Los hilos usan conexiones
  distintas y no ven lo que no está confirmado (`WalletConcurrencyIT` lo documenta).
- **Dependency-Check necesita `NVD_API_KEY`** o la primera descarga tarda horas. Es gratuita.
- **Toda supresión en `dependency-check-suppressions.xml` lleva motivo, revisor y fecha.** Una supresión
  sin justificar es una vulnerabilidad escondida.
