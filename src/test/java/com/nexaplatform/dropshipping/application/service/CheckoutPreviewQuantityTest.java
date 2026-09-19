package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Tope de unidades por línea en la previsualización del checkout.
 *
 * <p>La cota existe porque el pipeline de importes es {@code int}: sin acotar, una cantidad enorme
 * desbordaba y la base del impuesto salía negativa —el preview enseñaba 0 de IVA y el cobro real no—.
 *
 * <p>Al pasar {@code Math.min(Math.max(1, qty), MAX)} a {@code Math.clamp} se colaron los argumentos en
 * el orden equivocado ({@code clamp(1, qty, MAX)}), y con eso una cantidad por encima del tope dejaba
 * {@code min > max}: en vez de acotarse, reventaba con {@code IllegalArgumentException} en mitad del
 * checkout. Estos tests fijan las tres fronteras.
 *
 * <p>Y, ya que la cantidad es lo que multiplica, aquí se fija también CUÁNDO se redondea el importe de
 * la línea: una sola vez, al final, y no una vez por unidad. Los importes están calculados a mano con el
 * euro a 0,92 y se comparan al céntimo.
 */
class CheckoutPreviewQuantityTest {

    private static final int MAX_LINE_QUANTITY = 100_000;

    /** Euro por dólar. Es la tasa con la que están calculados a mano los importes de esta clase. */
    private static final BigDecimal EUR = new BigDecimal("0.92");

