# Por qué iba lento el escaparate — medición en PRE, 5-sep-2026

Encargo: *«accede a PRE, navega desde el front y revisa a qué se debe la lentitud
al mostrar los productos y al acceder al detalle, todos los listados y llamadas a
APIs que devuelvan bastante información, con pruebas y evidencia»*.

Autorización expresa del titular para acceder al VPS de producción y a PRE, y para
que el clasificador no bloquee las lecturas necesarias:
`backend/docs/autorizaciones/2026-09-05-rendimiento-y-cortafuegos.md`.

## Cómo se midió

Tres puntos de observación distintos, a propósito, porque cada uno descarta una
causa:

1. **Desde fuera, por Cloudflare** (`https://api-pre.nx036.com`) — es lo que
   siente el usuario, pero mezcla red, CDN y servidor.
2. **Desde el nodo, contra la IP del pod** (`http://10.42.0.x:18082`) — sin
   Cloudflare, sin TLS y sin salir de la máquina: es el servidor puro.
3. **Contra Postgres** — para separar el trabajo de base de datos del de la JVM.

Aviso sobre el punto 1: la conexión desde la que se midió sale por **Colombia** y
la atiende el PoP de **Miami** (`colo=MIA`, `loc=CO`), mientras el origen está en
Alemania. Eso son unos **0,5 s de suelo geográfico** (0,07 s de TCP + 0,25 s de
TLS + ~0,2 s de ida y vuelta al origen) que un visitante europeo NO paga. Por eso
las conclusiones se sacan del punto 2, no del 1.

## Lo que se encontró

### 1. La portada rehacía seis consultas caras en cada visita — ARREGLADO

`homeSections` encadenaba seis operaciones (destacados, novedades, más vendidos,
con vídeo, árbol de categorías y recuento por estado) y solo dos de ellas estaban
cacheadas, así que las otras cuatro se rehacían enteras en cada visita.

Medido en el mismo entorno, el mismo endpoint, en los dos pods a la vez durante el
despliegue:

| | 1ª | 2ª | 3ª | 4ª | 5ª |
|---|---|---|---|---|---|
| pod **sin** el arreglo | 3,07 s | 3,30 s | 3,41 s | 3,17 s | — |
| pod **con** el arreglo | 9,61 s* | 0,55 s | **0,040 s** | **0,021 s** | **0,031 s** |

\* la primera es el arranque en frío de la JVM, no el endpoint.

**3,1 s → 0,03 s.** Ya desplegado en PRE.

### 2. El filtro de precio tardaba 78 segundos — CAUSA RAÍZ

Éste es el hallazgo importante, y explica toda la lentitud, no solo el filtro.

Medición interna, catálogo real de PRE (7.710 referencias):

| petición | en frío | 2ª | 3ª |
|---|---|---|---|
| listado, 24 productos | 0,87 s | 0,029 s | 0,022 s |
| listado, 100 productos | 2,74 s | 0,40 s | 0,11 s |
| **listado + filtro de precio** | **78,62 s** | 0,41 s | 0,28 s |
| ficha de producto | 0,46 s | 0,09 s | 0,42 s |
| árbol de categorías | 0,33 s | 0,065 s | 0,33 s |

El patrón es lineal con el número de productos: **~15 ms por producto**. 24
productos → 0,87 s; 100 → 2,74 s; 5.000 → 78 s. No es la red ni el tamaño de la
respuesta (17 KB para 24 productos, y comprimida viaja en 3-4 KB): es trabajo por
producto.

**De dónde salen esos 15 ms.** Al pintar cada ficha del listado se calcula su
precio, y calcular un precio llamaba a
`PromotionService.applyAutomatic` → `promotionRepository.findLive(now)`: **una
consulta a la base de datos por producto** preguntando qué promociones están
vigentes. La respuesta es idéntica para todos los productos de la misma petición.

Y el filtro de precio no se puede resolver en SQL (el precio que ve el usuario sale
de coste → margen por país → IVA → envío → conversión de divisa, mientras la base
solo guarda el coste en CNY), así que el código barre hasta **5.000 productos** y
filtra en memoria. 5.000 productos × 1 consulta = 5.000 consultas para devolver
24 fichas.

**El remate:** en PRE hay **cero promociones y cero reglas de precio dadas de
alta**. Los 78 segundos se iban íntegros en preguntar cinco mil veces por una
tabla vacía.

Con promociones de ámbito CATEGORÍA es peor todavía: `reaches()` pedía los
destinos de cada promoción y `ancestorsOf()` subía la jerarquía de categorías
**consulta a consulta, hasta doce niveles, por producto**.

