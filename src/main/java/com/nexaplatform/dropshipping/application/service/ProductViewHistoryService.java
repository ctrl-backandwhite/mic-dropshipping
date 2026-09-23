package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductViewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
     * Cuántas fichas conserva el historial de cada usuario.
     *
     * <p>Es un tope DURO, no solo de lectura: al abrir la ficha número cincuenta y uno se borra la visita
     * más antigua. Antes se guardaban todas y solo se leían las primeras doscientas, de modo que la tabla
     * crecía sin fin con filas que nadie iba a mirar y que únicamente desaparecían al cumplir los noventa
     * días de retención.
     *
     * <p>Cincuenta es lo que se pidió y encaja con para qué sirve esto: recordarle a alguien por dónde ha
     * pasado hace poco. Nadie baja a la visita número doscientos, y guardar el rastro entero de una
     * persona sin que vaya a usarse es acumular dato personal por acumularlo.
     */
    public static final int MAX_HISTORIAL = 50;

    /** Retención del historial: pasados estos días, la visita se borra. */
    public static final Duration RETENCION = Duration.ofDays(90);

    private final ProductViewRepository viewRepository;
    private final ProductRepository productRepository;
    private final PricingService pricingService;

    /**
     * Una ficha del historial con el precio que vio quien la visitó.
     *
     * <p>El precio va aquí y no se recalcula al pintar la página: rehacerlo son, por cada una de las
     * cincuenta fichas, una conversión de divisa, el margen del país de registro, el IVA, el envío, las dos
     * bolsas de subvención y el recargo fijo. Eso es lo que hacía lento el historial.
     *
     * <p>Puede venir a nulo en las visitas anteriores al 4-sep-2026, que se anotaron sin precio; para esas
     * el listado vuelve al cálculo de siempre.
     */
    public record FichaVista(UUID productId, BigDecimal precio, String moneda, String formateado) {
    }

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
        // El precio se calcula AQUÍ, en el servidor, y con el contexto de quien mira: su país de registro
        // decide el margen y su moneda el importe. Es el mismo que acaba de ver en la ficha. No se acepta
        // del navegador: un importe que viajara desde el cliente lo podría poner cualquiera.
        PrecioDeLaVisita precio = precioDeLaFicha(productId);
        viewRepository.registrarVisita(userId, productId, Instant.now(), precio.importe(), precio.moneda(),
                precio.formateado());
        // La ficha recién vista empuja fuera a la más antigua en cuanto se pasa del tope. Se poda aquí y
        // no en un barrido nocturno porque el historial se lee justo después de escribirlo —el usuario
        // vuelve a su página desde la propia ficha— y con la poda diferida vería una lista más larga de
        // lo que promete hasta que el barrido pasara.
        viewRepository.podarExcedente(userId, MAX_HISTORIAL);
    }

    /** IDs del historial del usuario, del visitado más recientemente al más antiguo. */
    @Transactional(readOnly = true)
    public List<UUID> viewedProductIds(UUID userId) {
        return viewRepository.findProductIdsByUserId(userId, Limit.of(MAX_HISTORIAL));
    }

    /** El historial con el precio que vio el usuario, del más reciente al más antiguo. */
    @Transactional(readOnly = true)
    public List<FichaVista> fichasVistas(UUID userId) {
        return viewRepository.findFichasVistasByUserId(userId, Limit.of(MAX_HISTORIAL));
    }

    /** Lo que cuesta la ficha para quien la está mirando, ya resuelto. */
    private PrecioDeLaVisita precioDeLaFicha(UUID productId) {
        try {
            ProductEntity producto = productRepository.findById(productId).orElse(null);
            if (producto == null) {
                return PrecioDeLaVisita.SIN_PRECIO;
            }
            PricedAmount precio = pricingService.priceFor(producto);
            return new PrecioDeLaVisita(precio.displayAmount(), precio.displayCurrency(), precio.displayFormatted());
        } catch (Exception e) {
            // Que no se pueda calcular el precio no puede impedir que quede constancia de la visita: el
            // historial sirve para reencontrar el producto, y el precio es un adorno útil, no el dato.
            log.warn("Historial: no se pudo resolver el precio del producto {}: {}", productId, e.toString());
            return PrecioDeLaVisita.SIN_PRECIO;
        }
    }

    private record PrecioDeLaVisita(BigDecimal importe, String moneda, String formateado) {
        static final PrecioDeLaVisita SIN_PRECIO = new PrecioDeLaVisita(null, null, null);
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
