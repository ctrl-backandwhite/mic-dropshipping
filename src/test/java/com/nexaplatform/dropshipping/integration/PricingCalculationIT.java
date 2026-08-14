package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.PriceTierView;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.PricingChannelHolder;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Certificación CALC-PRE: el precio que se enseña y el que se cobra, comprobados AL CÉNTIMO contra un
 * importe calculado a mano.
 *
 * <p><b>Datos de partida</b> (sembrados aquí para que la cuenta sea reproducible; son las mismas tasas que
 * trae la migración v2):
 * <ul>
 *   <li>CNY = 7,24 por dólar · EUR = 0,92 por dólar.</li>
 *   <li>Producto tipo: base 72,40 CNY (= 10,00 $ de coste), IVA 7,24 CNY (= 1,00 $), envío 14,48 CNY (= 2,00 $).</li>
 *   <li>Margen del escaparate 150 %, margen de integración 75 %, ajuste MOQ 50 %.</li>
 * </ul>
 *
 * <p>De ahí sale el precio de referencia: 10,00 × 2,5 = 25,00 de base con margen, más 1,00 de IVA y 2,00 de
 * envío (que NO llevan margen) = <b>28,00 $</b>. Cada caso parte de esa cuenta y cambia una sola variable.
 *
 * <p>El coste del proveedor —en yuanes— es información interna: no puede viajar en ninguna respuesta que
 * vea un cliente, porque revela el margen y con él la lista de proveedores.
 */
class PricingCalculationIT extends BaseIntegration {

    /** 72,40 CNY ÷ 7,24 = 10,00 $ de coste. Todo el resto de importes de la clase deriva de aquí. */
    private static final BigDecimal BASE_CNY = new BigDecimal("72.40");
    private static final BigDecimal IVA_CNY = new BigDecimal("7.24");
    private static final BigDecimal ENVIO_CNY = new BigDecimal("14.48");

    @Autowired
    private PricingService precios;
    @Autowired
    private MarginService margenes;
    @Autowired
    private CurrencyRateService divisas;
    @Autowired
    private ProductRepository productos;
    @Autowired
    private ProductPriceTierRepository tramos;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private PlatformTransactionManager gestorTransacciones;

    private TransactionTemplate transacciones;

    @BeforeEach
    void sembrarDivisasReglasYAjustes() {
        transacciones = new TransactionTemplate(gestorTransacciones);
        sembrarDivisa("USD", "US Dollar", "$", "en-US", "1.00");
        sembrarDivisa("EUR", "Euro", "€", "es-ES", "0.92");
        sembrarDivisa("CNY", "Chinese Yuan", "¥", "zh-CN", "7.24");
        // La caché de divisas (TTL 5 min) sobrevive al TRUNCATE de BaseIntegration; `overrideRate` es el
        // único punto público que la vuelve a leer de la tabla, y con USD = 1 la operación es neutra.
        divisas.overrideRate("USD", BigDecimal.ONE);

        sembrarMargenGlobal("STOREFRONT", "150.00");
        sembrarMargenGlobal("INTEGRATION", "75.00");
        jdbcTemplate.update("INSERT INTO moq_margin_setting (id, enabled, factor_percent) VALUES (1, TRUE, 50) "
                + "ON CONFLICT (id) DO UPDATE SET enabled = TRUE, factor_percent = 50");
        margenes.invalidateCache();
    }

    @AfterEach
    void limpiarContextoDeHilo() {
        // Divisa y canal viven en ThreadLocal: sin limpiarlos, un test contagia al siguiente el euro o el
        // canal de integración y los importes dejan de ser los que dice el nombre del caso.
        CurrencyHolder.clear();
        PricingChannelHolder.clear();
    }

    /* ==================================================================================
     * 1. Margen: por canal, por especificidad y por MOQ
     * ================================================================================== */

