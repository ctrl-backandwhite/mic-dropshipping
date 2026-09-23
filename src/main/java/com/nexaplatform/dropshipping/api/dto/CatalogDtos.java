package com.nexaplatform.dropshipping.api.dto;

import com.nexaplatform.dropshipping.application.service.EuComplianceService.ProductComplianceView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All Request + Response records for catalog endpoints. */
public final class CatalogDtos {
    private CatalogDtos() {
    }

    /* ============================ REQUESTS ============================ */

    public record IngestProductRequest(@NotBlank String source, @NotBlank String externalId, @NotBlank String titleZh,
            String shortDescriptionZh, String descriptionZh, String brand, Integer moq, BigDecimal basePrice,
            String currency, Integer weightGrams, Integer monthlySales, BigDecimal repurchaseRate, BigDecimal rating,
            Integer reviewCount, String sourceUrl, UUID supplierId, UUID categoryId, List<IngestImage> images,
            List<IngestVariantOption> options, List<IngestVariant> variants, List<IngestPriceTier> priceTiers) {
    }

    public record IngestImage(@NotBlank String sourceUrl, int position, String role) {
    }

    public record IngestVariantOption(@NotBlank String nameZh, int position, List<IngestVariantValue> values) {
    }

    public record IngestVariantValue(@NotBlank String valueZh, int position, String imageSourceUrl) {
    }

    public record IngestVariant(String externalId, String sku, String title, BigDecimal price, Integer stock,
            String imageSourceUrl, Map<String, String> options) {
    }

    public record IngestPriceTier(@Positive int minQty, Integer maxQty, BigDecimal unitPrice, String currency,
            BigDecimal surchargeCny) {

        /** Sin recargo propio: el tramo hereda el del producto, que es el caso de siempre. */
        public IngestPriceTier(int minQty, Integer maxQty, BigDecimal unitPrice, String currency) {
            this(minQty, maxQty, unitPrice, currency, null);
        }
    }

    public record IngestSupplierRequest(@NotBlank String source, @NotBlank String externalId, String name,
            String nameZh, String country, String city, BigDecimal rating, Integer yearsActive, boolean verified,
            boolean trustPass, String profileUrl) {
    }

    public record IngestCategoryRequest(@NotBlank String slug, UUID parentId, String source, String externalId,
            String nameZh, int position, String icon, Map<String, String> nameTranslations) {
    }

    public record UpdateProductStatusRequest(@NotNull String status) {
    }

    /* ============================ RESPONSES ============================ */

    /**
     * Un producto al que le faltan datos obligatorios de aduana, con la lista de lo que le falta.
     *
     * <p>{@code missing} viene ya redactado ("partida arancelaria (HSCode)") porque quien lo lee es el
     * administrador que tiene que corregirlo, y son los mismos nombres que usan el aviso del despacho y
     * el fichero de carga.
     */
    public record ProductCustomsGapView(UUID id, String slug, String title, String externalId, String status,
            List<String> missing) {
    }

    /**
     * Resultado de repasar el catálogo buscando productos que no se podrían declarar en aduana.
     *
     * <p>Con miles de referencias, abrirlas una a una no es viable: esta vista las enumera de golpe.
     * {@code truncated} avisa de que se alcanzó el tope pedido y quedan más por revisar, para que nadie
     * lea una lista corta como «ya no queda nada».
     */
    public record CustomsAuditView(long scanned, long incomplete, boolean truncated,
            List<ProductCustomsGapView> products) {
    }

    public record SupplierView(UUID id, String source, String externalId, String name, String nameZh, String country,
            String city, BigDecimal rating, Integer yearsActive, boolean verified, boolean trustPass,
            String profileUrl) {
    }

    public record CategoryView(UUID id, String slug, String nameZh, Map<String, String> names, UUID parentId,
            int position, String icon, boolean active) {
    }

    public record CategoryTreeView(UUID id, String slug, String nameZh, Map<String, String> names, String icon,
            List<CategoryTreeView> children) {
    }

    public record ProductImageView(UUID id, int position, String role, String sourceUrl, String cdnUrl) {
    }

    /**
     * Valor de un eje de variante. El campo {@code value} es el override neutral que se mantiene por
     * compatibilidad; {@code valueLocalized} es la etiqueta del idioma activo, que cae al override
     * cuando no hay traducción; y {@code translations} lleva todas las traducciones por idioma, que es
     * lo que necesita el editor del admin.
     */
    public record VariantValueView(UUID id, String valueZh, String value, String valueLocalized, String imageUrl,
            String imageSourceUrl, int position, Map<String, String> translations) {
    }

