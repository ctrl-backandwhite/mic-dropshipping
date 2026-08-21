package com.nexaplatform.dropshipping.api.mapper;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Filtros del listado del escaparate.
 *
 * <p>Antes viajaban como diecisiete parámetros sueltos, con {@code freeShipping}, {@code selfPickup} y
 * {@code hasVideo} seguidos: tres booleanos del mismo tipo donde intercambiar dos al llamar no da error
 * de compilación y el listado devuelve otra cosa sin que nadie se entere. Agrupados, cada filtro se pasa
 * por su nombre.
 *
 * <p>Un campo a {@code null} significa «este filtro no se aplica», que no es lo mismo que aplicarlo con
 * un valor falso: {@code hasVideo = null} devuelve todos los productos y {@code hasVideo = false} sólo
 * los que NO tienen vídeo.
 */
public record ProductListFilters(String q, UUID categoryId, UUID supplierId, BigDecimal minPrice,
        BigDecimal maxPrice, String shipFrom, Boolean freeShipping, Boolean selfPickup, Boolean hasVideo,
        Integer minRating, Integer inventoryMin, String certification, Boolean verified, UUID promotionId,
        // Grupo de declaración: «ver los que no suman arancel». Filtra por la TERNA del grupo (partida,
        // material y uso), que es lo que de verdad hace que dos productos compartan línea de aduana.
        UUID dutyGroupId) {

    /** Sin ningún filtro: el listado completo. */
    public static ProductListFilters none() {
        return new ProductListFilters(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    /** Sólo búsqueda de texto, categoría, proveedor y rango de precio (lo que expone el listado simple). */
    public static ProductListFilters basic(String q, UUID categoryId, UUID supplierId, BigDecimal minPrice,
            BigDecimal maxPrice) {
        return new ProductListFilters(q, categoryId, supplierId, minPrice, maxPrice, null, null, null, null, null,
                null, null, null, null, null);
    }
}