    @Test
    @DisplayName("Precio de escaparate: 10,00 $ de coste × 150 % + 1,00 de IVA + 2,00 de envío = 28,00 $")
    void elPrecioDeEscaparateSaleExacto() {
        UUID producto = sembrarProducto("camiseta-basica", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);

        PricedAmount p = precioDe(producto);

        assertThat(p.costUsd()).isEqualByComparingTo("10.0000");
        assertThat(p.appliedMarginPercent()).isEqualByComparingTo("150.00");
        assertThat(p.baseRetailUsd()).isEqualByComparingTo("25.0000");
        assertThat(p.ivaUsd()).isEqualByComparingTo("1.0000");
        assertThat(p.shippingUsd()).isEqualByComparingTo("2.0000");
        assertThat(p.retailUsd()).isEqualByComparingTo("28.00");
        assertThat(p.displayAmount()).isEqualByComparingTo("28.00");
        assertThat(p.displayCurrency()).isEqualTo("USD");
        // El string lo compone el backend: el frontend solo lo pinta, así que aquí se fija su forma exacta.
        assertThat(p.displayFormatted()).isEqualTo("$28.00");
    }

    @Test
    @DisplayName("El margen NO se aplica al IVA ni al envío: solo a la base del proveedor")
    void elMargenSoloSeAplicaALaBase() {
        UUID producto = sembrarProducto("solo-base", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);

        PricedAmount p = precioDe(producto);

        // Si el margen se aplicara al total del proveedor (10 + 1 + 2 = 13), el precio sería 32,50 $.
        assertThat(p.retailUsd()).isEqualByComparingTo("28.00");
        assertThat(p.retailUsd()).isNotEqualByComparingTo("32.50");
    }

