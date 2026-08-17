package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService.CheckoutTotals;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.Line;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El derecho temporal de 3 EUR de la Unión Europea, comprobado <b>regla a regla contra la norma</b>.
 *
 * <p><b>Por qué existe este fichero.</b> El cálculo del arancel es dinero real en las dos direcciones: si se
 * cobran líneas de más, el cliente paga un sobreprecio que no debe; si se cobran de menos, la diferencia la
 * pone el comercio al despachar. Y la norma no es intuitiva — «item» no significa «artículo» — así que la
 * única forma de que dentro de seis meses se pueda releer el cálculo y saber de dónde sale cada número es
 * que cada prueba cite el artículo que la sostiene. Eso es lo que hace el javadoc de cada test.
 *
 * <h2>Fuentes oficiales (verificadas el 14-ago-2026)</h2>
 * <ul>
 *   <li><b>[REG]</b> Reglamento (UE) 2026/382 del Consejo, de 11 de febrero de 2026 —
 *       <a href="https://eur-lex.europa.eu/eli/reg/2026/382/oj">eur-lex.europa.eu/eli/reg/2026/382/oj</a>.
 *       Su artículo 1 suprime el capítulo V del título II del Reglamento (CE) n.º 1186/2009 (la franquicia
 *       de los envíos de valor insignificante) y su artículo 2 establece, en su lugar, «<i>a customs duty of
 *       EUR 3 per item in a consignment the intrinsic value of which does not exceed a total of EUR 150</i>».
 *       Se aplica del <b>1 de julio de 2026 al 1 de julio de 2028</b>.</li>
 *   <li><b>[DA]</b> Reglamento Delegado (UE) 2015/2446 (UCC-DA) —
 *       <a href="https://eur-lex.europa.eu/eli/reg_del/2015/2446/oj">eur-lex.europa.eu/eli/reg_del/2015/2446/oj</a>.
 *       Artículo 1(61): definición de «item». Artículo 1(48): definición de «valor intrínseco».</li>
 *   <li><b>[GUÍA]</b> Comisión Europea, DG TAXUD, «<i>Importation and exportation of low value consignments —
 *       The EUR 3 temporary customs duty. Guidance for Member States and Trade</i>», versión de 2 de junio
 *       de 2026, ref. Ares(2026)5636357 —
 *       <a href="https://taxation-customs.ec.europa.eu/document/download/053e5b4e-f0be-4f20-9a23-3e3b659a6676_en?filename=Customs+Guidance+on+EUR+3+customs+duty.pdf">PDF en taxation-customs.ec.europa.eu</a>
 *       (publicada en
 *       <a href="https://taxation-customs.ec.europa.eu/news/guidance-and-legal-text-temporary-flat-fee-low-value-imports-which-will-apply-until-1-july-2028-2026-06-08_en">esta nota</a>).</li>
 * </ul>
 *
 * <h2>Cómo se aíslan los importes</h2>
 * <p>La regla del país se siembra con TODOS los demás recargos a cero (handling fijo, handling porcentual,
 * prepago de IVA, recargo por despacho formal y arancel ad valorem). Así {@code handlingFeeCents} contiene
 * <b>solo</b> el derecho temporal y se puede comparar al céntimo. El euro se siembra a 1,00 USD para que las
 * cifras de la norma (3 EUR y 150 EUR) se lean literalmente en céntimos: 300 y 15.000.
 */
class CustomsRegulationIT extends BaseIntegration {

    /** España: Estado miembro de la UE, sujeto al derecho temporal. */
    private static final String DESTINO_UE = "ES";
    /** Estados Unidos: fuera del territorio aduanero de la Unión, ajeno a esta norma. */
    private static final String DESTINO_NO_UE = "US";

    /** [REG] art. 2: 3 EUR por «item». Con el euro a 1,00 USD son 300 céntimos. */
    private static final int DERECHO_POR_LINEA = 300;
    /** [REG] art. 2: franquicia de 150 EUR de valor intrínseco. */
    private static final int FRANQUICIA = 15_000;

    /* Subpartidas del SA reales, para que los ejemplos se puedan contrastar con el TARIC. */
    private static final String HS_VAQUEROS = "620342";
    private static final String HS_CAMISETAS = "610910";
    private static final String HS_ZAPATOS = "640399";
    private static final String HS_TRAJES_MUJER = "610419";
    private static final String HS_PERFUME = "330300";
    private static final String HS_LICOR = "220870";
    private static final String HS_TABACO = "240220";

