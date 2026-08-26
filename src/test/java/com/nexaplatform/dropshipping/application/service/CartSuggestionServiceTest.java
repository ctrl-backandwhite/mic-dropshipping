package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.api.mapper.ProductListFilters;
import com.nexaplatform.dropshipping.application.service.CartSuggestionService.Linea;
import com.nexaplatform.dropshipping.application.service.CartSuggestionService.Sugerencia;
import com.nexaplatform.dropshipping.application.service.CatalogDutyBadgeService.DutyBadge;
import com.nexaplatform.dropshipping.application.service.CatalogDutyBadgeService.LineaDeclarada;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El motor que decide qué merece la pena añadir. Se prueba con cifras porque son cifras lo que se
 * le promete a quien está a punto de pagar.
 */
class CartSuggestionServiceTest {

    private CatalogDutyBadgeService dutyBadges;
    private CatalogStorefrontReadService storefrontRead;
    private CustomsValuationService customsValuation;
    private ShippingQuoteService shippingQuotes;
    private CurrencyRateService currencyService;
    private CartSuggestionService service;

    private final UUID enCarrito = UUID.randomUUID();
    private final UUID grupo = UUID.randomUUID();
    private final UUID candidatoGratis = UUID.randomUUID();
    private final UUID candidatoCaro = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dutyBadges = Mockito.mock(CatalogDutyBadgeService.class);
        storefrontRead = Mockito.mock(CatalogStorefrontReadService.class);
        customsValuation = Mockito.mock(CustomsValuationService.class);
        shippingQuotes = Mockito.mock(ShippingQuoteService.class);
        currencyService = Mockito.mock(CurrencyRateService.class);
        service = new CartSuggestionService(dutyBadges, storefrontRead, customsValuation, shippingQuotes,
                currencyService);