    @Test
    @DisplayName("El canal de integración aplica su propio margen (75 %): 20,50 $, no 28,00 $")
    void elCanalDeIntegracionUsaSuPropioMargen() {
        UUID producto = sembrarProducto("mismo-producto-dos-canales", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);

        PricedAmount escaparate = precioDe(producto);

        PricingChannelHolder.set(PriceRuleChannel.INTEGRATION);
        PricedAmount integracion = precioDe(producto);

        assertThat(escaparate.retailUsd()).isEqualByComparingTo("28.00");
        // 10,00 × 1,75 = 17,50 de base + 1,00 + 2,00 = 20,50.
        assertThat(integracion.retailUsd()).isEqualByComparingTo("20.50");
        assertThat(integracion.appliedMarginPercent()).isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("Un producto con MOQ > 1 aplica la MITAD del margen: 20,50 $")
    void unProductoConMoqAplicaLaMitadDelMargen() {
        UUID producto = sembrarProducto("pack-de-cinco", BASE_CNY, IVA_CNY, ENVIO_CNY, 5);

        PricedAmount p = precioDe(producto);

        // 150 % × 50 % = 75 % efectivo → 10,00 × 1,75 = 17,50 + 1,00 + 2,00 = 20,50.
        assertThat(p.appliedMarginPercent()).isEqualByComparingTo("75.000000");
        assertThat(p.retailUsd()).isEqualByComparingTo("20.50");
    }

    @Test
    @DisplayName("El porcentaje del ajuste MOQ es configurable: al 25 % el precio baja a 16,75 $")
    void elPorcentajeDelAjusteMoqEsConfigurable() {
        UUID producto = sembrarProducto("pack-al-veinticinco", BASE_CNY, IVA_CNY, ENVIO_CNY, 5);

        margenes.updateMoqMargin(true, new BigDecimal("25"));

        // 150 % × 25 % = 37,5 % efectivo → 10,00 × 1,375 = 13,75 + 1,00 + 2,00 = 16,75.
        assertThat(precioDe(producto).retailUsd()).isEqualByComparingTo("16.75");
    }

    @Test
    @DisplayName("Con el ajuste MOQ desactivado, un producto con MOQ > 1 cobra el margen completo")
    void conElAjusteMoqDesactivadoSeCobraElMargenCompleto() {
        UUID producto = sembrarProducto("pack-sin-ajuste", BASE_CNY, IVA_CNY, ENVIO_CNY, 5);

        margenes.updateMoqMargin(false, new BigDecimal("50"));

        assertThat(precioDe(producto).retailUsd()).isEqualByComparingTo("28.00");
    }

    @Test
    @DisplayName("MOQ exactamente 1 no reduce nada: el ajuste solo aplica a partir de 2 unidades")
    void elMoqDeUnaUnidadNoReduceElMargen() {
        assertThat(precioDe(sembrarProducto("moq-uno", BASE_CNY, IVA_CNY, ENVIO_CNY, 1)).retailUsd())
                .isEqualByComparingTo("28.00");
        assertThat(precioDe(sembrarProducto("moq-dos", BASE_CNY, IVA_CNY, ENVIO_CNY, 2)).retailUsd())
                .isEqualByComparingTo("20.50");
    }

    @Test
    @DisplayName("Una regla de PRODUCTO gana a la GLOBAL: 300 % de margen → 43,00 $")
    void laReglaMasEspecificaGanaALaGlobal() {
        UUID producto = sembrarProducto("con-regla-propia", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);
        jdbcTemplate.update("INSERT INTO price_rule (id, scope, scope_id, margin_type, margin_value, active, "
                + "position, channel, description) VALUES (gen_random_uuid(), 'PRODUCT', ?, 'PERCENTAGE', "
                + "300.00, TRUE, 0, 'STOREFRONT', 'regla del producto')", producto);
        margenes.invalidateCache();

        // 10,00 × 4 = 40,00 de base + 1,00 + 2,00 = 43,00.
        assertThat(precioDe(producto).retailUsd()).isEqualByComparingTo("43.00");
    }

    /* ==================================================================================
     * 2. Divisa de visualización y redondeo
     * ================================================================================== */

    @Test
    @DisplayName("Al cambiar a euros el importe se recalcula con la tasa vigente: 25,76 €")
    void alCambiarDeDivisaSeRecalculaConLaTasaVigente() {
        UUID producto = sembrarProducto("en-euros", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);

        CurrencyHolder.set("EUR");
        PricedAmount p = precioDe(producto);

        // 28,00 $ × 0,92 = 25,76 €.
        assertThat(p.displayAmount()).isEqualByComparingTo("25.76");
        assertThat(p.displayCurrency()).isEqualTo("EUR");
        assertThat(p.displaySymbol()).isEqualTo("€");
        // Separadores del locale de la divisa (es-ES): coma decimal y símbolo detrás.
        assertThat(normalizado(p.displayFormatted())).isEqualTo("25,76 €");
        // El cobro canónico NO cambia con la divisa que mire el cliente: se cobra en dólares.
        assertThat(p.retailUsd()).isEqualByComparingTo("28.00");
    }

    @Test
    @DisplayName("El importe canónico en dólares es el mismo mire el cliente la divisa que mire")
    void elImporteCanonicoNoDependeDeLaDivisaQueSeMire() {
        UUID producto = sembrarProducto("canonico-estable", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);

        CurrencyHolder.set("USD");
        BigDecimal enDolares = precioDe(producto).retailUsd();
        CurrencyHolder.set("EUR");
        BigDecimal enEuros = precioDe(producto).retailUsd();
        CurrencyHolder.set("CNY");
        BigDecimal enYuanes = precioDe(producto).retailUsd();

        assertThat(enEuros).isEqualByComparingTo(enDolares);
        assertThat(enYuanes).isEqualByComparingTo(enDolares);
        assertThat(enDolares).isEqualByComparingTo("28.00");
    }

    @Test
    @DisplayName("El total se redondea UNA sola vez, sobre el importe canónico, no sumando componentes")
    void elTotalSeRedondeaUnaSolaVezSobreElCanonico() {
        // IVA de 0,0362 CNY = 0,0050 $: medio céntimo exacto, el peor caso para el redondeo.
        UUID producto = sembrarProducto("medio-centimo", BASE_CNY, new BigDecimal("0.0362"), ENVIO_CNY, 1);

        CurrencyHolder.set("EUR");
        PricedAmount p = precioDe(producto);

        assertThat(p.baseRetailUsd()).isEqualByComparingTo("25.0000");
        assertThat(p.ivaUsd()).isEqualByComparingTo("0.0050");
        assertThat(p.shippingUsd()).isEqualByComparingTo("2.0000");
        // Canónico: 25,00 + 0,01 (medio céntimo hacia arriba) + 2,00 = 27,01 $.
        assertThat(p.retailUsd()).isEqualByComparingTo("27.01");
        // Y el precio que se enseña sale de ESE importe: 27,01 × 0,92 = 24,8492 → 24,85 €.
        assertThat(p.displayAmount()).isEqualByComparingTo("24.85");

        // OBSERVACIÓN (no es un fallo de cobro, pero conviene tenerlo por escrito): el desglose que ve el
        // admin se convierte componente a componente —23,00 + 0,00 + 1,84 = 24,84 €— y por tanto NO suma
        // el total que se enseña, 24,85 €. Un céntimo de diferencia, siempre en el desglose informativo,
        // nunca en lo que se cobra. El comentario de PricingService dice que el desglose «SIEMPRE cuadra»,
        // y en este caso no cuadra.
        assertThat(normalizado(p.baseFormatted())).isEqualTo("23,00 €");
        assertThat(normalizado(p.ivaFormatted())).isEqualTo("0,00 €");
        assertThat(normalizado(p.shippingFormatted())).isEqualTo("1,84 €");
        assertThat(normalizado(p.displayFormatted())).isEqualTo("24,85 €");
    }

    @Test
    @DisplayName("Un producto sin precio no se tarifica: todo a null, nunca 0 (que se leería como gratis)")
    void unProductoSinPrecioNoSeTarifica() {
        UUID producto = sembrarProducto("sin-precio", null, null, null, 1);

        PricedAmount p = precioDe(producto);

        assertThat(p.costUsd()).isNull();
        assertThat(p.retailUsd()).isNull();
        assertThat(p.displayAmount()).isNull();
        assertThat(p.displayFormatted()).isNull();
    }

    /* ==================================================================================
     * 3. Variantes y tramos por cantidad
     * ================================================================================== */

    @Test
    @DisplayName("El precio de cabecera sale de la variante ACTIVA más barata, no del base_price")
    void elPrecioDeCabeceraSaleDeLaVarianteMasBarata() {
        UUID producto = sembrarProducto("con-variantes", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);
        sembrarVariante(producto, "SKU-CARA", new BigDecimal("72.40"), true);
        sembrarVariante(producto, "SKU-BARATA", new BigDecimal("50.68"), true);
        // Una variante inactiva más barata todavía no puede tirar del precio hacia abajo: no se puede comprar.
        sembrarVariante(producto, "SKU-INACTIVA", new BigDecimal("14.48"), false);

        // 50,68 CNY ÷ 7,24 = 7,00 $ → 7,00 × 2,5 = 17,50 + 1,00 + 2,00 = 20,50 $.
        assertThat(precioDe(producto).retailUsd()).isEqualByComparingTo("20.50");
    }

    @Test
    @DisplayName("Un tramo por cantidad se tarifica igual que la ficha: IVA y envío incluidos (15,50 $)")
    void elTramoPorCantidadLlevaIvaYEnvioComoLaFicha() {
        UUID producto = sembrarProducto("con-tramos", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);
        sembrarTramo(producto, 10, new BigDecimal("36.20"));

        PriceTierView tramo = transacciones.execute(estado -> {
            List<ProductPriceTierEntity> lista = tramos.findByProductIdOrderByMinQtyAsc(producto);
            return productMapper.toPriceTierView(lista.get(0));
        });

        // 36,20 CNY ÷ 7,24 = 5,00 $ de coste → 5,00 × 2,5 = 12,50 + 1,00 de IVA + 2,00 de envío = 15,50 $.
        // Sin IVA ni envío la ficha anunciaría 12,50 $ y al pagar se cobrarían 15,50: un 24 % más.
        assertThat(tramo.minQty()).isEqualTo(10);
        assertThat(tramo.unitPrice()).isEqualByComparingTo("15.50");
        assertThat(tramo.currency()).isEqualTo("USD");
        assertThat(tramo.unitPriceFormatted()).isEqualTo("$15.50");
    }

    @Test
    @DisplayName("Al alcanzar el tramo el unitario BAJA respecto del precio de una unidad")
    void alAlcanzarElTramoElUnitarioBaja() {
        UUID producto = sembrarProducto("tramo-mas-barato", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);
        sembrarTramo(producto, 10, new BigDecimal("36.20"));

        BigDecimal unaUnidad = precioDe(producto).retailUsd();
        PriceTierView tramo = transacciones.execute(estado ->
                productMapper.toPriceTierView(tramos.findByProductIdOrderByMinQtyAsc(producto).get(0)));

        assertThat(unaUnidad).isEqualByComparingTo("28.00");
        assertThat(tramo.unitPrice()).isEqualByComparingTo("15.50");
        assertThat(tramo.unitPrice()).isLessThan(unaUnidad);
    }

    /* ==================================================================================
     * 4. El coste en yuanes no puede salir en una respuesta de usuario
     * ================================================================================== */

    @Test
    @DisplayName("La ficha de un usuario no lleva coste, retail en dólares, margen ni desglose")
    void laFichaDeUnUsuarioNoLlevaCosteNiMargen() {
        UUID producto = sembrarProducto("ficha-usuario", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);

        client.get().uri("/api/catalog/products/by-id/{id}?lang=es", producto)
                .header("Authorization", bearer(jwt.userToken("USER"))).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.costUsd").doesNotExist()
                .jsonPath("$.retailUsd").doesNotExist()
                .jsonPath("$.appliedMarginPercent").doesNotExist()
                .jsonPath("$.baseFormatted").doesNotExist()
                .jsonPath("$.ivaFormatted").doesNotExist()
                .jsonPath("$.shippingFormatted").doesNotExist()
                // Lo que sí ve: el precio de venta ya compuesto y formateado.
                .jsonPath("$.displayFormatted").isEqualTo("$28.00")
                .jsonPath("$.displayPrice").value(v -> importeJson(v, "28.00"));
    }

    /**
     * Ojo con el contrato: esta ruta devuelve {@code StorefrontViews.VariantView}, que lleva el importe
     * ({@code price}) pero NO un precio ya formateado — el {@code priceFormatted} vive en las variantes
     * que van dentro de la ficha ({@code CatalogDtos.VariantView}), y ese lo comprueba la ficha. Aquí se
     * mide lo que sí depende de esta ruta: que el número publicado sea el de VENTA y no el coste en
     * yuanes del proveedor.
     */
    @Test
    @DisplayName("La lista de variantes devuelve el PRECIO DE VENTA (28,00 $), nunca el coste en yuanes")
    void laListaDeVariantesNoDevuelveElCosteEnYuanes() {
        UUID producto = sembrarProducto("variantes-usuario", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);
        sembrarVariante(producto, "SKU-1", BASE_CNY, true);

        client.get().uri("/api/catalog/products/{id}/variants", producto)
                .header("Authorization", bearer(jwt.userToken("USER"))).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].sku").isEqualTo("SKU-1")
                // 72,40 es el coste del proveedor en CNY: si apareciera aquí, el margen sería público.
                .jsonPath("$[0].price").value(v -> importeJson(v, "28.00"))
                .jsonPath("$[0].price").value(v -> importeDistintoJson(v, "72.40"));
    }

