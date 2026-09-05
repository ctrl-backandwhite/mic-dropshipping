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
