package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.CartItemDto;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CartItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CartItemJpaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Carrito ACTIVO ligado al usuario (no al dispositivo): lo que se añade en la web aparece en la app y al
 * revés. Reglas:
 * <ul>
 *   <li><b>Identidad de la línea = (producto, variante).</b> Dos variantes del mismo producto son dos
 *       líneas distintas, con su cantidad propia.</li>
 *   <li><b>{@link #upsert} FIJA la cantidad</b> (no la suma). Es la operación de la pantalla del carrito,
 *       donde la persona elige un número: si sumara, no habría forma de bajar de 3 a 2 unidades, y un
 *       reintento por red inestable —habitual en móvil— duplicaría lo pedido. Quien "añade" ya conoce la
 *       cesta y manda el total resultante.</li>
 *   <li><b>{@link #merge} SUMA</b>: es lo que espera quien llenó la cesta sin sesión y luego entra. No
 *       puede perder ni lo que traía del navegador ni lo que ya tenía en la cuenta.</li>
 *   <li><b>La cantidad nunca baja del MOQ</b> del producto (ni de 1). El mínimo se lee del CATÁLOGO, no
 *       del snapshot que manda el cliente: ese campo es solo de pintado y es manipulable.</li>
 *   <li><b>El precio no se recalcula aquí.</b> El snapshot sirve para pintar la línea; el importe que se
 *       cobra lo resuelve el presupuesto ({@code /api/catalog/cart-quote}) con la tarifa y la tasa del
 *       día.</li>
 *   <li>Solo se guardan productos que existen; una referencia inexistente lanza {@link NotFoundException}
 *       (así una cesta vieja no arrastra productos retirados del catálogo).</li>
 * </ul>
 *
 * <p><b>Aislamiento.</b> El {@code userId} llega desde la autenticación y entra en el filtro de TODAS las
 * consultas. Ningún método acepta un identificador de usuario del cuerpo o de la URL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    /** Tope duro de unidades por línea, coherente con la validación del checkout. */
    private static final int MAX_QTY = 100_000;

    private final CartItemJpaRepository repo;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<CartItemDto> list(UUID userId) {
        return repo.findByUserIdOrderByCreatedAtAscIdAsc(userId).stream().map(CartItemDto::fromEntity).toList();
    }

    /** Añade o actualiza una línea FIJANDO su cantidad; devuelve la cesta completa. */
    @Transactional
    public List<CartItemDto> upsert(UUID userId, CartItemDto dto) {
        save(userId, dto, false);
        return list(userId);
    }

    /** Funde una lista con la que ya haya, SUMANDO las cantidades de las líneas coincidentes. */
    @Transactional
    public List<CartItemDto> merge(UUID userId, List<CartItemDto> items) {
        if (items != null) {
            for (CartItemDto dto : items) {
                if (dto != null) {
                    save(userId, dto, true);
                }
            }
        }
        return list(userId);
    }

    @Transactional
    public List<CartItemDto> remove(UUID userId, UUID productId, UUID variantId) {
        repo.deleteByUserIdAndProductIdAndVariantId(userId, productId, variantId);
        return list(userId);
    }

    /** Vacía la cesta del usuario — al completar un pedido, o cuando la persona la descarta entera. */
    @Transactional
    public List<CartItemDto> clear(UUID userId) {
        repo.deleteByUserId(userId);
        return List.of();
    }

    /**
     * Persiste una línea. Con {@code sumar} a false la cantidad recibida SUSTITUYE a la guardada (pantalla
     * del carrito); con true se acumula sobre ella (fusión al iniciar sesión).
     */
    private void save(UUID userId, CartItemDto dto, boolean sumar) {
        ProductEntity producto = productRepository.findById(dto.productId())
                .orElseThrow(() -> new NotFoundException("Product"));
        // El mínimo manda sobre lo pedido: por debajo del MOQ el proveedor no sirve el pedido.
        int minimo = Math.max(1, producto.getMoq());
        CartItemEntity linea = repo
                .findByUserIdAndProductIdAndVariantId(userId, dto.productId(), dto.variantId())
                .orElseGet(() -> CartItemEntity.builder()
                        .userId(userId).productId(dto.productId()).variantId(dto.variantId()).quantity(0).build());
        int pedida = Math.max(1, dto.quantity());
        // La suma se hace en long a propósito: el merge NO valida el cuerpo campo a campo y una cantidad
        // enorme desbordaría el int, dando una cantidad negativa (el desbordamiento de cantidad ya provocó
        // un cobro incorrecto en el checkout). Con long, el tope de abajo recorta antes de volver a int.
        long deseada = sumar ? (long) linea.getQuantity() + pedida : pedida;
        linea.setQuantity((int) Math.min(MAX_QTY, Math.max(minimo, deseada)));
        applySnapshot(linea, dto, minimo);
        repo.save(linea);
    }

    /**
     * Refresca los datos "de pintado" (precio/título/imagen/variante) al valor más reciente recibido. El
     * MOQ es la excepción: se devuelve el del CATÁLOGO, no el que mandó el cliente, para que el selector
     * de unidades de la web y el de la app enseñen el mismo mínimo real aunque uno tenga la ficha vieja.
     */
    private void applySnapshot(CartItemEntity entity, CartItemDto dto, int moqCatalogo) {
        entity.setSku(dto.sku());
        entity.setSlug(dto.slug());
        entity.setTitle(dto.title());
        entity.setImageUrl(dto.image());
        entity.setVariantLabel(dto.variantLabel());
        entity.setUnitPriceSource(dto.unitPriceSource());
        entity.setSourceCurrency(dto.sourceCurrency());
        entity.setMoq(moqCatalogo);
        entity.setUnitPriceDisplay(dto.unitPriceDisplay());
        entity.setDisplayCurrency(dto.displayCurrency());
        entity.setDisplaySymbol(dto.displaySymbol());
    }
}