    public record VariantOptionView(UUID id, String nameZh, String name, int position, List<VariantValueView> values) {
    }

    public record VariantView(UUID id, String sku, String title, BigDecimal price, String priceFormatted, int stock,
            String imageUrl, Map<String, String> options, boolean active,
            // Báscula de peso por variante (peso en gramos, dimensiones en mm). El volumen se calcula en el front.
            Integer weightGrams, Integer lengthMm, Integer widthMm, Integer heightMm,
            // Rebaja de ESTA variante. Tiene que ser suya y no la del producto: cada variante parte de
            // un precio distinto, así que el «antes» del producto junto al «ahora» de la variante da
            // un tachado incoherente — y puede salir MENOR que el precio rebajado.
            String originalFormatted, Integer discountPercent,
            // Envío nacional chino de ESTA variante, en CNY. El scraper lo calcula por tramos de peso
            // (6 / 10 / 16) porque una misma ficha tiene tallas que pesan el doble que otras, y con un
            // único importe por producto a la pesada se le cobraba el flete de la ligera.
            // Null = no declara envío propio y manda el del producto; cero = envío gratis.
            BigDecimal shippingCny) {

        /** Sin promoción: atajo para los usos que no la calculan. */
        public VariantView(UUID id, String sku, String title, BigDecimal price, String priceFormatted, int stock,
                String imageUrl, Map<String, String> options, boolean active, Integer weightGrams, Integer lengthMm,
                Integer widthMm, Integer heightMm) {
            this(id, sku, title, price, priceFormatted, stock, imageUrl, options, active, weightGrams, lengthMm,
                    widthMm, heightMm, null, null, null);
        }
    }

    /**
     * Un escalon de la tabla de cantidades, ya tarificado.
     *
     * <p>{@code surchargeCny} es el recargo fijo de ESTE tramo, en la moneda del proveedor y tal como
     * lo teclea quien administra (23-sep-2026). Nulo significa dos cosas distintas segun quien mire:
     * para quien administra, que el tramo no tiene recargo propio y hereda el del producto; para
     * cualquier otro, que no se publica -es un importe interno, como el resto del desglose-.
     */
    public record PriceTierView(int minQty, Integer maxQty, BigDecimal unitPrice, String currency,
            String unitPriceFormatted, BigDecimal surchargeCny) {

        /** Sin el recargo: lo que ve quien no administra. */
        public PriceTierView sinRecargo() {
            return new PriceTierView(minQty, maxQty, unitPrice, currency, unitPriceFormatted, null);
        }
    }

    /**
     * Un producto que no se pudo anunciar al bus del catálogo.
     *
     * <p>Existe para que el fallo SE VEA. El admin marca un producto como certificado, la petición
     * responde al instante y el envío se hace después: si ese envío no llega, sin esta lista nadie se
     * enteraría hasta echar en falta el producto en producción, semanas más tarde.
     */
    public record AnuncioBusFallidoView(UUID id, String externalId, String slug, String title, int intentos,
            String error, Instant actualizadoEn) {
    }

