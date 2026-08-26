package com.nexaplatform.dropshipping.api.dto;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Formas que devuelve (y acepta) el escaparate: categorías, proveedores, variantes, envío, carrito,
 * secciones de portada y estimaciones de margen.
 *
 * <p>Vivían dentro de {@code StorefrontCatalogController}. Veinticuatro tipos declarados en el propio
 * controlador lo convertían en la pieza de la que dependía media API: el controlador del canal de
 * partners, el servicio de lectura compartido y hasta las campañas de correo importaban sus vistas
 * desde ahí, así que tocar el controlador arrastraba a todos. Aquí son lo que son —el contrato de
 * datos del escaparate— y el controlador vuelve a ser solo el que atiende las peticiones.
 */
public final class StorefrontViews {

    private StorefrontViews() {
    }

    public record CategoryView(UUID id, String slug, String name, String nameZh, UUID parentId, int position,
            String icon, int directProductCount, List<CategoryView> children) {
    }

    public record CategoryBreadcrumb(UUID id, String slug, String name) {
    }

    public record SupplierView(UUID id, String slug, String name, String nameZh, String country, String city,
            BigDecimal rating, Integer yearsActive, boolean verified, boolean trustPass, long productCount) {
    }

    public record VariantView(UUID id, String sku, String externalId, String title, BigDecimal price, int stock,
            String imageUrl, Map<String, String> options, boolean active) {
    }

    public record SpecificationView(String key, String value, int position) {
    }

    public record AttributeView(String key, String value) {
    }

    public record TagView(String tag) {
    }

    public record ShippingZoneView(UUID supplierId, String supplierName, String countryCode, String region,
            boolean active) {
    }

    public record ShippingRateView(UUID id, UUID supplierId, String countryCode, String method, String carrier,
            int transitDaysMin, int transitDaysMax, BigDecimal baseCost, BigDecimal perKgCost, Integer maxWeightGrams) {
    }

    public record ShippingQuoteItem(UUID supplierId, String method, String carrier, int transitDaysMin,
            int transitDaysMax, BigDecimal cost, String currency) {
    }

    public record ShippingQuoteRequest(UUID productId, UUID variantId, int quantity, String country) {
    }

    public record AttributeKeyView(String key, long usage) {
    }

    public record SuggestionView(String type, String text, String slug) {
    }

    public record CartQuoteItemIn(UUID productId, UUID variantId, int quantity) {
    }

    /**
     * @param weightGrams peso NETO de la variante comprada, en gramos; el del producto si la variante no
     *                    lo declara, y {@code null} si no hay ninguno. Nunca un respaldo inventado: el
     *                    backend usa 500 g por defecto para poder cotizar un envío, pero enseñar eso como
     *                    el peso de la compra sería darle al cliente un número que nadie ha medido
     */
    public record CartQuoteLineOut(UUID productId, UUID variantId, BigDecimal unit, BigDecimal lineTotal,
            String unitFormatted, String lineTotalFormatted, Integer weightGrams) {
    }

    /**
     * @param totalWeightGrams suma del peso de las líneas que SÍ lo tienen, por cantidad
     * @param weightIncomplete true si alguna línea no tiene peso real. Con él la pantalla dice «desde X g»
     *                         en vez de un total que el comprador tomaría por el peso de su paquete
     */
    public record CartQuoteOut(String currency, String symbol, List<CartQuoteLineOut> items, BigDecimal subtotal,
            String subtotalFormatted, int totalWeightGrams, boolean weightIncomplete) {
    }

    public record HomeSection(String code, String title, List<ProductSummaryView> items) {
    }

    /**
     * Secciones de la portada.
     *
     * <p>{@code totalProducts} viaja aquí porque la portada lo enseña («N SKUs en producción») y antes
     * lo sacaba pidiendo la primera página del listado solo para leer su total. Desde que el listado
     * exige cuenta, esa llamada devolvía 401 y echaba al visitante a la pantalla de login. El número de
     * SKUs es un dato de escaparate, no el catálogo.
     */
    public record HomeSectionsResponse(List<HomeSection> sections, List<CategoryView> hotCategories,
            long totalProducts) {
    }

    public record ImportUrlRequest(@NotBlank String url) {
    }

