package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Los cinco datos que el transportista exige en CADA línea de la declaración aduanera.
 *
 * <p>Vive aquí, y no dentro del proveedor de envíos, porque hacen falta en DOS momentos muy distintos:
 * al preparar el catálogo —donde el fallo es barato de arreglar— y al transmitir la guía —donde el
 * pedido ya está cobrado—. Teniendo la regla en un solo sitio, el producto que el admin da por listo
 * para vender es exactamente el que el transportista va a aceptar; con dos comprobaciones separadas
 * bastaba con que una se quedara atrás para volver a la situación que esto viene a evitar.
 *
 * <p>Hasta ahora la ausencia de cualquiera de ellos solo dejaba un aviso en el registro y el envío se
 * transmitía igual: el transportista podía rechazar la guía —con el pedido ya cobrado— o la aduana
 * retener el paquete, y nadie se enteraba hasta que el cliente reclamaba.
 */
public final class CustomsDataCheck {

    /**
     * Dato obligatorio de la declaración, con el nombre que le da el transportista entre paréntesis.
     *
     * <p>La etiqueta lleva el nombre técnico a propósito: quien lee el error es el administrador que
     * tiene que corregir el producto, y ese nombre es el que aparece en la documentación de YunExpress
     * y en el fichero de carga.
     */
    public enum CustomsField {

        ENGLISH_NAME("nombre en inglés (EName)"), CHINESE_NAME("nombre en chino (CName)"), HS_CODE(
                "partida arancelaria (HSCode)"), UNIT_WEIGHT(
                        "peso unitario (UnitWeight)"), DECLARED_VALUE("valor declarado (UnitPrice)");

        private final String etiqueta;

        CustomsField(String etiqueta) {
            this.etiqueta = etiqueta;
        }

        /** Cómo se nombra el dato en los mensajes que ve el administrador. */
        public String etiqueta() {
            return etiqueta;
        }
    }

    private CustomsDataCheck() {
    }