        // País de la Unión: cobra derecho por artículo, así que agrupar tiene sentido.
        Mockito.when(customsValuation.perArticleFeeUsdCents(Mockito.any())).thenReturn(330);
        Mockito.when(currencyService.usdToDisplay(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        Mockito.when(currencyService.formatDisplay(Mockito.any(), Mockito.any()))
                .thenAnswer(i -> i.getArgument(0).toString() + " €");
        Mockito.when(dutyBadges.lineasDe(Mockito.any())).thenReturn(List.of(new LineaDeclarada(grupo, "CN")));
    }

    private ProductSummaryView producto(UUID id, String slug, String titulo) {
        // Veinticuatro componentes, de los que a esta prueba solo le importan cuatro:
        // identificador, ruta, título e imagen. El resto va vacío a propósito.
        return new ProductSummaryView(id, slug, titulo, "http://img/" + slug, null, null, null, 0, null, null, null, null, null, null, null, null, null, false, null, null, null, null, null, null);
    }

    private void conCandidatos(ProductSummaryView... vistas) {
        Mockito.when(storefrontRead.productListFull(Mockito.anyInt(), Mockito.anyInt(), Mockito.any(),
                Mockito.any(ProductListFilters.class), Mockito.any()))
                .thenReturn(new PageResponse<>(List.of(vistas), 0, 12, vistas.length, 1));
    }

    @Test
    @DisplayName("Con el carrito vacío no se sugiere nada")
    void carritoVacio() {
        assertTrue(service.para(List.of(), "es").isEmpty());
        assertTrue(service.para(null, "es").isEmpty());
    }

    @Test
    @DisplayName("Donde no se cobra derecho por artículo, la promesa no significa nada y no se hace")
    void paisSinDerechoPorArticulo() {
        Mockito.when(customsValuation.perArticleFeeUsdCents(Mockito.any())).thenReturn(0);

        assertTrue(service.para(List.of(new Linea(enCarrito, null, 1)), "es").isEmpty());
        // Ni siquiera se molesta en buscar candidatos.
        Mockito.verifyNoInteractions(storefrontRead);
    }

    @Test
    @DisplayName("Solo se sugiere lo que NO suma arancel; lo que sí suma se descarta")
    void soloLoQueNoSumaArancel() {
        conCandidatos(producto(candidatoGratis, "calcetines", "Calcetines"),
                producto(candidatoCaro, "reloj", "Reloj"));
        Mockito.when(dutyBadges.badgesFor(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Map.of(
                candidatoGratis, new DutyBadge(0, "0,00 €", grupo),
                candidatoCaro, new DutyBadge(330, "3,00 €", UUID.randomUUID())));
        Mockito.when(shippingQuotes.quote(Mockito.any(), Mockito.any()))
                .thenReturn(new ShippingQuote(true, "ES", 500, "YunExpress", "BPA", 5, 9, "EU"))
                .thenReturn(new ShippingQuote(true, "ES", 540, "YunExpress", "BPA", 5, 9, "EU"));

        List<Sugerencia> salida = service.para(List.of(new Linea(enCarrito, null, 1)), "es");

        assertEquals(1, salida.size());
        assertEquals("calcetines", salida.get(0).slug());
        // Arancel extra cero, y el envío solo sube la diferencia: 5,40 - 5,00 = 0,40.
        assertEquals("0.00 €", salida.get(0).dutyExtraFormatted());
        assertEquals("0.40 €", salida.get(0).shippingExtraFormatted());
    }

    @Test
    @DisplayName("Lo que ya está en el carrito no se sugiere otra vez")
    void noRepiteLoQueYaLleva() {
        conCandidatos(producto(enCarrito, "lo-mismo", "Lo que ya lleva"));

        assertTrue(service.para(List.of(new Linea(enCarrito, null, 1)), "es").isEmpty());
    }

    @Test
    @DisplayName("Sin grupos aprobados en el carrito no hay nada honesto que prometer")
    void sinGruposAprobados() {
        Mockito.when(dutyBadges.lineasDe(Mockito.any())).thenReturn(List.of());

        assertTrue(service.para(List.of(new Linea(enCarrito, null, 1)), "es").isEmpty());
        Mockito.verifyNoInteractions(storefrontRead);
    }

    @Test
    @DisplayName("Si el transportista falla, la sugerencia sale sin la cifra de envío en vez de perderse")
    void transportistaCaido() {
        conCandidatos(producto(candidatoGratis, "calcetines", "Calcetines"));
        Mockito.when(dutyBadges.badgesFor(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Map.of(candidatoGratis, new DutyBadge(0, "0,00 €", grupo)));
        Mockito.when(shippingQuotes.quote(Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("transportista caído"));

        List<Sugerencia> salida = service.para(List.of(new Linea(enCarrito, null, 1)), "es");

        // El ahorro de aduana es cierto por sí solo: no se tira la sugerencia por no poder cotizar.
        assertEquals(1, salida.size());
        assertEquals("0.00 €", salida.get(0).dutyExtraFormatted());
        assertNull(salida.get(0).shippingExtraFormatted());
    }

    @Test
    @DisplayName("Nunca más de tres: es un globo, no un catálogo")
    void comoMuchoTres() {
        ProductSummaryView[] muchos = new ProductSummaryView[6];
        Map<UUID, DutyBadge> badges = new java.util.HashMap<>();
        for (int i = 0; i < 6; i++) {
            UUID id = UUID.randomUUID();
            muchos[i] = producto(id, "p" + i, "Producto " + i);
            badges.put(id, new DutyBadge(0, "0,00 €", grupo));
        }
        conCandidatos(muchos);
        Mockito.when(dutyBadges.badgesFor(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(badges);
        Mockito.when(shippingQuotes.quote(Mockito.any(), Mockito.any()))
                .thenReturn(new ShippingQuote(true, "ES", 500, "YunExpress", "BPA", 5, 9, "EU"));

        assertEquals(3, service.para(List.of(new Linea(enCarrito, null, 1)), "es").size());
    }
}
