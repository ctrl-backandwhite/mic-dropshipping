package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductViewRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Historial de fichas visitadas por el usuario. Alimenta dos cosas: la página «lo que has visto» de su área
 * y el correo recordatorio de cada tres días.
 *
 * <p>Solo se registra con SESIÓN INICIADA — quien llama trae siempre el usuario de la autenticación, nunca
 * del cuerpo de la petición. Anotar una visita es idempotente: repetirla mueve la fecha, no crea otra fila.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductViewHistoryService {

    /**
     * Tope de fichas que se devuelven. El historial de un usuario que lleva meses mirando puede ser de
     * miles de filas y nadie baja tan abajo: sin tope, cada apertura de la página cargaría el catálogo
     * entero de esa persona para enseñar las primeras veinticuatro.
     */
    public static final int MAX_HISTORIAL = 200;

    /** Retención del historial: pasados estos días, la visita se borra. */
    public static final Duration RETENCION = Duration.ofDays(90);

    private final ProductViewRepository viewRepository;
    private final ProductRepository productRepository;

    /**
     * Anota que el usuario ha abierto la ficha del producto.
     *
     * @throws NotFoundException si el producto no existe: sin esta comprobación, cualquiera podría sembrar
     *         el historial con identificadores inventados que luego nadie sabría pintar.
     */
    @Transactional
    public void record(UUID userId, UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw new NotFoundException("Product");
        }
        viewRepository.registrarVisita(userId, productId, Instant.now());
    }

    /** IDs del historial del usuario, del visitado más recientemente al más antiguo. */
    @Transactional(readOnly = true)
    public List<UUID> viewedProductIds(UUID userId) {
        return viewRepository.findProductIdsByUserId(userId, Limit.of(MAX_HISTORIAL));
    }

    /**
     * Borra las visitas que superan la retención.
     *
     * @return cuántas filas se han eliminado.
     */
    @Transactional
    public int purgeExpired() {
        Instant limite = Instant.now().minus(RETENCION);
        int borradas = viewRepository.deleteByViewedAtBefore(limite);
        if (borradas > 0) {
            log.info("Historial de visitas: {} filas purgadas por superar los {} días de retención", borradas,
                    RETENCION.toDays());
        }
        return borradas;
    }
}
