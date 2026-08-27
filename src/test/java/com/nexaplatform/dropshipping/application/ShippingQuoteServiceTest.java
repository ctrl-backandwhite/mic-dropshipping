package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.ShippingQuoteService;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.application.service.FulfillmentRouter;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.ParcelSpec;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Cómo se pesa el carrito antes de pedir tarifa.
 *
 * <p>Lo que se comprueba aquí es el peso que sale del carrito y las salvaguardas de las líneas raras
 * —cantidad cero, producto que ya no está, lista vacía—, no la tarifa: esa la ponen los transportistas.
 *
 * <p>Desde el 18-ago-2026 la cotización no va a un transportista fijo sino a {@link FulfillmentRouter},
 * que pregunta a todos los que puedan llevar ese pedido. Por eso el doble que recibe el bulto pesado es
 * el enrutador y no el proveedor: el proveedor se queda solo para la lista de países de la portada.
 */
@ExtendWith(MockitoExtension.class)
class ShippingQuoteServiceTest {

    @Mock
    ProductRepository productRepository;
    @Mock
    FulfillmentProvider cainiao;
    @Mock
    FulfillmentRouter router;
    @InjectMocks
    ShippingQuoteService service;

    @Captor
    ArgumentCaptor<ParcelSpec> parcelCaptor;

    private static ProductEntity product(Integer packageWeight, Integer weight) {
        return ProductEntity.builder().packageWeightGrams(packageWeight).weightGrams(weight).build();
    }

    private static ShippingQuote okQuote() {
        return new ShippingQuote(true, "ES", 999, "Standard Shipping", "Standard Shipping", 7, 15, "EU");
    }

    @Test
    void quote_usesPackageWeightTimesQuantity() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(product(300, 100)));
        when(router.cotizar(eq("ES"), parcelCaptor.capture(), anyList())).thenReturn(okQuote());

        ShippingQuote result = service.quote("ES", List.of(new ShippingQuoteService.Line(id, null, 2)));

        // packageWeightGrams (300) tiene prioridad sobre weightGrams; 300 * 2 = 600.
        assertThat(parcelCaptor.getValue().weightGrams()).isEqualTo(600);
        assertThat(result.supported()).isTrue();
    }

    @Test
    void quote_fallsBackToWeightGramsWhenPackageWeightMissing() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(product(null, 250)));
        when(router.cotizar(eq("ES"), parcelCaptor.capture(), anyList())).thenReturn(okQuote());

        service.quote("ES", List.of(new ShippingQuoteService.Line(id, null, 1)));

        assertThat(parcelCaptor.getValue().weightGrams()).isEqualTo(250);
    }

    @Test
    void quote_defaultsTo500WhenProductHasNoWeights() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(product(null, 0)));
        when(router.cotizar(eq("ES"), parcelCaptor.capture(), anyList())).thenReturn(okQuote());

        service.quote("ES", List.of(new ShippingQuoteService.Line(id, null, 1)));

        assertThat(parcelCaptor.getValue().weightGrams()).isEqualTo(500);
    }

    @Test
    void quote_defaultsTo500WhenProductNotFound() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());
        when(router.cotizar(eq("ES"), parcelCaptor.capture(), anyList())).thenReturn(okQuote());

        service.quote("ES", List.of(new ShippingQuoteService.Line(id, null, 1)));

        assertThat(parcelCaptor.getValue().weightGrams()).isEqualTo(500);
    }

    @Test
    void quote_treatsZeroOrNegativeQuantityAsOne() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(product(200, null)));
        when(router.cotizar(eq("ES"), parcelCaptor.capture(), anyList())).thenReturn(okQuote());

        service.quote("ES", List.of(new ShippingQuoteService.Line(id, null, 0)));

        // Math.max(1, quantity) => 200 * 1.
        assertThat(parcelCaptor.getValue().weightGrams()).isEqualTo(200);
    }

    @Test
    void quote_sumsAcrossMultipleLines() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(productRepository.findById(a)).thenReturn(Optional.of(product(100, null)));
        when(productRepository.findById(b)).thenReturn(Optional.of(product(400, null)));
        when(router.cotizar(eq("ES"), parcelCaptor.capture(), anyList())).thenReturn(okQuote());

        service.quote("ES", List.of(new ShippingQuoteService.Line(a, null, 3), new ShippingQuoteService.Line(b, null, 1)));

        // 100*3 + 400*1 = 700.
        assertThat(parcelCaptor.getValue().weightGrams()).isEqualTo(700);
    }

    @Test
    void quote_nullLinesProducesMinimumWeightOfOne() {
        when(router.cotizar(eq("ES"), parcelCaptor.capture(), anyList())).thenReturn(okQuote());

        service.quote("ES", null);

        // Sin líneas el peso es 0, pero el guard Math.max(1, weight) lo eleva a 1; el repo no se toca.
        assertThat(parcelCaptor.getValue().weightGrams()).isEqualTo(1);
        verifyNoInteractions(productRepository);
    }

    @Test
    void quote_emptyLinesProducesMinimumWeightOfOne() {
        when(router.cotizar(eq("ES"), parcelCaptor.capture(), anyList())).thenReturn(okQuote());

        service.quote("ES", List.of());

        assertThat(parcelCaptor.getValue().weightGrams()).isEqualTo(1);
    }

    /** Un destino que ningún transportista cubre se devuelve tal cual, sin maquillarlo. */
    @Test
    void quote_devuelveSinCoberturaTalCual() {
        ShippingQuote sinCobertura = ShippingQuote.unsupported("XX");
        when(router.cotizar(eq("XX"), any(ParcelSpec.class), anyList())).thenReturn(sinCobertura);

        ShippingQuote result = service.quote("XX", List.of());

        assertThat(result).isSameAs(sinCobertura);
        assertThat(result.supported()).isFalse();
    }

    @Test
    void supportedCountries_delegaEnElProveedor() {
        FulfillmentProvider.SupportedCountry es =
                new FulfillmentProvider.SupportedCountry("ES", "Spain");
        when(cainiao.supportedCountries()).thenReturn(List.of(es));

        List<FulfillmentProvider.SupportedCountry> result = service.supportedCountries();

        assertThat(result).containsExactly(es);
    }
}
