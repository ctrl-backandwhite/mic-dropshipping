package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CartItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso al carrito sincronizado. TODAS las consultas llevan el {@code userId} en el filtro: no existe
 * ninguna forma de tocar la cesta de otra persona desde este repositorio, ni siquiera por error.
 */
public interface CartItemJpaRepository extends JpaRepository<CartItemEntity, UUID> {

    /**
     * Las líneas del usuario en su orden de llegada (la más antigua primero), que es como se ve el
     * carrito en pantalla. El desempate por id es necesario porque varias líneas subidas en la misma
     * transacción (un merge) pueden compartir instante de creación: sin él, el orden sería arbitrario y
     * la cesta "bailaría" entre recargas.
     */
    List<CartItemEntity> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

    /**
     * Busca la línea de una variante concreta. Spring Data traduce un {@code variantId} nulo a
     * {@code variant_id IS NULL}, así que sirve tanto para producto con variante como sin ella.
     */
    Optional<CartItemEntity> findByUserIdAndProductIdAndVariantId(UUID userId, UUID productId, UUID variantId);

    @Modifying
    void deleteByUserIdAndProductIdAndVariantId(UUID userId, UUID productId, UUID variantId);

    /** Vaciado de la cesta: se usa al completar un pedido. Acotado al usuario, nunca global. */
    @Modifying
    void deleteByUserId(UUID userId);
}