    /**
     * ¿El texto lleva algún ideograma? Es lo que YunExpress comprueba para dar por válido el CName
     * ({@code 必填，且不得为纯数字或纯字母}).
     *
     * <p>No basta con mirar si el campo viene relleno: en el catálogo actual {@code product.title_zh}
     * quedó poblado con el título en español, así que el dato «está» y aun así la guía se rechaza.
     */
    public static boolean tieneIdeogramas(String texto) {
        if (texto == null || texto.isBlank()) {
            return false;
        }
        return texto.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /**
     * Qué le falta a una línea YA RESUELTA de la declaración (la que se va a transmitir).
     *
     * <p>Es la comprobación de último momento: aquí los nombres y el peso ya se han buscado en el pedido,
     * en el producto y en la variante comprada, así que lo que falte aquí falta de verdad.
     */
    public static List<CustomsField> faltantesEnLinea(String nombreIngles, String nombreChino, String hsCode,
            double pesoUnitarioKg, double valorUnitario) {
        List<CustomsField> faltantes = new ArrayList<>();
        if (estaVacio(nombreIngles)) {
            faltantes.add(CustomsField.ENGLISH_NAME);
        }
        if (!tieneIdeogramas(nombreChino)) {
            faltantes.add(CustomsField.CHINESE_NAME);
        }
        if (estaVacio(hsCode)) {
            faltantes.add(CustomsField.HS_CODE);
        }
        if (pesoUnitarioKg <= 0) {
            faltantes.add(CustomsField.UNIT_WEIGHT);
        }
        if (valorUnitario <= 0) {
            faltantes.add(CustomsField.DECLARED_VALUE);
        }
        return faltantes;
    }

    /**
     * Qué le falta a un producto del catálogo para poder venderse y declararse.
     *
     * <p>Se comprueba sobre el producto y TODAS sus variantes porque el peso y el precio que se declaran
     * son los de la variante comprada: que una sola variante se quede sin peso basta para que el pedido
     * que la incluya salga con la declaración coja. Mirar solo la primera variante daría por bueno un
     * producto que falla en cuanto el cliente elige otra talla.
     */
    public static List<CustomsField> faltantesDe(ProductEntity producto) {
        List<CustomsField> faltantes = new ArrayList<>();
        if (estaVacio(nombreIngles(producto))) {
            faltantes.add(CustomsField.ENGLISH_NAME);
        }
        if (!tieneIdeogramas(nombreChino(producto))) {
            faltantes.add(CustomsField.CHINESE_NAME);
        }
        if (estaVacio(producto.getHsCode())) {
            faltantes.add(CustomsField.HS_CODE);
        }
        if (!tienePesoDeclarable(producto)) {
            faltantes.add(CustomsField.UNIT_WEIGHT);
        }
        if (!tienePrecioDeclarable(producto)) {
            faltantes.add(CustomsField.DECLARED_VALUE);
        }
        return faltantes;
    }

    /**
     * Frase para el administrador: de qué producto se trata y qué datos le faltan.
     *
     * <p>Se enumeran TODOS de golpe, no el primero: corregirlos de uno en uno obliga a guardar, reintentar
     * y descubrir el siguiente, y con miles de referencias eso es inasumible.
     */
    public static String describe(String queProducto, List<CustomsField> faltantes) {
        List<String> etiquetas = new ArrayList<>();
        for (CustomsField campo : faltantes) {
            etiquetas.add(campo.etiqueta());
        }
        return queProducto + ": falta " + String.join(", ", etiquetas);
    }

    /** Título en inglés declarado: la traducción {@code en} del catálogo. */
    private static String nombreIngles(ProductEntity producto) {
        return tituloTraducido(producto, "en");
    }

    /**
     * Nombre chino declarable: la traducción {@code zh} del catálogo y, si no la hay, la columna
     * {@code title_zh}. En ambos casos tiene que llevar ideogramas de verdad.
     */
    private static String nombreChino(ProductEntity producto) {
        String traducido = tituloTraducido(producto, "zh");
        if (tieneIdeogramas(traducido)) {
            return traducido;
        }
        return producto.getTitleZh();
    }

    private static String tituloTraducido(ProductEntity producto, String idioma) {
        if (producto.getTranslations() == null) {
            return null;
        }
        for (ProductTranslationEntity traduccion : producto.getTranslations()) {
            if (idioma.equalsIgnoreCase(traduccion.getLanguage()) && !estaVacio(traduccion.getTitle())) {
                return traduccion.getTitle();
            }
        }
        return null;
    }

    /**
     * ¿Hay peso que declarar para CUALQUIER variante que el cliente pueda comprar?
     *
     * <p>El peso del producto vale para todas; si no lo tiene, cada variante debe traer el suyo. Un
     * producto sin peso propio y sin variantes no tiene de dónde sacarlo.
     */
    private static boolean tienePesoDeclarable(ProductEntity producto) {
        if (esPositivo(producto.getPackageWeightGrams()) || esPositivo(producto.getWeightGrams())) {
            return true;
        }
        List<ProductVariantEntity> variantes = producto.getVariants();
        if (variantes == null || variantes.isEmpty()) {
            return false;
        }
        return variantes.stream()
                .allMatch(v -> esPositivo(v.getPackageWeightGrams()) || esPositivo(v.getWeightGrams()));
    }

    /** Mismo criterio para el valor declarado: el precio base sirve para todas, si no lo trae cada variante. */
    private static boolean tienePrecioDeclarable(ProductEntity producto) {
        if (esPositivo(producto.getBasePrice())) {
            return true;
        }
        List<ProductVariantEntity> variantes = producto.getVariants();
        if (variantes == null || variantes.isEmpty()) {
            return false;
        }
        return variantes.stream().allMatch(v -> esPositivo(v.getPrice()));
    }

    private static boolean esPositivo(Integer valor) {
        return valor != null && valor > 0;
    }

    private static boolean esPositivo(BigDecimal valor) {
        return valor != null && valor.signum() > 0;
    }

    private static boolean estaVacio(String texto) {
        return texto == null || texto.isBlank();
    }
}