    /**
     * Cierre del hallazgo: {@code ProductMapper.toDetail} pasaba {@code basePrice}/{@code currency} sin
     * filtrar por rol, a diferencia de {@code costUsd}/{@code retailUsd}. Con el coste en yuanes y el precio
     * de venta al lado, el margen exacto de cada producto se despejaba con una división. El escaparate
     * (ProductDetailPage, ProductCard) los usaba como precio de reserva cuando faltaba {@code displayPrice};
     * ese apaño se retiró en el mismo movimiento: si no hay precio de VENTA, se pinta un guión.
     */
    @Test
    @DisplayName("La ficha de un usuario no debería llevar el precio base del proveedor en yuanes")
    void laFichaDeUnUsuarioNoDeberiaLlevarElCosteEnYuanes() {
        UUID producto = sembrarProducto("fuga-cny", BASE_CNY, IVA_CNY, ENVIO_CNY, 1);

        client.get().uri("/api/catalog/products/by-id/{id}?lang=es", producto)
                .header("Authorization", bearer(jwt.userToken("USER"))).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.basePrice").doesNotExist()
                .jsonPath("$.currency").doesNotExist();
    }

    /* ==================================================================================
     * Utilidades de siembra
     * ================================================================================== */

    /** Tarifica el producto por el mismo camino que la ficha, dentro de una transacción (variantes lazy). */
    private PricedAmount precioDe(UUID productoId) {
        return transacciones.execute(estado -> {
            ProductEntity entidad = productos.findWithDetailsById(productoId).orElseThrow();
            return precios.priceFor(entidad);
        });
    }

