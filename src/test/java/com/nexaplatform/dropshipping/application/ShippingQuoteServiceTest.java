package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.ShippingQuoteService;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoFulfillmentService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShippingQuoteService}. Mockito drives the legacy product
 * Spring Data repository (weight resolution) and the Cainiao fulfillment service
 * (final rate by destination). Focus: cart weight aggregation and the guards.
 */
@ExtendWith(MockitoExtension.class)
class ShippingQuoteServiceTest {

    @Mock
    ProductRepository productRepository;
    @Mock
    CainiaoFulfillmentService cainiao;
    @InjectMocks
    ShippingQuoteService service;

    @Captor
    ArgumentCaptor<Integer> weightCaptor;

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
        when(cainiao.quote(eq("ES"), weightCaptor.capture())).thenReturn(okQuote());

        ShippingQuote result = service.quote("ES", List.of(new ShippingQuoteService.Line(id, null, 2)));

        // packageWeightGrams (300) tiene prioridad sobre weightGrams; 300 * 2 = 600.
        assertThat(weightCaptor.getValue()).isEqualTo(600);
        assertThat(result.supported()).isTrue();
    }

    @Test
    void quote_fallsBackToWeightGramsWhenPackageWeightMissing() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(product(null, 250)));
        when(cainiao.quote(eq("ES"), weightCaptor.capture())).thenReturn(okQuote());

        service.quote("ES", List.of(new ShippingQuoteService.Line(id, null, 1)));

        assertThat(weightCaptor.getValue()).isEqualTo(250);
    }

    @Test
    void quote_defaultsTo500WhenProductHasNoWeights() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(product(null, 0)));
        when(cainiao.quote(eq("ES"), weightCaptor.capture())).thenReturn(okQuote());

        service.quote("ES", List.of(new ShippingQuoteService.Line(id, null, 1)));

        assertThat(weightCaptor.getValue()).isEqualTo(500);
    }

    @Test
    void quote_defaultsTo500WhenProductNotFound() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());
        when(cainiao.quote(eq("ES"), weightCaptor.capture())).thenReturn(okQuote());

        service.quote("ES", List.of(new ShippingQuoteService.Line(id, null, 1)));

        assertThat(weightCaptor.getValue()).isEqualTo(500);
    }

    @Test
    void quote_treatsZeroOrNegativeQuantityAsOne() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(product(200, null)));
        when(cainiao.quote(eq("ES"), weightCaptor.capture())).thenReturn(okQuote());

        service.quote("ES", List.of(new ShippingQuoteService.Line(id, null, 0)));

        // Math.max(1, quantity) => 200 * 1.
        assertThat(weightCaptor.getValue()).isEqualTo(200);
    }

    @Test
    void quote_sumsAcrossMultipleLines() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(productRepository.findById(a)).thenReturn(Optional.of(product(100, null)));
        when(productRepository.findById(b)).thenReturn(Optional.of(product(400, null)));
        when(cainiao.quote(eq("ES"), weightCaptor.capture())).thenReturn(okQuote());

        service.quote("ES", List.of(new ShippingQuoteService.Line(a, null, 3), new ShippingQuoteService.Line(b, null, 1)));

        // 100*3 + 400*1 = 700.
        assertThat(weightCaptor.getValue()).isEqualTo(700);
    }

    @Test
    void quote_nullLinesProducesMinimumWeightOfOne() {
        when(cainiao.quote(eq("ES"), weightCaptor.capture())).thenReturn(okQuote());

        service.quote("ES", null);

        // Sin líneas el peso es 0, pero el guard Math.max(1, weight) lo eleva a 1; el repo no se toca.
        assertThat(weightCaptor.getValue()).isEqualTo(1);
        verifyNoInteractions(productRepository);
    }

    @Test
    void quote_emptyLinesProducesMinimumWeightOfOne() {
        when(cainiao.quote(eq("ES"), weightCaptor.capture())).thenReturn(okQuote());

        service.quote("ES", List.of());

        assertThat(weightCaptor.getValue()).isEqualTo(1);
    }

    @Test
    void quote_returnsUnsupportedFromCainiaoUnchanged() {
        ShippingQuote unsupported = ShippingQuote.unsupported("XX");
        when(cainiao.quote(eq("XX"), eq(1))).thenReturn(unsupported);

        ShippingQuote result = service.quote("XX", List.of());

        assertThat(result).isSameAs(unsupported);
        assertThat(result.supported()).isFalse();
    }

    @Test
    void supportedCountries_delegatesToCainiao() {
        CainiaoFulfillmentService.SupportedCountry es =
                new CainiaoFulfillmentService.SupportedCountry("ES", "Spain");
        when(cainiao.supportedCountries()).thenReturn(List.of(es));

        List<CainiaoFulfillmentService.SupportedCountry> result = service.supportedCountries();

        assertThat(result).containsExactly(es);
    }
}