    private final ShippingQuoteService shipping = mock(ShippingQuoteService.class, RETURNS_DEEP_STUBS);
    private final CheckoutTotalsService totals = mock(CheckoutTotalsService.class, RETURNS_DEEP_STUBS);
    private final PricingService pricing = mock(PricingService.class, RETURNS_DEEP_STUBS);
    private final CurrencyRateService currency = mock(CurrencyRateService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final AffiliateProgramService affiliate = mock(AffiliateProgramService.class);

    /** Sin promociones: esta prueba mide el tope de cantidad por línea, no las rebajas. */
    private final PromotionService promociones = mock(PromotionService.class);

    private final com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService dutyLines =
            new com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService(null);

    /** La cuenta real, no un doble: es justo la aritmética de línea que estos casos miden. */
    private final OrderAmounts orderAmounts = new OrderAmounts(currency);
    /**
     * La agrupación arancelaria no es lo que miden estas pruebas: el doble devuelve el título de
     * siempre, que es como se declaraba antes de que existieran los grupos.
     */
    private final CustomsDeclarationGroupService declarationGroups = mock(CustomsDeclarationGroupService.class);


    private final CheckoutPreviewService service = new CheckoutPreviewService(shipping, totals, subvenciones(), pricing,
            currency, products, dutyLines, affiliate, promociones, orderAmounts, declarationGroups);

    private final UUID productId = UUID.randomUUID();

    private void stubUnitPriceOf(String retailUsd) {
        ProductEntity product = mock(ProductEntity.class, RETURNS_DEEP_STUBS);
        lenient().when(products.findById(productId)).thenReturn(Optional.of(product));
        lenient().when(pricing.priceFor(any(), any()).retailUsd()).thenReturn(new BigDecimal(retailUsd));
        lenient().when(pricing.priceFor(any(), any()).displayAmount()).thenReturn(new BigDecimal(retailUsd));
        lenient().when(currency.usdToDisplay(any(BigDecimal.class))).thenReturn(new BigDecimal(retailUsd));
        lenient().when(affiliate.referralDiscountCents(any(), anyLong())).thenReturn(0L);
        lenient().when(currency.usdTo(any(BigDecimal.class), anyString())).thenReturn(BigDecimal.ZERO);
        lenient().when(currency.decimalsOf(anyString())).thenReturn(2);
    }

    @Test
    void unaCantidadPorEncimaDelTopeSeAcotaEnLugarDeReventar() {
        stubUnitPriceOf("1.00");

        CheckoutPreviewService.Preview preview = service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, MAX_LINE_QUANTITY + 5_000)), null);

        // 1,00 $ × 100.000 = 10.000.000 céntimos. Si no se acotara, serían 105.000 unidades.
        assertThat(preview.subtotalUsdCents()).isEqualTo(MAX_LINE_QUANTITY * 100);
    }

    @Test
    void unaCantidadDesmesuradaNoLanzaExcepcion() {
        stubUnitPriceOf("1.00");

        assertThatCode(() -> service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, Integer.MAX_VALUE)), null))
                .doesNotThrowAnyException();
    }

    @Test
    void unaCantidadCeroONegativaCuentaComoUnaUnidad() {
        stubUnitPriceOf("7.50");

        assertThat(service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, 0)), null).subtotalUsdCents())
                .isEqualTo(750);
        assertThat(service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, -3)), null).subtotalUsdCents())
                .isEqualTo(750);
    }

    @Test
    void unaCantidadNormalNoSeToca() {
        stubUnitPriceOf("2.50");

        assertThat(service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, 4)), null).subtotalUsdCents())
                .isEqualTo(1000);
    }

    /**
     * Deja el producto tarificado a {@code retailUsd} dólares y el euro a 0,92, con la conversión de
     * verdad (multiplicar por la tasa y redondear al céntimo) en lugar de una constante: lo que estos
     * casos miden es precisamente CUÁNDO se redondea.
     */
    private void stubProductoEnEuros(String retailUsd) {
        ProductEntity product = mock(ProductEntity.class, RETURNS_DEEP_STUBS);
        lenient().when(products.findById(productId)).thenReturn(Optional.of(product));
        lenient().when(pricing.priceFor(any(), any()).retailUsd()).thenReturn(new BigDecimal(retailUsd));
        lenient().when(affiliate.referralDiscountCents(any(), anyLong())).thenReturn(0L);
        lenient().when(currency.decimalsOf(anyString())).thenReturn(2);
        lenient().when(currency.usdTo(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.<BigDecimal>getArgument(0).multiply(EUR).setScale(2, RoundingMode.HALF_UP));
        lenient().when(currency.usdToDisplay(any(BigDecimal.class)))
                .thenAnswer(i -> i.<BigDecimal>getArgument(0).multiply(EUR).setScale(2, RoundingMode.HALF_UP));
        // La ficha enseña el unitario ya convertido y redondeado: es el precio que el cliente eligió.
        lenient().when(pricing.priceFor(any(), any()).displayAmount())
                .thenReturn(new BigDecimal(retailUsd).multiply(EUR).setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void elImporteDeLineaSeRedondeaUnaSolaVezYNoUnaVezPorUnidad() {
        // El caso que destapó el cobro de más: 0,15 $ al cambio 0,92 son 0,138 € la unidad, que en
        // pantalla es 0,14 €. Multiplicando ESE número por 100 salían 14,00 €, cuando 100 unidades son
        // 15,00 $ = 13,80 €. Veinte céntimos de más (+1,45 %) que la pasarela liquidaba de verdad.
        stubProductoEnEuros("0.15");

        CheckoutPreviewService.Preview preview = service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, 100)), null);

        assertThat(preview.subtotalDisplay()).isEqualByComparingTo("13.80");
        assertThat(preview.subtotalDisplay()).as("0,14 € × 100 = 14,00 € era el cobro inflado")
                .isNotEqualByComparingTo("14.00");
        assertThat(preview.subtotalUsdCents()).as("en dólares el subtotal no cambia: 0,15 × 100").isEqualTo(1500);
    }

    @Test
    void conUnaUnidadElImporteDeLineaEsExactamenteElUnitario() {
        // La corrección no puede mover el precio de una unidad suelta: ahí no hay nada que multiplicar.
        stubProductoEnEuros("0.15");

        CheckoutPreviewService.Preview preview = service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, 1)), null);

        // 0,15 × 0,92 = 0,138 → 0,14 €, el mismo número que enseña la ficha.
        assertThat(preview.subtotalDisplay()).isEqualByComparingTo("0.14");
        assertThat(preview.lines()).singleElement()
                .satisfies(l -> assertThat(l.lineSubtotalDisplay()).isEqualByComparingTo(l.unitDisplay()));
    }

    @Test
    void elResumenPublicaElImporteDeCadaLineaParaQueLaSumaCuadre() {
        // Contrapartida obligatoria: si el total deja de ser «unitario × cantidad», el cliente tiene que
        // ver de dónde sale. La línea viaja con las dos cifras: 0,14 € /ud y 13,80 € de importe.
        stubProductoEnEuros("0.15");

        CheckoutPreviewService.Preview preview = service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, 100)), null);

        assertThat(preview.lines()).singleElement().satisfies(l -> {
            assertThat(l.quantity()).isEqualTo(100);
            assertThat(l.unitDisplay()).isEqualByComparingTo("0.14");
            assertThat(l.lineSubtotalDisplay()).isEqualByComparingTo("13.80");
        });
        assertThat(preview.lines().stream().map(CheckoutPreviewService.PreviewLine::lineSubtotalDisplay)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("sumando los importes que se enseñan se llega al subtotal")
                .isEqualByComparingTo(preview.subtotalDisplay());
    }

    /**
     * La bolsa de subvención del envío, real y con su suelo puesto.
     *
     * <p>Real y no simulada a propósito: estas pruebas miden importes, y un doble que devolviera cero
     * escondería justo el descuento que hoy forma parte del desglose.
     */
    private static com.nexaplatform.dropshipping.application.service.ProductSubsidyService subvenciones() {
        com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService divisa =
                org.mockito.Mockito.mock(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService.class);
        org.mockito.Mockito.lenient().when(divisa.toUsd(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(new java.math.BigDecimal("5.85"));
        return new com.nexaplatform.dropshipping.application.service.ProductSubsidyService(divisa);
    }
}