    private UUID sembrarProducto(String slug, BigDecimal baseCny, BigDecimal ivaCny, BigDecimal envioCny,
            int moq) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, title_zh, moq, base_price, "
                + "currency, iva_cny, shipping_cny, status, hs_code, weight_grams) "
                + "VALUES (?, ?, ?, '1688', ?, ?, ?, 'CNY', ?, ?, 'ACTIVE', '610910', 500)",
                id, slug, "ext-" + slug, "测试商品 " + slug, moq, baseCny, ivaCny, envioCny);
        return id;
    }

    private void sembrarVariante(UUID productoId, String sku, BigDecimal precioCny, boolean activa) {
        jdbcTemplate.update("INSERT INTO product_variant (id, product_id, sku, title, price, stock, active) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, ?, 100, ?)", productoId, sku, sku, precioCny, activa);
    }

    private void sembrarTramo(UUID productoId, int minQty, BigDecimal precioUnitarioCny) {
        jdbcTemplate.update("INSERT INTO product_price_tier (id, product_id, min_qty, unit_price, currency) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, 'CNY')", productoId, minQty, precioUnitarioCny);
    }

    private void sembrarDivisa(String codigo, String nombre, String simbolo, String locale, String tasa) {
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, TRUE) ON CONFLICT (code) DO UPDATE "
                + "SET rate_vs_usd = EXCLUDED.rate_vs_usd, active = TRUE",
                codigo, nombre, simbolo, locale, new BigDecimal(tasa));
    }

    private void sembrarMargenGlobal(String canal, String porcentaje) {
        jdbcTemplate.update("INSERT INTO price_rule (id, scope, scope_id, margin_type, margin_value, active, "
                + "position, channel, description) VALUES (gen_random_uuid(), 'GLOBAL', NULL, 'PERCENTAGE', "
                + "?, TRUE, 100, ?, ?)", new BigDecimal(porcentaje), canal, "margen " + canal);
    }

    /**
     * Los formatos de divisa del JDK separan importe y símbolo con espacio DURO (U+00A0) según la versión
     * de CLDR. Se normaliza a espacio normal para que el caso compruebe los separadores y no la versión de
     * la máquina virtual donde corre.
     */
    private static String normalizado(String formateado) {
        return formateado == null ? null : formateado.replace('\u00A0', ' ').replace('\u202F', ' ');
    }

    /** Comprueba un importe de una respuesta JSON al céntimo, llegue con el tipo que llegue. */
    private static void importeJson(Object valor, String esperado) {
        assertThat(new BigDecimal(String.valueOf(valor))).isEqualByComparingTo(esperado);
    }

    /** Y el contrapunto: que un importe NO sea el indicado (p. ej. el coste en yuanes del proveedor). */
    private static void importeDistintoJson(Object valor, String prohibido) {
        assertThat(new BigDecimal(String.valueOf(valor))).isNotEqualByComparingTo(prohibido);
    }
}
