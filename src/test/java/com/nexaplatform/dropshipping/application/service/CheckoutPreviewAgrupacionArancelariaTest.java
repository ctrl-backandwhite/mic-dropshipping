package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CheckoutPreviewService.Line;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La vista previa del checkout tiene que contar el arancel con la descripción con la que se va a
 * declarar de verdad, no con el título del producto.
 *
 * <p>Es el primero de los tres sitios que TIENEN que coincidir —vista previa, pedido y despacho—. Si
 * uno clasifica distinto que otro, el cliente ve un importe y se le cobra otro; ese descuadre ya ocurrió
 * una vez y por eso el conteo se hace por la terna completa.
 */
class CheckoutPreviewAgrupacionArancelariaTest {

    private final ShippingQuoteService shipping = mock(ShippingQuoteService.class, RETURNS_DEEP_STUBS);
    private final CheckoutTotalsService totals = mock(CheckoutTotalsService.class, RETURNS_DEEP_STUBS);
    private final PricingService pricing = mock(PricingService.class, RETURNS_DEEP_STUBS);
    private final CurrencyRateService currency = mock(CurrencyRateService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final AffiliateProgramService affiliate = mock(AffiliateProgramService.class);
    private final PromotionService promociones = mock(PromotionService.class);
    private final CustomsDutyLinesService dutyLines = new CustomsDutyLinesService(null);
    private final OrderAmounts orderAmounts = new OrderAmounts(currency);
    private final CustomsDeclarationGroupService declarationGroups = mock(CustomsDeclarationGroupService.class);

    /** Sin escalera de cantidades: estas pruebas miden otra cosa y un tramo la falsearía. */
    private final ProductPriceTierRepository tramos = mock(ProductPriceTierRepository.class);

    private final CheckoutPreviewService service = new CheckoutPreviewService(shipping, totals, subvenciones(), pricing,
            currency, products, tramos, dutyLines, affiliate, promociones, orderAmounts, declarationGroups);

    private final UUID zapatillas = UUID.randomUUID();
    private final UUID boxers = UUID.randomUUID();

    @Test
    void dosProductosDelMismoGrupoSeDeclaranIgualYPaganUnSoloDerecho() {
        // 3 EUR por LÍNEA de declaración: dos productos que viajan con la misma descripción son UNA
        // línea. Es lo que convierte los 5,99 EUR de un carrito de dos artículos en 3,00 EUR.
        catalogar(zapatillas, "640520", "EVA slippers");
        catalogar(boxers, "620343", "Cotton boxers");
        when(declarationGroups.describeFor(any(), eq("ES"))).thenReturn("Men's knitted cotton garment");

        List<CustomsDutyLinesService.Line> lineas = service.customsLines(carrito(), "ES");

        assertThat(lineas).hasSize(2).extracting(CustomsDutyLinesService.Line::description)
                .containsOnly("Men's knitted cotton garment");
    }

    @Test
    void sinGrupoAprobadoCadaProductoConservaSuDescripcion() {
        catalogar(zapatillas, "640520", "EVA slippers");
        catalogar(boxers, "620343", "Cotton boxers");
        when(declarationGroups.describeFor(any(), eq("ES")))
                .thenAnswer(inv -> CustomsDutyLinesService.declaredDescriptionOf(inv.getArgument(0)));

        List<CustomsDutyLinesService.Line> lineas = service.customsLines(carrito(), "ES");

        assertThat(lineas).extracting(CustomsDutyLinesService.Line::description)
                .containsExactlyInAnyOrder("EVA slippers", "Cotton boxers");
    }

    @Test
    void elPaisDeDESTINOLlegaAlaResolucion() {
        // Sin el país no se pueden aplicar los apagados: un destino sacado de la agrupación seguiría
        // agrupando y cobraríamos de menos.
        catalogar(zapatillas, "640520", "EVA slippers");
        when(declarationGroups.describeFor(any(), eq("FR"))).thenReturn("EVA slippers");

        service.customsLines(List.of(new Line(zapatillas, null, 1)), "FR");

        org.mockito.Mockito.verify(declarationGroups).describeFor(any(), eq("FR"));
    }

    private List<Line> carrito() {
        return List.of(new Line(zapatillas, null, 1), new Line(boxers, null, 1));
    }

    private void catalogar(UUID id, String hs, String tituloEn) {
        ProductEntity p = new ProductEntity();
        p.setId(id);
        p.setHsCode(hs);
        p.setCountryOfOrigin("CN");
        ProductTranslationEntity en = new ProductTranslationEntity();
        en.setLanguage("en");
        en.setTitle(tituloEn);
        p.setTranslations(List.of(en));
        lenient().when(products.findById(id)).thenReturn(Optional.of(p));
        lenient().when(pricing.priceFor(any(), any()).retailUsd()).thenReturn(java.math.BigDecimal.ONE);
    }

    /**
     * La bolsa de subvención del envío, real y con su suelo puesto.
     *
     * <p>Real y no simulada a propósito: estas pruebas miden importes, y un doble que devolviera cero
     * escondería justo el descuento que hoy forma parte del desglose.
     */
    private static com.nexaplatform.dropshipping.application.service.ProductSubsidyService subvenciones() {
        com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService divisa = org.mockito.Mockito
                .mock(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService.class);
        org.mockito.Mockito.lenient()
                .when(divisa.toUsd(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new java.math.BigDecimal("5.85"));
        return new com.nexaplatform.dropshipping.application.service.ProductSubsidyService(divisa);
    }
}