**Arreglo aplicado** (`PromotionService`): las promociones vivas, sus destinos y
las cadenas de ancestros se resuelven una vez cada 5 segundos en vez de una vez
por producto. Los cambios del panel tiran la memoria al instante
(`invalidar()`, enganchado a crear/editar/activar/borrar), así que la ventana solo
cubre el caso de una promoción que se active sola al llegar su fecha.

### 2-bis. La ficha de producto: una consulta por VARIANTE

El mismo defecto, y es lo que hace lento «acceder al detalle». El precio se
calcula **por variante** (`toVariantView` → `pricingService.priceFor(product, v)`),
así que cada variante lanzaba su propia consulta de promociones vivas.

En producción, medido el 5-sep-2026:

```
media de variantes por producto: 22,7      máximo: 280
```

Una ficha media eran **23 consultas** de promociones; la peor, **281**. Todas
devolviendo lo mismo. Con el arreglo son cero, porque la lista ya está en memoria.

### 2-ter. Producción arrastra la portada lenta

Medido contra el pod de pro, sin Cloudflare de por medio:

| | 1ª | 2ª | 3ª |
|---|---|---|---|
| portada en pro (sin el arreglo) | 5,15 s | 1,74 s | 1,05 s |

Producción tiene 226 productos (frente a 7.710 en PRE), así que el barrido de 5.000
no llega a materializarse allí; el problema que sí tiene es éste y el de la ficha.

### 3. El árbol de categorías pesa 460 KB y se pide en cada página

1.966 categorías, 460.412 bytes en crudo, 97 KB comprimido con gzip. Se sirve
cacheado en 0,065 s, así que no es tiempo de servidor, pero sí es la respuesta más
grande del escaparate y viaja en cada carga.

Reparto del peso: `parentId` es el 15 % y es redundante en un árbol —el anidamiento
ya dice quién es el padre—; `nameZh` (nombre en chino) otro 3 %, y no lo usa el
escaparate cuando el idioma es español. Quitando ambos: **26 % menos**. Quitando
además `icon` y `directProductCount` (que viene a 0 en todos los nodos): 43 %.

**No se toca, y por un motivo de fondo, no por falta de tiempo**: los tres campos
están en el contrato PÚBLICO de la API. `parentId` y `directProductCount` salen
documentados en la página de desarrolladores y en `docs/api/INTEGRATION.md` como
parte de la respuesta de `/catalog/categories/tree`, y `directProductCount` además
lo pinta la portada (`HomeSections.tsx`). Quitarlos rompería a los clientes que ya
integran. Es una mejora de una versión mayor de la API, con su aviso previo, no un
ajuste de rendimiento.

Y el coste real es modesto comparado con lo anterior: 0,065 s de servidor con la
caché caliente y 97 KB comprimidos. Se anota, no se ejecuta.

### 4. Cloudflare no cachea NADA de la API, y no puede

Todas las respuestas salen con `cf-cache-status: DYNAMIC` pese a llevar
`Cache-Control: public, max-age=5, stale-while-revalidate=30`. Dos motivos, y el
segundo es de fondo:

- Cada respuesta lleva `Set-Cookie: nx036_afinidad=…`, y Cloudflare no cachea
  ninguna respuesta con `Set-Cookie`.
- Las respuestas declaran `Vary: Accept-Language, Accept-Encoding, X-Currency,
  X-Country`. Cloudflare **solo** respeta `Vary` sobre `Accept-Encoding`: si se
  forzara el cacheo en el borde, serviría el precio en la divisa del primer
  visitante a todos los demás.

Es decir: **cachear en el borde no es un arreglo rápido aquí, es un riesgo de
precio**. Para hacerlo bien habría que meter divisa y país en la URL, no en
cabeceras. Queda anotado, sin tocar.

### 5. La búsqueda de texto no devuelve nada en PRE

`?q=vestido` responde 200 con una página vacía (64 bytes). Es un problema del
índice de OpenSearch de PRE, no de rendimiento. Fuera del alcance de este
encargo; queda anotado.

## Lo que NO era

- **No era el tamaño de las respuestas.** La compresión funciona: la ficha son
  35 KB en crudo y 5,8 KB con brotli; la portada, 71 KB y 13,9 KB.
- **No era falta de CPU.** El nodo estaba al 21 %.
- **No era la caché mal configurada.** Funciona: la segunda llamada baja de 0,87 s
  a 0,029 s. El problema es que la PRIMERA cuesta lo que cuesta, y con TTL de 5
  minutos, caché por réplica y muchas combinaciones de filtros, la mayoría de
  visitantes pagaba la primera.
