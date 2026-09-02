package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Qué línea del transportista puede llevar qué mercancía.
 *
 * <p>No todas las líneas admiten de todo. La {@code FZZXR} de YunExpress —y su variante para Amazon— es
 * la línea de ropa: <b>solo textil, y en bolsa</b>. Ofrecerla para algo que no lo es acaba en una guía
 * rechazada en el almacén de origen con el pedido ya cobrado, y en un cliente esperando un paquete que
 * no se ha movido. El error simétrico cuesta lo mismo: si un pedido no puede ir por esa línea y no se le
 * ofrece ninguna otra, no hay venta. De ahí que exista el segundo transportista.
 *
 * <p><b>Qué cuenta como ropa</b> (decisión del dueño, 18-ago-2026): la partida arancelaria. Capítulos
 * 61 (prendas de punto), 62 (prendas excepto punto) y 65 (sombrerería). Se decide por el arancel y no
 * por la categoría del catálogo por tres razones: ya es obligatorio en cada ficha —un producto sin él ni
 * siquiera se puede activar—, es exactamente el criterio que aplica el transportista al despachar, y no
 * añade un dato más que se pueda quedar desactualizado.
 *
 * <p><b>El pedido manda sobre el producto.</b> La restricción se evalúa sobre TODAS las líneas del
 * pedido, no producto a producto: en cuanto una no es textil, la bolsa entera deja de serlo y el
 * transportista rechaza la guía completa.
 */
@Service
public class CarrierEligibilityService {

    /**
     * Capítulos del arancel que el transportista acepta como textil.
     *
     * <p>En un enum y no en un {@code Map} o una lista suelta: son constantes de negocio con nombre, y
     * quien lea {@code PRENDAS_DE_PUNTO} entiende por qué está ahí sin ir a buscar la tabla del arancel.
     */
    public enum CapituloTextil {

        /** 61 — prendas y complementos de vestir, de punto. */
        PRENDAS_DE_PUNTO("61"),
        /** 62 — prendas y complementos de vestir, excepto los de punto. */
        PRENDAS_EXCEPTO_PUNTO("62"),
        /** 65 — sombrerería y sus partes. */
        SOMBRERERIA("65");

        private final String codigo;

        CapituloTextil(String codigo) {
            this.codigo = codigo;
        }

        /** ¿Esta partida ya normalizada pertenece al capítulo? */
        boolean cubre(String partidaNormalizada) {
            return partidaNormalizada.startsWith(codigo);
        }
    }

    /**
     * Líneas con restricción de mercancía.
     *
     * <p>Solo están aquí las que la tienen. Lo que no aparece admite carga general, y por eso la lista
     * es corta y no una tabla de todo el catálogo de canales.
     */
    private enum CanalRestringido {

        /** Línea de ropa de YunExpress: textil en bolsa, prohibida la caja de cartón. */
        LINEA_DE_ROPA("FZZXR");

        private final String prefijo;

        CanalRestringido(String prefijo) {
            this.prefijo = prefijo;
        }

        /**
         * ¿Este código de canal es esta línea?
         *
         * <p>Se compara por prefijo porque la misma línea se publica con sufijos —{@code FZZXR-AMZ} es
         * la de ropa para Amazon— y todas arrastran la misma restricción. Comparar por igualdad exacta
         * dejaría pasar las variantes, que es justo el descuido que cuesta una guía rechazada.
         */
        boolean esteEs(String canal) {
            return canal.toUpperCase(Locale.ROOT).startsWith(prefijo);
        }
    }

    /** Longitud mínima para poder leer el capítulo: las dos primeras cifras del arancel. */
    private static final int CIFRAS_DEL_CAPITULO = 2;

    /**
     * ¿Puede este pedido salir por el canal indicado?
     *
     * <p>Un canal sin restricción admite cualquier mercancía; la comprobación cara solo se hace para los
     * que la tienen.
     */
    public boolean admiteCanal(String canal, List<ProductEntity> productos) {
        if (canal == null || canal.isBlank()) {
            return false;
        }
        if (!CanalRestringido.LINEA_DE_ROPA.esteEs(canal)) {
            return true;
        }
        return admiteLaLineaDeRopa(productos);
    }

    /**
     * ¿Todo lo que va en el pedido es textil?
     *
     * <p>Un pedido sin líneas devuelve {@code false} a propósito: sin saber qué se envía no se puede
     * afirmar que sea ropa, y equivocarse aquí se paga con la guía rechazada.
     */
    public boolean admiteLaLineaDeRopa(List<ProductEntity> productos) {
        if (productos == null || productos.isEmpty()) {
            return false;
        }
        for (ProductEntity producto : productos) {
            if (!esTextil(producto)) {
                return false;
            }
        }
        return true;
    }

    private boolean esTextil(ProductEntity producto) {
        String partida = normalizar(producto == null ? null : producto.getHsCode());
        if (partida.length() < CIFRAS_DEL_CAPITULO) {
            // Sin partida utilizable no se decide a favor: es la opción que no rompe ningún pedido.
            return false;
        }
        for (CapituloTextil capitulo : CapituloTextil.values()) {
            if (capitulo.cubre(partida)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deja la partida en cifras.
     *
     * <p>En el catálogo conviven {@code 610910}, {@code 6109.10} y {@code 61 09 10}: es el mismo arancel
     * escrito de tres maneras, y comparar el texto tal cual daría por no-textil a dos de los tres.
     */
    private static String normalizar(String hsCode) {
        if (hsCode == null) {
            return "";
        }
        return hsCode.replaceAll("[^0-9]", "");
    }
}