    @Autowired
    private CustomsDutyLinesService dutyLines;

    @Autowired
    private CustomsValuationService valuation;

    @Autowired
    private CheckoutTotalsService checkoutTotals;

    @Autowired
    private CurrencyRateService currencyRates;

    /**
     * {@link BaseIntegration} vacía todas las tablas antes de cada test, así que la norma se siembra aquí:
     * es el estado exacto sobre el que se afirma, sin depender de lo que traigan las migraciones.
     */
    @BeforeEach
    void sembrarLaNormaAduanera() {
        // El euro a 1,00 USD: no se está probando la conversión de divisa, sino la aritmética del arancel.
        // Sembrarlo 1:1 hace que 3 EUR sean 300 céntimos y 150 EUR sean 15.000, tal y como los escribe la
        // norma, y evita que un cambio en la tasa del día haga fallar una prueba de derecho aduanero.
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), 'USD', 'US Dollar', '$', 'en-US', 1.00000000, true)");
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), 'EUR', 'Euro', '€', 'es-ES', 1.00000000, true)");
        // El servicio de divisas cachea 5 minutos; sin refrescar seguiría viendo la tasa del arranque.
        currencyRates.warm();

        sembrarRegla(DESTINO_UE, "150.00", "EUR", "3.00", "EUR");
        // Fuera de la UE no hay derecho temporal: arancel por línea a 0 y sin franquicia configurada.
        sembrarRegla(DESTINO_NO_UE, "0.00", "USD", "0.00", "USD");

        // Sin límites de canal, todo viaja en un bulto. Los tests que necesiten reparto los fijan aparte.
        limitarBultos(0, 0);
    }

    /**
     * Regla aduanera del país con todos los recargos comerciales a cero, para que el importe resultante sea
     * exclusivamente el derecho temporal y se pueda comparar al céntimo.
     */
    private void sembrarRegla(String pais, String franquicia, String divisaFranquicia, String derecho,
            String divisaDerecho) {
        jdbcTemplate.update("INSERT INTO country_customs_rule (id, country_code, tax_mode, de_minimis_amount, "
                + "de_minimis_currency, over_threshold_policy, handling_fee_cents, handling_percent_bps, "
                + "over_threshold_surcharge_cents, duty_rate_bps, vat_prepay_percent_bps, "
                + "per_article_fee_amount, per_article_fee_currency, active) "
                + "VALUES (gen_random_uuid(), ?, 'DDP', CAST(? AS numeric), ?, 'SURCHARGE', 0, 0, 0, 0, 0, "
                + "CAST(? AS numeric), ?, true)",
                pais, franquicia, divisaFranquicia, derecho, divisaDerecho);
    }

    /** Fija los topes del canal de transporte (0 = sin tope) sobre el bean real del contexto. */
    private void limitarBultos(int maxUnidadesPorBulto, int maxValorCentimos) {
        CustomsDutyLinesService objetivo = AopTestUtils.getTargetObject(dutyLines);
        ReflectionTestUtils.setField(objetivo, "maxParcelUnits", maxUnidadesPorBulto);
        ReflectionTestUtils.setField(objetivo, "maxParcelValueCents", maxValorCentimos);
        ReflectionTestUtils.setField(objetivo, "maxParcelWeightGrams", 0);
    }

    /**
     * Da de alta un producto con el {@code hs_code} que se quiere probar y devuelve una línea de pedido que
     * lo referencia. La partida viaja tal y como se lee de la columna, que es lo que hace el checkout real.
     */
    private Line productoConPartida(String hsCode, int cantidad, int precioUnitarioCentimos) {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, title_zh, hs_code, weight_grams, "
                + "status) VALUES (?, ?, ?, ?, ?, 500, 'ACTIVE')",
                id, "producto-" + sufijo, "ext-" + sufijo, "测试商品", hsCode);
        String leido = jdbcTemplate.queryForObject("SELECT hs_code FROM product WHERE id = ?", String.class, id);
        // Sin descripción declarada: el producto se siembra sin traducciones, que es lo que
        // CustomsDutyLinesService.declaredDescriptionOf leería de él. Así estos casos siguen midiendo lo
        // que afirman —la clasificación arancelaria— y no el texto del título.
        return new Line(id, leido, null, "CN", cantidad, precioUnitarioCentimos, 500, 0, 0, 0, false);
    }

    /** Valor intrínseco del pedido: lo que el cliente paga por los bienes, sin envío ni impuestos. */
    private static int valorIntrinseco(List<Line> lineas) {
        int total = 0;
        for (Line linea : lineas) {
            total += linea.quantity() * linea.unitPriceCents();
        }
        return total;
    }

    /** Valoración aduanera completa del pedido: reparte en bultos y aplica la regla del país. */
    private CustomsValuation valorar(String pais, List<Line> lineas) {
        List<DutyParcel> bultos = dutyLines.parcelsOf(lineas);
        return valuation.valuate(pais, valorIntrinseco(lineas), 0, bultos);
    }

    /** Solo el derecho arancelario, en céntimos (el resto de recargos está sembrado a cero). */
    private int derechoDe(String pais, List<Line> lineas) {
        return valorar(pais, lineas).handlingFeeCents();
    }

    /* ==================== La unidad de cobro: la línea de declaración ==================== */

    /**
     * <b>Regla:</b> el derecho se cobra por línea de declaración, y la cantidad no lo multiplica.
     *
     * <p>[GUÍA] §3.3.1, pág. 12: «<i>the EUR 3 customs duty will automatically apply <b>per declaration line
     * irrespective of the quantity</b> (number of the articles) in that declaration line, provided that the
     * intrinsic value of all goods included in the declaration does not exceed EUR 150</i>».
     *
     * <p>Es la confusión que más dinero mueve: cinco vaqueros iguales son UNA línea (3 EUR), no cinco
     * (15 EUR). Cobrar por unidad serían 12 EUR de más en este solo pedido.
     */
    @Test
    @DisplayName("el derecho se cobra por línea de declaración, y la cantidad no lo multiplica")
    void elDerechoSeCobraPorLineaNoPorUnidad() {
        assertThat(derechoDe(DESTINO_UE, List.of(productoConPartida(HS_VAQUEROS, 5, 1_000))))
                .isEqualTo(DERECHO_POR_LINEA);
        assertThat(derechoDe(DESTINO_UE, List.of(productoConPartida(HS_VAQUEROS, 1, 1_000))))
                .isEqualTo(DERECHO_POR_LINEA);
    }

    /**
     * <b>Regla:</b> productos DISTINTOS que comparten clasificación arancelaria son UNA sola línea.
     *
     * <p>[DA] art. 1(61) define «item» como «<i>one or more goods in a consignment sharing the same tariff
     * classification, description and, if provided…, origin</i>» — no como «producto» ni como «referencia».
     *
     * <p>El ejemplo es literal de la [GUÍA] §3.3.1, pág. 12: un envío con tres prendas distintas (un traje de
     * fibra artificial, uno de lana y otro «otros») que comparten la subpartida <b>6104 19</b> se declara en
     * UNA línea H7 y paga «<i>1x EUR 3</i>». Tres artículos, un solo derecho.
     */
    @Test
    @DisplayName("productos distintos bajo la misma subpartida son una sola línea de declaración")
    void productosDistintosBajoLaMismaSubpartidaSonUnaLinea() {
        List<Line> pedido = List.of(
                productoConPartida(HS_TRAJES_MUJER, 1, 3_000),
                productoConPartida(HS_TRAJES_MUJER, 1, 3_000),
                productoConPartida(HS_TRAJES_MUJER, 1, 3_000));

        assertThat(derechoDe(DESTINO_UE, pedido)).isEqualTo(DERECHO_POR_LINEA);
    }

    /**
     * <b>Regla:</b> cada clasificación arancelaria distinta es una línea distinta, y paga su derecho.
     *
     * <p>[DA] art. 1(61): la agrupación exige compartir clasificación; sin eso son «items» separados.
     * [GUÍA] §3.3.1, pág. 13, ejemplo H1: tres líneas de declaración → «<i>The EUR 3 customs duty will apply
     * for each of these declaration lines</i>».
     *
     * <p>Se agrupa por los <b>6 primeros dígitos</b> (subpartida del SA) porque es lo que exige el conjunto
     * de datos H7, la declaración reducida de bajo valor que se usa con IOSS: [GUÍA] §3.3.1, pág. 12,
     * «<i>H6 and H7: 6-digit HS code needs to be declared</i>». Por eso da igual el detalle que traiga el
     * código: 6104199010 y 6104.19.90.90 caen en la misma línea.
     */
    @Test
    @DisplayName("cada subpartida distinta es una línea que paga su propio derecho")
    void cadaSubpartidaDistintaEsUnaLinea() {
        List<Line> tresFamilias = List.of(
                productoConPartida(HS_VAQUEROS, 1, 1_000),
                productoConPartida(HS_CAMISETAS, 1, 1_000),
                productoConPartida(HS_ZAPATOS, 1, 1_000));

        assertThat(derechoDe(DESTINO_UE, tresFamilias)).isEqualTo(3 * DERECHO_POR_LINEA);

        // El mismo código con más detalle del que cabe en el H7 sigue siendo una única subpartida.
        List<Line> mismaSubpartidaConDetalle = List.of(
                productoConPartida("6104199010", 1, 1_000),
                productoConPartida("610419 90 20", 1, 1_000),
                productoConPartida("6104.19.90.90", 1, 1_000));

        assertThat(derechoDe(DESTINO_UE, mismaSubpartidaConDetalle)).isEqualTo(DERECHO_POR_LINEA);
    }

    /**
     * <b>Regla:</b> un envío de muchas familias paga una línea por familia.
     *
     * <p>Es el pedido típico de la tienda y la comprobación de que el conteo escala: cinco familias
     * (vaqueros, camisetas, zapatos, trajes y perfume) son cinco líneas → 15 EUR. Contando artículos serían
     * trece → 39 EUR. Sostiene [DA] art. 1(61) y la [GUÍA] §3.3.1.
     */
    @Test
    @DisplayName("un envío con muchas familias paga una línea por familia, no por artículo")
    void unEnvioDeMuchasFamiliasPagaUnaLineaPorFamilia() {
        List<Line> pedido = List.of(
                productoConPartida(HS_VAQUEROS, 5, 1_000),
                productoConPartida(HS_CAMISETAS, 3, 1_000),
                productoConPartida(HS_ZAPATOS, 1, 1_000),
                productoConPartida(HS_TRAJES_MUJER, 3, 1_000),
                productoConPartida(HS_PERFUME, 1, 1_000));

        assertThat(derechoDe(DESTINO_UE, pedido)).isEqualTo(5 * DERECHO_POR_LINEA);
    }

    /**
     * <b>Regla:</b> sin clasificación arancelaria no se puede agrupar, así que cada producto es su propia
     * línea.
     *
     * <p>[DA] art. 1(61) condiciona la agrupación a «<i>sharing the same tariff classification</i>»: sin
     * código no hay nada que compartir. Agrupar dos productos sin clasificar sería atribuirles una partida
     * que nadie ha verificado, y de esa declaración responde el declarante ([GUÍA] §3.4.5, pág. 20: «<i>The
     * declarant is therefore responsible for the customs debt</i>»).
     *
     * <p>El criterio conservador cobra de más en el peor caso, nunca de menos: una línea sin cubrir la
     * pagaría el comercio al despachar. Un código de menos de 6 dígitos no llega a subpartida y se trata
     * igual que la ausencia de código.
     */
    @Test
    @DisplayName("un producto sin código HS cuenta como línea propia y no se agrupa con nadie")
    void unProductoSinCodigoHsCuentaComoLineaPropia() {
        List<Line> pedido = List.of(
                productoConPartida(null, 1, 1_000),
                productoConPartida("", 1, 1_000),
                productoConPartida("123", 1, 1_000),
                productoConPartida(HS_VAQUEROS, 1, 1_000));

        assertThat(derechoDe(DESTINO_UE, pedido)).isEqualTo(4 * DERECHO_POR_LINEA);
    }

    /**
     * <b>Regla:</b> una cantidad grande de la misma referencia sigue siendo una sola línea.
     *
     * <p>Es la consecuencia extrema de «<i>irrespective of the quantity</i>» ([GUÍA] §3.3.1, pág. 12):
     * doscientas unidades de un mismo artículo pagan 3 EUR, igual que una.
     *
     * <p>La segunda parte cubre el guardarraíl del código: {@code CustomsDutyLinesService.MAX_UNITS} acota a
     * 10.000 las unidades que se expanden en memoria para repartir en bultos. El conteo de líneas no se
     * altera. <b>Ojo:</b> por encima de ese tope el valor del bulto sí queda truncado (ver el informe), pero
     * el carrito limita la cantidad por línea muy por debajo, así que es defensa en profundidad frente a una
     * petición manipulada, no una ruta real.
     */
    @Test
    @DisplayName("una cantidad grande de la misma referencia sigue siendo una única línea")
    void unaCantidadGrandeSigueSiendoUnaUnicaLinea() {
        assertThat(derechoDe(DESTINO_UE, List.of(productoConPartida(HS_CAMISETAS, 200, 50))))
                .isEqualTo(DERECHO_POR_LINEA);

        // Cantidad absurda: el tope de expansión la acota sin reventar el proceso ni inventar líneas.
        assertThat(derechoDe(DESTINO_UE, List.of(productoConPartida(HS_CAMISETAS, 50_000, 1))))
                .isEqualTo(DERECHO_POR_LINEA);
    }

    /* ==================== La franquicia de 150 EUR ==================== */

    /**
     * <b>Regla:</b> el umbral es «no excede», así que 150,00 EUR EXACTOS siguen DENTRO del régimen de bajo
     * valor.
     *
     * <p>[REG] art. 2: «<i>a consignment the intrinsic value of which <b>does not exceed</b> a total of
     * EUR 150</i>». La [GUÍA] lo repite en §2.1, pág. 5 («<i>an intrinsic value <b>not exceeding</b>
     * EUR 150 per consignment</i>») y en §3.3.1, pág. 12 («<i>does not exceed EUR 150</i>»).
     *
     * <p>Es decir: la comparación es <b>estricta sobre el exceso</b> ({@code valor > 150}), no
     * {@code valor >= 150}. En el borde exacto se cobra el derecho de 3 EUR; un céntimo por encima ya no
     * aplica el importe fijo, sino el arancel normal del TARIC según la clasificación.
     */
    @Test
    @DisplayName("la franquicia es «no excede»: 150,00 EUR exactos siguen dentro del régimen de bajo valor")
    void laFranquiciaUsaComparacionEstricta() {
        CustomsValuation justoDebajo = valorar(DESTINO_UE,
                List.of(productoConPartida(HS_VAQUEROS, 1, FRANQUICIA - 1)));
        assertThat(justoDebajo.deMinimisExceeded()).isFalse();
        assertThat(justoDebajo.handlingFeeCents()).isEqualTo(DERECHO_POR_LINEA);

        // El borde exacto: 150,00 EUR NO exceden 150,00 EUR.
        CustomsValuation enElBorde = valorar(DESTINO_UE,
                List.of(productoConPartida(HS_VAQUEROS, 1, FRANQUICIA)));
        assertThat(enElBorde.deMinimisExceeded()).isFalse();
        assertThat(enElBorde.handlingFeeCents()).isEqualTo(DERECHO_POR_LINEA);

        // Un céntimo por encima: fuera del régimen, no se aplica el importe fijo de 3 EUR.
        CustomsValuation justoEncima = valorar(DESTINO_UE,
                List.of(productoConPartida(HS_VAQUEROS, 1, FRANQUICIA + 1)));
        assertThat(justoEncima.deMinimisExceeded()).isTrue();
        assertThat(justoEncima.handlingFeeCents()).isZero();
    }

    /**
     * <b>Regla:</b> el valor intrínseco son los bienes, y NO incluye el transporte.
     *
     * <p>[DA] art. 1(48), citado literalmente en la [GUÍA] §3.3.1, pág. 11: para mercancías comerciales es
     * «<i>the price of the goods themselves when sold for export to the customs territory of the Union,
     * <b>excluding transport and insurance costs</b>, unless they are included in the price and not
     * separately indicated on the invoice, and any other taxes and charges</i>».
     *
     * <p>Aquí se comprueba en el desglose real del checkout: un pedido de 150,00 EUR de mercancía con 50,00
     * de envío declara 150,00 —no 200,00— y por tanto sigue dentro de la franquicia y paga sus 3 EUR. Si el
     * envío entrase en el valor declarado, este pedido se saldría del régimen y se cobraría de más.
     */
    @Test
    @DisplayName("el valor intrínseco excluye el transporte: el envío no empuja el pedido fuera de la franquicia")
    void elValorIntrinsecoExcluyeElTransporte() {
        List<Line> pedido = List.of(productoConPartida(HS_VAQUEROS, 1, FRANQUICIA));
        List<DutyParcel> bultos = dutyLines.parcelsOf(pedido);

        CheckoutTotals totales = checkoutTotals.compute(DESTINO_UE, null, FRANQUICIA, 5_000, bultos);

        assertThat(totales.customs().declaredValueCents()).isEqualTo(FRANQUICIA);
        assertThat(totales.customs().deMinimisExceeded()).isFalse();
        assertThat(totales.customsHandlingCents()).isEqualTo(DERECHO_POR_LINEA);
    }

    /* ==================== Alcance de la norma ==================== */

    /**
     * <b>Regla:</b> la norma NO excluye alcohol, perfumes ni tabaco — al contrario, los incorpora.
     *
     * <p>Es el matiz que más fácilmente se implementaría al revés, porque el antiguo artículo 24 del
     * Reglamento (CE) n.º 1186/2009 <i>excluía</i> esas categorías de la franquicia. La [GUÍA] §3.1, pág. 6,
     * lo invierte de forma expresa: «<i>The EUR 3 customs duty <b>applies to</b> the goods indicated in the
     * former Article 24 DRR: alcoholic products, perfumes and toilet waters and tobacco or tobacco
     * products</i>».
     *
     * <p>Por eso el código no tiene —ni debe tener— lista de categorías excluidas: perfume, licor y tabaco
     * cuentan como tres líneas normales y corrientes.
     */
    @Test
    @DisplayName("alcohol, perfume y tabaco no están excluidos: cuentan como cualquier otra línea")
    void alcoholPerfumeYTabacoNoEstanExcluidos() {
        List<Line> pedido = List.of(
                productoConPartida(HS_PERFUME, 1, 2_000),
                productoConPartida(HS_LICOR, 1, 2_000),
                productoConPartida(HS_TABACO, 1, 2_000));

        assertThat(derechoDe(DESTINO_UE, pedido)).isEqualTo(3 * DERECHO_POR_LINEA);
    }

    /**
     * <b>Regla:</b> fuera del territorio aduanero de la Unión no hay derecho temporal.
     *
     * <p>[REG] art. 2 lo circunscribe a las mercancías importadas en la Unión, y la [GUÍA] §3.2.1, pág. 7,
     * exige que la venta a distancia tenga por destino «<i>a customer in the Union customs territory</i>».
     * Un envío a Estados Unidos se rige por su propio régimen de importación.
     *
     * <p>En el modelo esto no se codifica con una lista de países de la UE, sino con el dato de la regla del
     * destino: {@code per_article_fee_amount = 0} significa «este país no tiene derecho por línea». Cambiar
     * el alcance de la norma es un UPDATE, no un despliegue.
     */
    @Test
    @DisplayName("un destino fuera de la UE no paga el derecho temporal aunque lleve varias líneas")
    void unDestinoFueraDeLaUeNoPagaElDerechoTemporal() {
        List<Line> pedido = List.of(
                productoConPartida(HS_VAQUEROS, 1, 1_000),
                productoConPartida(HS_CAMISETAS, 1, 1_000),
                productoConPartida(HS_ZAPATOS, 1, 1_000));

        assertThat(derechoDe(DESTINO_NO_UE, pedido)).isZero();
        assertThat(derechoDe(DESTINO_UE, pedido)).isEqualTo(3 * DERECHO_POR_LINEA);
    }

    /* ==================== Envío repartido en varios bultos ==================== */

    /**
     * <b>Regla:</b> cada envío lleva su propia declaración, así que cada bulto cuenta y paga SUS líneas.
     *
     * <p>[GUÍA] §3.3.1, pág. 11, sobre la definición de «consignment»: «<i>goods dispatched by the same
     * consignor to the same consignee that were ordered and shipped separately, even if arriving on the same
     * day but as separate parcels, should be considered as <b>separate consignments</b></i>». Y el TJUE, en
     * el asunto <b>C-7/08 <i>Har Vaessen Douane Service</i></b> (citado en la [GUÍA] §3.2.3, pág. 9),
     * sostiene que en envíos agrupados «<i>each consignment is to be considered separately</i>».
     *
     * <p>Consecuencia práctica: cuando el tope del canal de transporte obliga a partir la mercancía, la misma
     * subpartida que viaja en dos paquetes se declara dos veces y paga 3 EUR en cada uno. No es un cargo
     * duplicado por error: son dos declaraciones distintas.
     */
    @Test
    @DisplayName("cuando el envío se parte en varios bultos, cada bulto declara y paga sus propias líneas")
    void cadaBultoDeclaraYPagaSusPropiasLineas() {
        List<Line> pedido = List.of(productoConPartida(HS_VAQUEROS, 2, 1_000));

        // En un solo bulto: una línea, un derecho.
        assertThat(derechoDe(DESTINO_UE, pedido)).isEqualTo(DERECHO_POR_LINEA);

        // El canal solo admite una unidad por bulto: dos declaraciones, cada una con su línea.
        limitarBultos(1, 0);
        List<DutyParcel> bultos = dutyLines.parcelsOf(pedido);
        assertThat(bultos).hasSize(2);
        assertThat(bultos).allSatisfy(bulto -> assertThat(bulto.tariffLines()).isEqualTo(1));
        assertThat(derechoDe(DESTINO_UE, pedido)).isEqualTo(2 * DERECHO_POR_LINEA);
    }

    /**
     * <b>Regla:</b> el tope de valor del canal de transporte y la franquicia aduanera son cosas distintas y
     * no deben confundirse.
     *
     * <p>El canal BPA contratado admite hasta 155 USD por bulto; la franquicia aduanera son 150 EUR de valor
     * intrínseco. Son magnitudes independientes: la primera es un límite comercial del transportista, la
     * segunda un umbral legal. Este test fija el comportamiento en el caso en que el tope del canal NO llega
     * a partir el pedido, que es donde ambos criterios coinciden y el resultado es indiscutible: un pedido de
     * 140,00 EUR cabe en un bulto y está por debajo de la franquicia → paga sus líneas.
     *
     * <p>El caso en que el tope del canal SÍ parte un pedido que supera la franquicia está documentado —y
     * cuestionado— en {@link #franquiciaMedidaPorBultoEnLugarDePorEnvio()}.
     */
    @Test
    @DisplayName("el tope del canal y la franquicia aduanera son umbrales distintos que no se confunden")
    void elTopeDelCanalYLaFranquiciaSonUmbralesDistintos() {
        limitarBultos(0, 15_500);
        List<Line> pedido = List.of(
                productoConPartida(HS_VAQUEROS, 1, 7_000),
                productoConPartida(HS_CAMISETAS, 1, 7_000));

        List<DutyParcel> bultos = dutyLines.parcelsOf(pedido);
        assertThat(bultos).hasSize(1);

        CustomsValuation resultado = valorar(DESTINO_UE, pedido);
        assertThat(resultado.deMinimisExceeded()).isFalse();
        assertThat(resultado.handlingFeeCents()).isEqualTo(2 * DERECHO_POR_LINEA);
    }

    /* ==================== Discrepancia documentada ==================== */

    /**
     * <b>DISCREPANCIA ENTRE LA NORMA Y EL CÓDIGO — no arreglar sin decidir antes.</b>
     *
     * <p><b>Qué dice la norma.</b> La franquicia de 150 EUR se mide <b>por envío</b> («<i>per
     * consignment</i>», [GUÍA] §2.1, pág. 5), y un «consignment» es, según el art. 5(35) del código aduanero
     * reformado recogido en la [GUÍA] §3.3.1, pág. 11, las mercancías «<i>conveyed by one consignor to one
     * consignee, by the same means of transport …, <b>under the same transport contract</b></i>». Varios
     * bultos que viajan bajo un mismo contrato de transporte son, por tanto, UN envío, y su valor se suma.
     * La [GUÍA] solo los considera envíos separados cuando fueron «<i>ordered and shipped separately</i>» o
     * «<i>dispatched separately</i>» — no cuando es el transportista quien parte una única expedición.
     * Además, la cláusula antiabuso del art. 243(5) UCC-IA ([GUÍA] §3.2.3, pág. 9) existe justamente para que
     * la aduana recomponga las expediciones fraccionadas.
     *
     * <p><b>Qué hace el código.</b> {@code CustomsValuationService.valuate} evalúa la franquicia
     * <b>bulto a bulto</b> ({@code bultos.stream().anyMatch(b -> exceedsDeMinimis(r, b.valueCents()))}).
     * Un pedido de 300,00 EUR que el tope de valor del canal parte en dos bultos de 150,00 se considera
     * DENTRO de la franquicia en ambas declaraciones: se cobran 2 × 3 EUR de derecho fijo y
     * {@code deMinimisExceeded} devuelve false. Bajo la lectura por envío, ese pedido supera los 150 EUR y no
     * le corresponde el importe fijo, sino el arancel normal del TARIC más el despacho formal.
     *
     * <p><b>Señal interna de que algo no cuadra.</b> El javadoc de {@code ParcelSplitter} afirma lo
     * contrario de lo que hace hoy la valoración: «<i>Nunca se parte para que cada envío quede por debajo del
     * umbral de minimis del destino: eso es fraccionamiento artificial, está prohibido en la UE … El umbral
     * se sigue evaluando sobre el valor del pedido completo</i>». Los dos ficheros ya no dicen lo mismo.
     *
     * <p><b>Qué hay que decidir.</b> Si los bultos de un pedido viajan bajo un único contrato de transporte
     * —que es lo habitual al crear varias guías de una misma expedición—, la franquicia debería medirse sobre
     * el valor del pedido y este test debería pasar. Si el transportista los tramita como expediciones
     * independientes, la lectura actual es defendible y lo que hay que corregir es el javadoc de
     * {@code ParcelSplitter}. Es una pregunta para YunExpress, no para el código: el riesgo de equivocarse es
     * una liquidación por debajo de lo debido, con la deuda aduanera a cargo del declarante ([GUÍA] §3.4.5).
     */
    @Test
    @Disabled("DISCREPANCIA: la franquicia se mide por bulto y la norma la mide por envío (mismo contrato de "
            + "transporte). Verificado el 14-ago-2026 al retirar este @Disabled: deMinimisExceeded devuelve "
            + "false para un pedido de 300 EUR partido en dos bultos de 150. Pendiente de confirmar con el "
            + "transportista si cada bulto es una expedición independiente; ver el javadoc del test.")
    @DisplayName("la franquicia debería medirse sobre el envío completo, no bulto a bulto")
    void franquiciaMedidaPorBultoEnLugarDePorEnvio() {
        // El tope de valor del canal (155 USD) parte el pedido en dos bultos de 150,00 EUR.
        limitarBultos(0, 15_500);
        List<Line> pedido = List.of(
                productoConPartida(HS_VAQUEROS, 1, FRANQUICIA),
                productoConPartida(HS_CAMISETAS, 1, FRANQUICIA));

        List<DutyParcel> bultos = dutyLines.parcelsOf(pedido);
        assertThat(bultos).hasSize(2);

        CustomsValuation resultado = valorar(DESTINO_UE, pedido);

        // El pedido vale 300,00 EUR: como envío único supera la franquicia de 150,00.
        assertThat(valorIntrinseco(pedido)).isEqualTo(2 * FRANQUICIA);
        assertThat(resultado.deMinimisExceeded()).isTrue();
        // Y por encima de la franquicia no corresponde el importe fijo de 3 EUR por línea.
        assertThat(resultado.handlingFeeCents()).isZero();
    }

    /**
     * <b>Regla:</b> sin mercancía no hay envío, no hay declaración y no hay derecho que cobrar.
     *
     * <p>Complemento defensivo de [REG] art. 2: el derecho se devenga «<i>per item in a consignment</i>», y
     * sin envío no hay ni una cosa ni la otra.
     */
    @Test
    @DisplayName("sin líneas no hay bultos que declarar ni derecho que cobrar")
    void sinLineasNoHayDerechoQueCobrar() {
        assertThat(dutyLines.parcelsOf(List.of())).isEmpty();
        assertThat(dutyLines.parcelsOf(null)).isEmpty();
        assertThat(valuation.valuate(DESTINO_UE, 0, 0, new ArrayList<>()).handlingFeeCents()).isZero();
    }
}
