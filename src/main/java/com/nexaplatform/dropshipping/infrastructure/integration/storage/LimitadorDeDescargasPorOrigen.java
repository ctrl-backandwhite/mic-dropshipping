package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Pone un techo a cuántas descargas se le piden A LA VEZ a un mismo proveedor.
 *
 * <p>Por qué existe: el cuello de botella del espejado no es nuestra máquina. Medido en preproducción
 * el 18-sep-2026, el nodo estaba al 16% de CPU y el pod sin límite mientras el espejado avanzaba a
 * ~4.200 imágenes/hora. Lo que se está esperando es la red del proveedor.
 *
 * <p>La conclusión tentadora —subir la concurrencia hasta que deje de mejorar— tiene un final malo:
 * 1688 responde 403 a quien enlaza sus imágenes desde otra web y limita por tasa a quien insiste. Si
 * se escala sin techo, el problema deja de ser de rendimiento y pasa a ser de acceso: el proveedor
 * corta, y entonces no hay catálogo que espejar a ninguna velocidad.
 *
 * <p>El techo es POR HOST y no global a secas, porque un proveedor lento no tiene por qué frenar las
 * descargas de otro.
 *
 * <p><b>Alcance real del límite, que conviene no malinterpretar:</b> esto limita por PROCESO. Con N
 * réplicas del espejado corriendo, el proveedor ve hasta {@code N × permisos} descargas simultáneas.
 * El número a ajustar es ese producto, no el de aquí suelto. Se hace así a propósito: un limitador
 * repartido exigiría coordinarse por Redis en el camino crítico de cada descarga, y el Redis repartido
 * de esta plataforma ya dio problemas. Mientras el espejado viva en un despliegue con réplicas fijas,
 * multiplicar dos números conocidos es más fiable que una coordinación que puede caerse.
 */
@Slf4j
@Component
public class LimitadorDeDescargasPorOrigen {

    /** Un semáforo por host. Se crean solos: los proveedores no se conocen de antemano. */
    private final Map<String, Semaphore> porHost = new ConcurrentHashMap<>();

    private final int permisosPorHost;
    private final long esperaMaximaSegundos;

    public LimitadorDeDescargasPorOrigen(
            @Value("${nexadrop.storage.descargas-por-origen:6}") int permisosPorHost,
            @Value("${nexadrop.storage.espera-maxima-permiso-segundos:120}") long esperaMaximaSegundos) {
        this.permisosPorHost = Math.max(1, permisosPorHost);
        this.esperaMaximaSegundos = Math.max(1, esperaMaximaSegundos);
    }

    /**
     * Ejecuta la descarga con un permiso del host de esa URL, y lo devuelve pase lo que pase.
     *
     * <p>La espera tiene tope. Sin él, un proveedor que dejara de responder acabaría con todos los
     * hilos del espejado parados en su puerta y ninguna otra imagen avanzaría — un proveedor caído
     * pararía el catálogo entero en vez de solo sus fotos.
     */
    public <T> T conPermiso(String url, Callable<T> descarga) throws Exception {
        String host = hostDe(url);
        if (host == null) {
            return descarga.call();
        }
        Semaphore semaforo = porHost.computeIfAbsent(host, h -> new Semaphore(permisosPorHost, true));
        if (!semaforo.tryAcquire(esperaMaximaSegundos, TimeUnit.SECONDS)) {
            throw new IllegalStateException("No hubo turno para descargar de " + host + " en "
                    + esperaMaximaSegundos + "s; la imagen vuelve a la cola");
        }
        try {
            return descarga.call();
        } finally {
            semaforo.release();
        }
    }

    /** Cuántas descargas hay ahora mismo en vuelo contra cada proveedor. Para diagnosticar, no para decidir. */
    public Map<String, Integer> enVuelo() {
        Map<String, Integer> foto = new ConcurrentHashMap<>();
        porHost.forEach((host, s) -> foto.put(host, permisosPorHost - s.availablePermits()));
        return foto;
    }

    /**
     * El host de la URL, en minúsculas. Una URL que no se puede analizar no se bloquea: se deja pasar
     * sin límite. Perder el techo en un caso raro es preferible a no espejar la imagen.
     */
    private static String hostDe(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            log.debug("No se pudo sacar el host de {}: {}", url, e.toString());
            return null;
        }
    }
}