    public record ProductSummaryView(UUID id, String slug, String title, String mainImage, BigDecimal basePrice, // legacy display in CNY (kept for back-compat)
            String currency, // legacy CNY label
            // rating SIN reviewCount no se puede pintar: el catálogo trae un 5,0 de origen para lo que
            // nadie ha valorado, y sin el recuento la tarjeta enseña cinco estrellas llenas de una nota
            // que nadie ha puesto. La ficha ya lo recibía; el listado no, así que las dos pantallas
            // contaban cosas distintas del mismo producto.
            BigDecimal rating, int reviewCount, int monthlySales, BigDecimal trendScore, String status,
            // canonical + display
            BigDecimal priceUsd, // retail in USD canon
            BigDecimal displayPrice, // converted to user's currency (X-Currency)
            String displayCurrency, String displaySymbol, String displayFormatted, // string ya formateado por el backend ("28,26 €") — el front solo lo pinta
            // stock: inventoryCount es el rollup del proveedor; availableUnits es
            // la suma del stock por variantes activas (más fiel para fulfillment).
            Integer inventoryCount, Integer availableUnits,
            // verificación manual del admin (false = pendiente/con error, true = revisado OK)
            boolean verified,
            // Rebaja. Nulos cuando el producto no está en promoción: es lo que decide si el escaparate
            // pinta el precio anterior tachado o solo uno. Ya vienen formateados por el backend.
            String originalFormatted, Integer discountPercent, String promotionName,
            // Arancel: cuánto sube el derecho de aduana del carrito al añadir ESTE producto, y a qué grupo
            // de declaración pertenece. Nulos = no hay nada que prometer (carrito vacío) o el país ya no
            // cobra derecho por artículo, y entonces el distintivo no se pinta. El importe viene formateado
            // por el backend: el front no calcula importes.
            // dutyCovered = la tienda paga el derecho de aduana de este producto. NO depende del carrito,
            // así que se sabe también con el carrito vacío, y solo puede ser cierto donde hay derecho por
            // artículo que cubrir: hoy los 27 de la UE.
            Integer extraDutyCents, String extraDutyFormatted, UUID dutyGroupId, boolean dutyCovered,
            // shippingCovered = la tienda pone algo del porte de este producto de su bolsa de envío.
            // A diferencia del arancel, NO depende del país: la bolsa se descuenta del porte del pedido
            // vaya a donde vaya, así que se resuelve DENTRO del listado —es una propiedad del producto,
            // como `verified`— y no fuera con el carrito.
            boolean shippingCovered) {

        /** Sin arancel resuelto: el listado lo decora después, fuera de la caché, porque depende del carrito. */
        public ProductSummaryView(UUID id, String slug, String title, String mainImage, BigDecimal basePrice,
                String currency, BigDecimal rating, int reviewCount, int monthlySales, BigDecimal trendScore,
                String status, BigDecimal priceUsd, BigDecimal displayPrice, String displayCurrency,
                String displaySymbol, String displayFormatted, Integer inventoryCount, Integer availableUnits,
                boolean verified, String originalFormatted, Integer discountPercent, String promotionName) {
            this(id, slug, title, mainImage, basePrice, currency, rating, reviewCount, monthlySales, trendScore, status,
                    priceUsd, displayPrice, displayCurrency, displaySymbol, displayFormatted, inventoryCount,
                    availableUnits, verified, originalFormatted, discountPercent, promotionName, null, null, null,
                    false, false);
        }

        /** Sin promoción: atajo para los usos que no la calculan. */
        public ProductSummaryView(UUID id, String slug, String title, String mainImage, BigDecimal basePrice,
                String currency, BigDecimal rating, int reviewCount, int monthlySales, BigDecimal trendScore,
                String status, BigDecimal priceUsd, BigDecimal displayPrice, String displayCurrency,
                String displaySymbol, String displayFormatted, Integer inventoryCount, Integer availableUnits,
                boolean verified) {
            this(id, slug, title, mainImage, basePrice, currency, rating, reviewCount, monthlySales, trendScore, status,
                    priceUsd, displayPrice, displayCurrency, displaySymbol, displayFormatted, inventoryCount,
                    availableUnits, verified, null, null, null, null, null, null, false, false);
        }

        /**
         * La misma ficha con el arancel resuelto.
         *
         * <p>Se decora <b>después</b> del listado y no dentro, a propósito: el listado está cacheado y su
         * clave incluye los argumentos del método. Meter el carrito ahí crearía una entrada de caché por
         * cada combinación de carrito —que no tiene fin— y echaría del hueco a las páginas que de verdad
         * se repiten.
         */
        public ProductSummaryView withDuty(Integer extraDutyCents, String extraDutyFormatted, UUID dutyGroupId,
                boolean dutyCovered) {
            return new ProductSummaryView(id, slug, title, mainImage, basePrice, currency, rating, reviewCount,
                    monthlySales, trendScore, status, priceUsd, displayPrice, displayCurrency, displaySymbol,
                    displayFormatted, inventoryCount, availableUnits, verified, originalFormatted, discountPercent,
                    promotionName, extraDutyCents, extraDutyFormatted, dutyGroupId, dutyCovered, shippingCovered);
        }

        /**
         * La misma ficha declarando que la tienda pone parte del porte.
         *
         * <p>Va aparte de {@link #withDuty} porque no comparte nada con él: el arancel depende del carrito
         * y del país y se resuelve fuera de la caché; esto es una propiedad fija del producto. Mezclarlos
         * en un decorador único obligaría a conocer el carrito para contestar algo que no lo necesita.
         */
        public ProductSummaryView withShippingCovered(boolean cubierto) {
            return new ProductSummaryView(id, slug, title, mainImage, basePrice, currency, rating, reviewCount,
                    monthlySales, trendScore, status, priceUsd, displayPrice, displayCurrency, displaySymbol,
                    displayFormatted, inventoryCount, availableUnits, verified, originalFormatted, discountPercent,
                    promotionName, extraDutyCents, extraDutyFormatted, dutyGroupId, dutyCovered, cubierto);
        }
    }

