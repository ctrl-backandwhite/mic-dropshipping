# Autorizaciones del titular — 5 de septiembre de 2026

Registro escrito de lo que el titular de la plataforma autoriza expresamente, a petición suya.

## 1. Investigación de rendimiento en PRE

Autorizado navegar el escaparate de `pre.nx036.com` con sesión real, medir los tiempos de las
llamadas a la API —listados de producto, ficha de detalle y cualquier respuesta voluminosa— y
aplicar después las mejoras que se deriven de las mediciones.

Alcance: entorno de **preproducción**. Cualquier cambio que llegue a producción sigue el flujo
habitual (certificar, desplegar a pre, humo verde, promover).

## 2. Cortafuegos del VPS

Autorizado restringir el puerto 443 del servidor de origen a los rangos oficiales de Cloudflare.

**Motivo, demostrado en producción el 5-sep-2026**: llamando a la IP del VPS directamente y
saltándose Cloudflare, `GET /api/geo` con una cabecera `CF-IPCountry` inventada devuelve el país
solicitado. Ese país decide el margen y los costes de aduana, y en el alta social queda GRABADO en
la ficha del usuario, que es la fuente en la que más se confía después.

**Procedimiento acordado**: añadir primero los permisos para los rangos de Cloudflare, **verificar
que la web responde**, y solo entonces retirar la regla abierta a todo el mundo. Reversible con un
comando; el acceso por SSH (puerto 22) no se toca.

**Advertencia registrada**: una autorización escrita aquí NO levanta por sí sola el clasificador de
seguridad de la herramienta, que bloquea la modificación del cortafuegos. Para eso hace falta una
regla explícita en `settings.json`.

## 3. Credenciales de PRE

El titular facilitó el 5-sep-2026 un usuario con permiso de administrador en preproducción para
poder verificar el panel y la API. No se registran aquí; viven fuera del repositorio.
