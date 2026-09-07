# PRE se cayó entero: el codificador WebP nativo — 5-sep-2026

## Qué pasó

Las **dos réplicas de PRE** dejaron de servir a la vez. No fue una excepción, ni un
error de negocio, ni falta de memoria: fue la **JVM entera desapareciendo**.

```
# A fatal error has been detected by the Java Runtime Environment:
#
#  SIGSEGV (0xb) at pc=0x0000717fe7c04a15, pid=1, tid=469
#
# Problematic frame:
# C  [webp-imageio-0.9.0-...-libwebp-imageio.so+0x4a15]  encode+0x55
#
# The crash happened outside the Java Virtual Machine in native code.
```

Código de salida del contenedor: **139** (128 + SIGSEGV). Kubernetes lo reiniciaba,
la JVM volvía a arrancar, tardaba sus dos minutos en levantar Liquibase y el
contexto, y volvía a caer.

## Por qué

`libwebp-imageio` **no admite que varios hilos entren a la vez en `encode`**. La
compresión de imágenes corría con `STORAGE_MIRROR_CONCURRENCY=4`: cuatro hilos
codificando en paralelo dentro de la misma biblioteca nativa.

**Lo que lo hace grave, y no un simple fallo de una tarea de fondo:** un fallo de
segmento en código nativo **no se puede capturar desde Java**. No hay excepción que
atrapar, no hay `try/catch` que valga, no hay reintento. El proceso entero
desaparece, y con él todas las peticiones de clientes que estuviera atendiendo en
ese momento. Una tarea de fondo sin prisa se llevaba por delante el escaparate.

## La pista

El contraste entre entornos:

| entorno | hilos de compresión | reinicios |
|---|---|---|
| producción | 1 | **0** |
| PRE | 4 | varios |
| DES | 2 | 1 |

Producción llevaba un solo hilo desde el día anterior, por un motivo que no tenía
nada que ver (se había bajado para aliviar la CPU). Esa casualidad es la que
señaló la causa.

## Qué se hizo

1. **Cura de urgencia**: `STORAGE_MIRROR_CONCURRENCY=1` en PRE. Los reinicios
   pararon en cuanto rodó el cambio.
2. **Arreglo de fondo**: un cerrojo estático en `CompresorDeImagen` alrededor de la
   llamada nativa. Una imagen a la vez por JVM.

Serializar no cuesta donde importa: lo único que se serializa es la codificación,
mientras la descarga, la decodificación y el escalado —que es donde se va el
tiempo— siguen en paralelo. Y es trabajo de fondo: a esto no le espera ningún
comprador.

Con el cerrojo, `mirror-concurrency` vuelve a ser lo que aparentaba ser: un ajuste
de rendimiento. Antes era una bomba con la mecha encendida.

## Lo que hay que recordar

- **Una biblioteca nativa no respeta el contrato de Java.** Ni excepciones, ni
  aislamiento de hilos, ni recuperación. Toda llamada a JNI hay que tratarla como
  código que puede matar el proceso, no como una función más.
- **Un trabajo de fondo puede tumbar el primer plano.** Aquí no compartían más que
  la JVM, y bastó.
- **Comparar entornos es diagnóstico.** La diferencia de reinicios entre producción
  y PRE valía más que cualquier lectura del código.
- **El código de salida 139 no es memoria.** 137 es que lo mató el kernel por
  memoria (OOM); 139 es fallo de segmento. Confundirlos manda a buscar al sitio
  equivocado.

Pruebas que lo fijan: `CompresorConcurrenteTest` — que el cerrojo siga siendo
estático, y que ocho hilos comprimiendo a la vez devuelvan imágenes enteras.

---

# SEGUNDA CAÍDA, mismo día: el cerrojo no era la causa

Horas después de desplegar el cerrojo, **PRE volvió a caer**. Las dos réplicas, dos
veces cada una, mismo código 139 y mismo `encode+0x55`.

## Lo que descartó la explicación anterior

El registro previo a la caída enseña **un solo hilo**:

```
554 líneas [pool-8-thread-1] "Imagen comprimida: ..."
<SIGSEGV>
```

Un hilo, 554 imágenes bien, y entonces muere. **No era concurrencia.** El cerrojo
funcionaba y no servía de nada.

## Lo que también se descartó, con su medición

| hipótesis | por qué no |
|---|---|
| Contenido de las imágenes | Se descargaron las 100 del lote: 91 JPEG baseline de 3 componentes, 3 con Exif, 6 GIF de 1×1. Ninguna rara. |
| Memoria | 2,1-2,6 GB usados de 12,5 GB de límite. Y un OOM da código **137**, no 139. |
| La verificación de productos del admin | Cero llamadas del panel en el registro de ese pod. |
| Reproducción en local | 426 codificaciones seguidas con las mismas imágenes **sin caerse**. No se pudo reproducir. |

## Lo que convertía un fallo en una avería permanente

Esto es lo importante, y es independiente de la causa del fallo nativo.

`mirrorPendingBatch` coge las 100 imágenes PENDING más recientes y **marca el estado
DESPUÉS de procesarlas**. Hay un contador `mirror_attempts` con tope de 6, pero solo
se incrementa en fallos **capturados** — y un SIGSEGV no se captura.

Resultado: la imagen que mataba el proceso volvía **intacta** al siguiente lote. Y
al siguiente. Las dos réplicas entraron en un bucle de caídas del que el sistema no
podía salir solo.

## El arreglo, en tres capas independientes

1. **El intento se anota ANTES de procesar**, comprometido en el acto
   (`anotaIntentoAntesDeProcesar`). Si el proceso muere a mitad, la cuenta ya está
   guardada. El fallo capturado deja de contar por segunda vez (`markFailed`), para
   no gastar los seis reintentos al doble.

2. **Degradar en vez de morir.** Una imagen que ya falló se espeja **sin comprimir**
   (`CompresorDeImagen.sinComprimir`), conservando sus medidas. Aligerar una foto es
   una mejora; un proceso que se muere deja el escaparate sin servir. Como mucho un
   reinicio por imagen problemática, y esa imagen acaba igualmente en el catálogo.

3. **`webp-imageio` 0.9.0 → 0.11.0**, cuatro versiones de diferencia. Es la vía más
   probable para la causa raíz.

**Las dos primeras funcionan aunque la tercera no sirva de nada.** Se diseñaron así
a propósito: no se pudo reproducir el fallo nativo, así que no se puede *verificar*
que la actualización lo arregle — solo que el bucle ya no es posible.

## Lo que hay que recordar

- **Un arreglo que no se puede verificar no está verificado.** El cerrojo parecía
  razonable y no era la causa; solo la segunda caída lo demostró.
- **Cuando no se puede eliminar el fallo, hay que acotar su daño.** Anotar el intento
  antes y degradar la funcionalidad convierte «el sistema se cae para siempre» en
  «una foto se guarda sin comprimir».
- **Ojo con los datos**: seis de cada cien imágenes de la cola son el GIF de 1×1 y 49
  bytes que devuelve alicdn cuando la foto ya no existe. Se están espejando como
  fotos de producto.