    public record ProductDetailView(UUID id, String slug, String source, String externalId, SupplierView supplier,
            UUID categoryId, String title, String shortDescription, String description, String titleZh,
            String shortDescriptionZh, String descriptionZh, String brand, int moq, BigDecimal basePrice,
            String currency, BigDecimal rating, int reviewCount, int monthlySales, BigDecimal repurchaseRate,
            BigDecimal trendScore, String status, String sourceUrl, Instant ingestedAt, Instant lastSyncedAt,
            List<ProductImageView> images, List<VariantOptionView> variantOptions, List<VariantView> variants,
            List<PriceTierView> priceTiers,
            // pricing
            BigDecimal costUsd, BigDecimal retailUsd, BigDecimal displayPrice, String displayCurrency,
            String displaySymbol, String displayFormatted, BigDecimal appliedMarginPercent,
            // Desglose del total (base×margen + IVA + envío + recargo). SOLO ADMIN (null para usuario
            // final); el displayFormatted ya es el TOTAL que ve todo el mundo.
            String baseFormatted, String ivaFormatted, String shippingFormatted,
            // IVA chino en CNY, el valor CRUDO que teclea el admin (23-sep-2026). Venia solo formateado,
            // y sin el crudo no se podia ofrecer para editar: el 13 % es lo habitual, no una ley, y
            // corregirlo obligaba a reenviar la ficha entera por el importador. SOLO ADMIN.
            BigDecimal ivaCny,
            // Recargo fijo por producto (surcharge_cny, 30-ago-2026). surchargeCny = valor crudo en CNY
            // (el que edita el admin); surchargeFormatted = ya convertido a la moneda de la petición.
            // SOLO ADMIN (null para usuario final).
            BigDecimal surchargeCny, String surchargeFormatted,
            // Bolsas de subvención por producto (1-sep-2026), en CNY y tal como las teclea el admin.
            // Cada una subvenciona una sola cosa: shippingUserCny el porte del pedido, dutyUserCny el
            // arancel. SOLO ADMIN: el cliente ve su efecto en el desglose del checkout, no el importe.
            // Valor CRUDO en CNY (lo que teclea el admin al editar) y el mismo importe ya convertido a la
            // moneda de la petición, que es como se enseña. Mismo par que el recargo.
            BigDecimal shippingUserCny, BigDecimal dutyUserCny, String shippingUserFormatted, String dutyUserFormatted,
            // DROP-679: SEO por idioma (generado al publicar a partir del contenido real)
            String metaTitle, String metaDescription,
            // verificación manual del admin (false = pendiente/con error, true = revisado OK)
            boolean verified,
            // Vídeo de explicación del producto (columna product.video_url). El front lo muestra en la galería.
            String videoUrl, boolean hasVideo,
            // Rebaja vigente sobre este producto. Nulos si no la hay.
            String originalFormatted, Integer discountPercent, String promotionName,
            // Cumplimiento del Reglamento (UE) 2023/988: fabricante (art. 19.a), advertencias de seguridad
            // (art. 19.d) y operador económico establecido en la Unión (art. 16.3). Va agrupado en un solo
            // campo para no sumar seis más a un record que ya arrastra cuarenta.
            ProductComplianceView compliance,
            // Arancel: lo mismo que en la tarjeta del catálogo. Cuánto sube el derecho de aduana del
            // carrito por llevarse ESTE producto y a qué grupo de declaración pertenece. La ficha promete
            // lo mismo que el listado porque lo calcula el mismo servicio.
            // dutyCovered = la tienda paga el derecho de aduana de este producto; lo mismo que anuncia la
            // tarjeta, calculado igual. Solo puede ser cierto donde hay derecho por artículo: hoy la UE.
            Integer extraDutyCents, String extraDutyFormatted, UUID dutyGroupId, boolean dutyCovered,
            // shippingCovered = la tienda pone algo del porte de este producto de su bolsa de envío. Es el
            // mismo dato que ya anunciaba la TARJETA del catálogo y que la ficha no llevaba, así que el
            // producto prometía en el listado algo que al abrirlo desaparecía. Es PÚBLICO —un booleano, no
            // el importe— y por eso sobrevive a sinDatosInternos(): lo que no se publica es cuánto pone la
            // tienda, no que lo ponga.
            boolean shippingCovered) {

        /** La misma ficha con el arancel resuelto; se decora fuera del detalle, que va cacheado. */
        public ProductDetailView withDuty(Integer extraDutyCents, String extraDutyFormatted, UUID dutyGroupId,
                boolean dutyCovered) {
            return new ProductDetailView(id, slug, source, externalId, supplier, categoryId, title, shortDescription,
                    description, titleZh, shortDescriptionZh, descriptionZh, brand, moq, basePrice, currency, rating,
                    reviewCount, monthlySales, repurchaseRate, trendScore, status, sourceUrl, ingestedAt, lastSyncedAt,
                    images, variantOptions, variants, priceTiers, costUsd, retailUsd, displayPrice, displayCurrency,
                    displaySymbol, displayFormatted, appliedMarginPercent, baseFormatted, ivaFormatted,
                    shippingFormatted, ivaCny, surchargeCny, surchargeFormatted, shippingUserCny, dutyUserCny,
                    shippingUserFormatted, dutyUserFormatted, metaTitle, metaDescription, verified, videoUrl, hasVideo,
                    originalFormatted, discountPercent, promotionName, compliance, extraDutyCents, extraDutyFormatted,
                    dutyGroupId, dutyCovered, shippingCovered);
        }

        /**
         * La misma ficha SIN nada que solo le incumba a quien administra.
         *
         * <p>FUGA QUE CIERRA ESTO, medida contra producción el 8-sep-2026: la ficha pública entregaba a
         * cualquiera —sin sesión siquiera— el enlace exacto a la oferta de origen en 1688, su
         * identificador y el proveedor entero. Seis de seis fichas comprobadas. La interfaz sí ocultaba
         * el panel de administración, pero el JSON viajaba igual: bastaba abrir las herramientas del
         * navegador, o pedir la API a pelo, para saber de qué oferta sale cada producto y comprarla al
         * coste. En una tienda de dropshipping eso no es un dato de más, es el activo.
         *
         * <p>Se limpian TRES familias:
         * <ul>
         *   <li>el ORIGEN —proveedor, enlace, identificador externo, tasa de recompra—, que es lo que se
         *       filtraba;
         *   <li>los importes internos —coste, margen aplicado, recargo y las dos bolsas de subvención—,
         *       que este mismo record ya declara «SOLO ADMIN» y que hoy llegan vacíos por cómo se
         *       construyen: aquí se hace explícito, para que dejen de depender de eso;
         *   <li>las marcas de tiempo de ingesta, que delatan cuándo se cargó y cuándo se sincronizó.
         * </ul>
         *
         * <p>NO se toca el sourceUrl de las IMÁGENES, que es otra cosa: la dirección de origen de cada
         * foto, necesaria para servirlas. Solo se limpia el del producto.
         *
         * <p>Va como copia del record y no como filtro de serialización a propósito: el día que alguien
         * añada un campo, el compilador le obliga a pasar por aquí y a decidir si es público. Un filtro
         * por anotaciones se olvida en silencio, que es exactamente como se llegó a esto.
         */
        public ProductDetailView sinDatosInternos() {
            return new ProductDetailView(id, slug, null, null, null, categoryId, title, shortDescription, description,
                    titleZh, shortDescriptionZh, descriptionZh, brand, moq, basePrice, currency, rating, reviewCount,
                    monthlySales, null, trendScore, status, null, null, null, images, variantOptions, variants,
                    tramosSinRecargo(), null, null, displayPrice, displayCurrency, displaySymbol, displayFormatted,
                    // margen aplicado, base, IVA y envio formateados, el IVA crudo y el recargo
                    null, null, null, null, null, null, null, null, null, null, null, metaTitle, metaDescription,
                    verified, videoUrl, hasVideo, originalFormatted, discountPercent, promotionName, compliance,
                    extraDutyCents, extraDutyFormatted, dutyGroupId, dutyCovered, shippingCovered);
        }

        /**
         * La misma ficha SIN los importes internos, pero CON el origen.
         *
         * <p>Es el recorte de quien REVISA las fotos. Necesita el enlace a la oferta de origen para
         * cotejar la galería contra lo que vende el proveedor —sin él la revisión es a ciegas—, y no
         * necesita saber a cuánto sale el artículo ni cuánto se gana con él.
         *
         * <p>Se limpia UNA de las tres familias que limpia {@link #sinDatosInternos()}: coste, precio de
         * venta interno, margen aplicado, el desglose en yuanes y las dos bolsas de subvención. El origen
         * y las marcas de ingesta se quedan.
         *
         * <p>Va como copia del record, igual que su hermana y por el mismo motivo: el día que alguien
         * añada un campo, el compilador le obliga a pasar por aquí y a decidir de quién es.
         */
        public ProductDetailView sinImportesInternos() {
            return new ProductDetailView(id, slug, source, externalId, supplier, categoryId, title, shortDescription,
                    description, titleZh, shortDescriptionZh, descriptionZh, brand, moq, basePrice, currency, rating,
                    reviewCount, monthlySales, repurchaseRate, trendScore, status, sourceUrl, ingestedAt, lastSyncedAt,
                    images, variantOptions, variants, tramosSinRecargo(), null, null, displayPrice, displayCurrency,
                    displaySymbol, displayFormatted,
                    // margen aplicado, base, IVA y envio formateados, el IVA crudo y el recargo
                    null, null, null, null, null, null, null, null, null, null, null, metaTitle, metaDescription,
                    verified, videoUrl, hasVideo, originalFormatted, discountPercent, promotionName, compliance,
                    extraDutyCents, extraDutyFormatted, dutyGroupId, dutyCovered, shippingCovered);
        }

        /** Los tramos con el recargo fuera: es un importe interno, igual que el resto del desglose. */
        private List<PriceTierView> tramosSinRecargo() {
            return priceTiers == null ? null : priceTiers.stream().map(PriceTierView::sinRecargo).toList();
        }

        /** Sin promoción: atajo para los usos que no la calculan. */
        public ProductDetailView(UUID id, String slug, String source, String externalId, SupplierView supplier,
                UUID categoryId, String title, String shortDescription, String description, String titleZh,
                String shortDescriptionZh, String descriptionZh, String brand, int moq, BigDecimal basePrice,
                String currency, BigDecimal rating, int reviewCount, int monthlySales, BigDecimal repurchaseRate,
                BigDecimal trendScore, String status, String sourceUrl, Instant ingestedAt, Instant lastSyncedAt,
                List<ProductImageView> images, List<VariantOptionView> variantOptions, List<VariantView> variants,
                List<PriceTierView> priceTiers, BigDecimal costUsd, BigDecimal retailUsd, BigDecimal displayPrice,
                String displayCurrency, String displaySymbol, String displayFormatted, BigDecimal appliedMarginPercent,
                String baseFormatted, String ivaFormatted, String shippingFormatted, String metaTitle,
                String metaDescription, boolean verified, String videoUrl, boolean hasVideo) {
            this(id, slug, source, externalId, supplier, categoryId, title, shortDescription, description, titleZh,
                    shortDescriptionZh, descriptionZh, brand, moq, basePrice, currency, rating, reviewCount,
                    monthlySales, repurchaseRate, trendScore, status, sourceUrl, ingestedAt, lastSyncedAt, images,
                    variantOptions, variants, priceTiers, costUsd, retailUsd, displayPrice, displayCurrency,
                    displaySymbol, displayFormatted, appliedMarginPercent, baseFormatted, ivaFormatted,
                    shippingFormatted, null, null, null, null, null, null, null, metaTitle, metaDescription, verified,
                    videoUrl, hasVideo, null, null, null, null, null, null, null, false, false);
        }
    }

    public record BestsellerView(UUID productId, String slug, String title, String mainImage, int rank, String listCode,
            Instant capturedAt) {
    }

    /**
     * Una promoción vigente, para anunciarla en la portada.
     *
     * <p>Solo lleva lo que el escaparate necesita pintar: ni el alcance interno ni los topes de uso,
     * que son cosa del admin.
     */
    public record LivePromotionView(UUID id, String name, Integer percentOff, String endsAt, String scope,
            List<ProductSummaryView> products) {
    }

}
