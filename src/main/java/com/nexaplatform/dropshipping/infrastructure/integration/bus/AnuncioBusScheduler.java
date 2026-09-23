package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.BusAnuncioEstado;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Construye y publica al bus los productos que la petición dejó marcados.
 *
 * <p>Por qué existe: marcar un producto como verificado tardaba varios segundos, y aplicar un recargo
 * a un lote tardaba eso multiplicado por el número de productos. No era la espera a Kafka —el envío ya
 * iba diferido por la bandeja de salida— sino que la petición construía la FICHA ENTERA del evento
 * antes de responder: varias consultas más el mapeo de los ocho idiomas, las variantes, las imágenes y
 * las reseñas, todo serializado a JSON.
 *
 * <p>Ahora la petición solo deja una marca —el cambio de una columna— y este barrido hace el trabajo.
 * La marca se pone dentro de la MISMA transacción que el cambio, que es lo que garantiza que un
 * producto certificado no se quede sin anunciar: publicar después del commit habría sido más simple,
 * pero deja una ventana en la que el proceso se cae y el producto no llega nunca a producción sin que
 * nadie se entere.
 *
 * <p>Lo que no se puede publicar queda en {@link BusAnuncioEstado#FALLIDO} con su motivo a la vista,
 * porque un producto que no llega a producción tiene que verse; mientras le queden intentos sigue en
 * la cola.
 */
@Slf4j
@Service
public class AnuncioBusScheduler {

    private final ProductRepository productRepository;
    private final ObjectProvider<CatalogoBusService> busCatalogo;
    private final CatalogUseCase catalogUseCase;

    /**
     * Se publica dentro de una transacción propia, no en la del barrido, por dos motivos: la ficha y
     * la rama de categorías se leen con relaciones perezosas —fuera de sesión reventarían—, y la
     * marca de anunciado tiene que confirmarse junto con las filas de la bandeja de salida, o un
     * corte a mitad dejaría el producto por anunciado sin haberlo encolado.
     */
    private final TransactionTemplate transaccion;

    public AnuncioBusScheduler(ProductRepository productRepository, ObjectProvider<CatalogoBusService> busCatalogo,
            CatalogUseCase catalogUseCase, PlatformTransactionManager gestorDeTransacciones) {
        this.productRepository = productRepository;
        this.busCatalogo = busCatalogo;
        this.catalogUseCase = catalogUseCase;
        this.transaccion = new TransactionTemplate(gestorDeTransacciones);
    }

    @Value("${nexadrop.bus.anuncio-enabled:true}")
    private boolean habilitado;

    /** Tope de intentos: pasado eso el producto se da por perdido y deja de ocupar sitio en la cola. */
    @Value("${nexadrop.bus.anuncio-max-intentos:5}")
    private int maxIntentos;

    /**
     * Sustituible en pruebas por uno que ejecute en el hilo que llama, para poder comprobar lo que
     * hizo el lote sin esperas ni relojes.
     */
    volatile Executor orquestador;

    private volatile ExecutorService orquestadorPropio;

    /** Impide que se solapen dos lotes cuando uno tarda más que el intervalo del barrido. */
    private final AtomicBoolean loteEnMarcha = new AtomicBoolean(false);

    private Executor orquestador() {
        Executor actual = orquestador;
        if (actual == null) {
            synchronized (this) {
                actual = orquestador;
                if (actual == null) {
                    ExecutorService creado = Executors.newSingleThreadExecutor();
                    orquestadorPropio = creado;
                    orquestador = creado;
                    actual = creado;
                }
            }
        }
        return actual;
    }

    @PreDestroy
    void apagar() {
        ExecutorService orq = orquestadorPropio;
        if (orq != null) {
            orq.shutdownNow();
        }
    }

    /**
     * Barrido: encarga el lote y VUELVE.
     *
     * <p>El trabajo no se hace en el hilo del planificador, que es uno solo para todas las tareas
     * programadas —con hilos virtuales, {@code spring.task.scheduling.pool.size} se ignora—: quedarse
     * aquí construyendo fichas dejaría sin ejecutar al despachador de la bandeja de salida, y entonces
     * los productos no llegarían igual. Ya pasó con el espejado de vídeo.
     */
    @Scheduled(fixedDelayString = "${nexadrop.bus.anuncio-interval-ms:5000}")
    public void anunciarPendientes() {
        if (!habilitado || busCatalogo.getIfAvailable() == null) {
            return;
        }
        if (!loteEnMarcha.compareAndSet(false, true)) {
            return;
        }
        orquestador().execute(() -> {
            try {
                anunciarLote();
            } catch (Exception e) {
                log.warn("Anuncio al bus: el lote terminó mal: {}", e.toString());
            } finally {
                loteEnMarcha.set(false);
            }
        });
    }

    /** Anuncia los pendientes que quepan en un lote. Devuelve cuántos se consiguieron. */
    public int anunciarLote() {
        CatalogoBusService bus = busCatalogo.getIfAvailable();
        if (bus == null) {
            return 0;
        }
        List<ProductEntity> pendientes = productRepository
                .findTop50ByBusEstadoOrderByUpdatedAtAsc(BusAnuncioEstado.PENDIENTE);
        if (pendientes.isEmpty()) {
            return 0;
        }
        int hechos = 0;
        for (ProductEntity p : pendientes) {
            hechos += anunciarUno(bus, p) ? 1 : 0;
        }
        if (hechos > 0) {
            log.info("Anuncio al bus: {} producto(s) anunciados, quedan {} en cola", hechos,
                    productRepository.countByBusEstado(BusAnuncioEstado.PENDIENTE));
        }
        return hechos;
    }

    private boolean anunciarUno(CatalogoBusService bus, ProductEntity pendiente) {
        UUID id = pendiente.getId();
        try {
            transaccion.executeWithoutResult(estado -> publicar(bus, id));
            return true;
        } catch (Exception e) {
            anotarElFallo(pendiente, e);
            return false;
        }
    }

    /**
     * Publica UN producto. Qué evento sale se decide por cómo ha quedado el producto AHORA, no por lo
     * que pasó cuando se marcó: si alguien lo certificó y lo descertificó seguido, al destino llega el
     * estado final, que es justo lo que tiene que llegar.
     */
    private void publicar(CatalogoBusService bus, UUID id) {
        ProductEntity p = productRepository.findById(id).orElse(null);
        if (p == null) {
            return; // lo borraron entre el barrido y ahora: no hay nada que contar
        }
        if (Boolean.TRUE.equals(p.getVerified())) {
            // La categoría PRIMERO y con toda su rama: el destino puede no haberla visto nunca, y un
            // producto que llega antes que su categoría se queda huérfano y no aparece en el escaparate.
            if (p.getCategory() != null) {
                bus.publicarCategoriaConAncestros(p.getCategory());
            }
            bus.publicarCertificado(catalogUseCase.exportProduct(id));
        } else {
            bus.publicarRetirado(p, "descertificado en la edición del catálogo");
        }
        productRepository.marcarBusAnunciado(id, Instant.now());
    }

    /**
     * Deja el motivo escrito. Va en su propia transacción porque la del intento acaba de deshacerse:
     * escribir sobre ella se perdería con el resto.
     */
    private void anotarElFallo(ProductEntity pendiente, Exception causa) {
        int intentos = (pendiente.getBusIntentos() == null ? 0 : pendiente.getBusIntentos()) + 1;
        try {
            productRepository.anotarFalloDeAnuncio(pendiente.getId(), recortar(causa.toString()));
            if (intentos >= maxIntentos) {
                productRepository.darAnuncioPorPerdido(pendiente.getId());
                log.warn("Anuncio al bus: el producto {} se da por perdido tras {} intentos: {}",
                        pendiente.getExternalId(), intentos, causa.toString());
                return;
            }
        } catch (Exception e) {
            // Si ni siquiera se puede anotar el fallo, la base está caída: el producto sigue en
            // PENDIENTE y se reintentará. Lo grave sería perder el rastro, y el log lo conserva.
            log.warn("Anuncio al bus: no se pudo anotar el fallo del producto {}: {}", pendiente.getExternalId(),
                    e.toString());
        }
        log.debug("Anuncio al bus: falló el producto {} (intento {} de {}): {}", pendiente.getExternalId(), intentos,
                maxIntentos, causa.toString());
    }

    /** El motivo se guarda para enseñarlo, no para depurar: una traza entera no cabe en un panel. */
    private static String recortar(String mensaje) {
        return mensaje != null && mensaje.length() > 500 ? mensaje.substring(0, 500) : mensaje;
    }
}