    public record ImportUrlResponse(boolean matched, String source, String externalId, ProductSummaryView product,
            String resolveHint) {
    }

    public record ImageSearchRequest(String imageBase64, String imageUrl, Integer limit) {
    }

    public record ImageSearchResult(ProductSummaryView product, double score) {
    }

    public record HistoryPoint(LocalDate date, BigDecimal price, int stock) {
    }

    public record MarginEstimate(BigDecimal cost, BigDecimal suggestedRetail, BigDecimal shipping,
            BigDecimal commission, BigDecimal netProfit, BigDecimal marginPct,
            String currency, BigDecimal appliedMarginPct, Integer appliedTierMinQty) {
    }

    /**
     * Un producto de ejemplo de la guía de bienvenida, con lo justo para que el simulador funcione.
     *
     * <p>La guía enseña dos reglas de la Unión Europea: el arancel se paga por partida declarada y el
     * envío por bulto. Para verlas moverse hacen falta el <b>precio</b> (subtotal), el <b>peso</b>
     * (porte) y la <b>partida</b> ({@code dutyGroup}), que es lo que el simulador cuenta para saber
     * cuántos derechos de 3 EUR se pagan.
     *
     * @param dutyGroup clave opaca de agrupación arancelaria. Dos ejemplos con la MISMA clave pagan un
     *                  solo derecho por mucho que se sumen unidades; con claves distintas, uno cada uno.
     */
    public record WelcomeExample(java.util.UUID id, String slug, String title, String imageUrl,
            String priceFormatted, java.math.BigDecimal priceAmount, int weightGrams, String dutyGroup,
            /**
             * El porte del proveedor que va dentro del precio unitario, en la divisa que se mira.
             *
             * <p>Es lo que se devuelve de la segunda unidad en adelante: el proveedor manda un solo bulto
             * tenga el cliente una unidad o cinco, así que ese porte entra y no se gasta. Con esto el
             * simulador puede enseñar el descuento sin saber nada de márgenes, que no son asunto del
             * comprador.
             */
            java.math.BigDecimal repeatShippingAmount) {
    }

    /**
     * Los ejemplos de la guía y los datos con los que se calculan.
     *
     * @param perArticleDutyFormatted derecho por partida del país que mira ("3,00 EUR"); vacío donde no
     *                                exista, y entonces la guía no enseña el paso del arancel
     * @param taxRateBps              impuesto del país de entrega, para que el ejemplo cuadre con lo que
     *                                luego verá en el checkout
     * @param orderLimitFormatted     valor de mercancía por encima del cual el transportista no acepta
     *                                el pedido; vacío si ese destino no tiene tope
     */
    public record WelcomeExamplesResponse(java.util.List<WelcomeExample> examples,
            String perArticleDutyFormatted, int taxRateBps, String orderLimitFormatted,
            /**
             * El mismo tope, en la divisa que se está mirando y como número.
             *
             * <p>La etiqueta ("150 EUR") va en su divisa LEGAL porque así lo fija la norma, pero el
             * simulador necesita comparar la mercancía de ejemplo con él, y para eso hace falta la cifra
             * en la divisa del visitante. Cero donde ese destino no tenga tope.
             */
            java.math.BigDecimal orderLimitAmount) {
    }

    /** Cuántas unidades de un producto de ejemplo pone el visitante en el simulador de la guía. */
    public record WelcomeSimulationLine(java.util.UUID productId, int quantity) {
    }

    /**
     * El desglose del simulador de la guía, calculado por el MISMO servicio que el checkout.
     *
     * <p>Se calcula en el servidor y no en el navegador por dos motivos. El primero es que así no puede
     * divergir: la guía enseña exactamente lo que se cobrará, incluidas las dos fuentes de la subvención
     * —el porte repetido y la ganancia del pedido por encima del suelo—. El segundo es que la segunda
     * fuente depende del margen, y el margen no sale de este servidor.
     */
    public record WelcomeSimulationResponse(String subtotalFormatted, String dutyFormatted, int dutyLines,
            String shippingFormatted, String shippingSubsidyFormatted, String shippingNetFormatted,
            String customsSubsidyFormatted, String customsNetFormatted, String taxFormatted,
            int taxRateBps, String totalFormatted, int weightGrams, boolean overLimit,
            String orderLimitFormatted) {
    }
}
