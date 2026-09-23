package com.nexaplatform.dropshipping.api.mapper;

import java.math.BigDecimal;
import java.util.List;
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
public record ProductListFilters(String q, UUID categoryId, UUID supplierId, BigDecimal minPrice, BigDecimal maxPrice,
        String shipFrom, Boolean freeShipping, Boolean selfPickup, Boolean hasVideo, Integer minRating,
        Integer inventoryMin, String certification, Boolean verified, UUID promotionId,
        // Grupos de declaración: «ver los que no suman arancel». Filtra por la TERNA de cada grupo
        // (partida, material y uso), que es lo que de verdad hace que dos productos compartan línea de
        // aduana.
        //
        // Es una LISTA y no un grupo suelto porque el carrito tiene tantas líneas de declaración como
        // ternas distintas lleve: con tres productos de tres grupos, enseñar solo uno deja fuera dos
        // tercios de lo que tampoco sumaría arancel.
        List<DutyLine> dutyLines) {

    /**
     * Una LÍNEA de declaración: el grupo (partida, material y uso) más el ORIGEN.
     *
     * <p>El origen no es un adorno: la terna del art. 1(61) del Reglamento Delegado (UE) 2015/2446 es
     * clasificación + descripción + <b>origen</b>, así que dos productos del mismo grupo con orígenes
     * distintos son dos líneas y pagan dos derechos. Filtrar solo por el grupo devolvía productos que,
     * al declararse con otro origen, sumaban arancel igualmente — que es exactamente lo que se veía en
     * pantalla el 21-ago-2026, con 81 camisas sin país de origen y 28 con «CN».
     *
     * @param originCountry ya normalizado; {@code null} = cualquier origen (el caso de «ver los del grupo
     *                      de este producto», donde no hay carrito con el que comparar)
     */
    public record DutyLine(UUID groupId, String originCountry) {
    }

    /** Sin ningún filtro: el listado completo. */
    public static ProductListFilters none() {
        return new ProductListFilters(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    /** Sólo búsqueda de texto, categoría, proveedor y rango de precio (lo que expone el listado simple). */
    public static ProductListFilters basic(String q, UUID categoryId, UUID supplierId, BigDecimal minPrice,
            BigDecimal maxPrice) {
        return new ProductListFilters(q, categoryId, supplierId, minPrice, maxPrice, null, null, null, null, null, null,
                null, null, null, null);
    }
}
