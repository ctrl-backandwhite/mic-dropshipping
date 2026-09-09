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
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
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
    private CheckoutPreviewService checkoutPreview;
    private CarrierChannelLimitService channelLimits;
    private ProductRepository productRepository;
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
        checkoutPreview = Mockito.mock(CheckoutPreviewService.class);
        channelLimits = Mockito.mock(CarrierChannelLimitService.class);
        productRepository = Mockito.mock(ProductRepository.class);
        service = new CartSuggestionService(dutyBadges, storefrontRead, customsValuation, shippingQuotes,
                currencyService, checkoutPreview, channelLimits, productRepository);
        // Sin tope de peso salvo que la prueba diga lo contrario: así el hueco no interfiere.
        Mockito.when(channelLimits.resolve(Mockito.any(), Mockito.any()))
                .thenReturn(new CarrierChannelLimitService.ChannelLimit("", "", 0, 0, 0, 0, 0, 0, false, null));
        Mockito.when(productRepository.findAllById(Mockito.any())).thenReturn(List.of());

        // País de la Unión: cobra derecho por artículo, así que agrupar tiene sentido.
        Mockito.when(customsValuation.perArticleFeeUsdCents(Mockito.any())).thenReturn(330);
        Mockito.when(currencyService.usdToDisplay(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        Mockito.when(currencyService.formatDisplay(Mockito.any(), Mockito.any()))
                .thenAnswer(i -> i.getArgument(0).toString() + " €");
        Mockito.when(dutyBadges.lineasDe(Mockito.any())).thenReturn(List.of(new LineaDeclarada(grupo, "CN")));
        // Umbral de la Unión (150 EUR ~ 15.000 céntimos) y un carrito holgadamente por debajo.
        Mockito.when(customsValuation.deMinimisUsdCentsFor(Mockito.any())).thenReturn(15_000);
        Mockito.when(checkoutPreview.compute(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(preview(2_000));
    }

    /** Un presupuesto de checkout con el subtotal indicado; lo demás no interviene aquí. */
    private static CheckoutPreviewService.Preview preview(int subtotalUsdCents) {
        return new CheckoutPreviewService.Preview(null, subtotalUsdCents, 0, 0, 0, 0, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Un producto de verdad, con su peso: es lo que el servicio usa para saber si cabe. */
    private static com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity producto(
            UUID id, int gramos) {
        var e = new com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity();
        e.setId(id);
        e.setWeightGrams(gramos);
        return e;
    }

    private ProductSummaryView producto(UUID id, String slug, String titulo) {
        // Veintiséis componentes, de los que a esta prueba solo le importan cuatro:
        // identificador, ruta, título e imagen. El resto va vacío a propósito.
        return new ProductSummaryView(id, slug, titulo, "http://img/" + slug, null, null, null, 0, null, null, null, new java.math.BigDecimal("10.00"), null, null, null, null, null, false, null, null, null, null, null, null, false, false);
    }

    private void conCandidatos(ProductSummaryView... vistas) {
        Mockito.when(storefrontRead.productListFull(Mockito.anyInt(), Mockito.anyInt(), Mockito.any(),
                Mockito.any(ProductListFilters.class), Mockito.any()))
                .thenReturn(new PageResponse<>(List.of(vistas), 0, 12, vistas.length, 1));
    }

    @Test
    @DisplayName("Con el carrito vacío no se sugiere nada")
    void carritoVacio() {
        assertTrue(service.para(List.of(), "es").items().isEmpty());
        assertTrue(service.para(null, "es").items().isEmpty());
    }

    @Test
    @DisplayName("Donde no se cobra derecho por artículo, la promesa no significa nada y no se hace")
    void paisSinDerechoPorArticulo() {
        Mockito.when(customsValuation.perArticleFeeUsdCents(Mockito.any())).thenReturn(0);

        assertTrue(service.para(List.of(new Linea(enCarrito, null, 1)), "es").items().isEmpty());
        // Ni siquiera se molesta en buscar candidatos.
        Mockito.verifyNoInteractions(storefrontRead);
    }

    @Test
    @DisplayName("Solo se sugiere lo que NO suma arancel; lo que sí suma se descarta")
    void soloLoQueNoSumaArancel() {
        conCandidatos(producto(candidatoGratis, "calcetines", "Calcetines"),
                producto(candidatoCaro, "reloj", "Reloj"));
        Mockito.when(dutyBadges.badgesFor(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Map.of(
                candidatoGratis, new DutyBadge(0, "0,00 €", grupo, false),
                candidatoCaro, new DutyBadge(330, "3,00 €", UUID.randomUUID(), false)));
        Mockito.when(shippingQuotes.quote(Mockito.any(), Mockito.any()))
                .thenReturn(new ShippingQuote(true, "ES", 500, "YunExpress", "BPA", 5, 9, "EU"))
                .thenReturn(new ShippingQuote(true, "ES", 540, "YunExpress", "BPA", 5, 9, "EU"));

        List<Sugerencia> salida = service.para(List.of(new Linea(enCarrito, null, 1)), "es").items();

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

        assertTrue(service.para(List.of(new Linea(enCarrito, null, 1)), "es").items().isEmpty());
    }

    @Test
    @DisplayName("Sin grupos aprobados en el carrito no hay nada honesto que prometer")
    void sinGruposAprobados() {
        Mockito.when(dutyBadges.lineasDe(Mockito.any())).thenReturn(List.of());

        assertTrue(service.para(List.of(new Linea(enCarrito, null, 1)), "es").items().isEmpty());
        Mockito.verifyNoInteractions(storefrontRead);
    }

    @Test
    @DisplayName("Si el transportista falla, la sugerencia sale sin la cifra de envío en vez de perderse")
    void transportistaCaido() {
        conCandidatos(producto(candidatoGratis, "calcetines", "Calcetines"));
        Mockito.when(dutyBadges.badgesFor(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Map.of(candidatoGratis, new DutyBadge(0, "0,00 €", grupo, false)));
        Mockito.when(shippingQuotes.quote(Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("transportista caído"));

        List<Sugerencia> salida = service.para(List.of(new Linea(enCarrito, null, 1)), "es").items();

        // El ahorro de aduana es cierto por sí solo: no se tira la sugerencia por no poder cotizar.
        assertEquals(1, salida.size());
        assertEquals("0.00 €", salida.get(0).dutyExtraFormatted());
        assertNull(salida.get(0).shippingExtraFormatted());
    }

    @Test
    @DisplayName("Con todo sumando arancel, sigue sugiriendo por el ahorro de envío")
    void cuandoTodoSumaArancelSugierePorEnvio() {
        conCandidatos(producto(candidatoGratis, "calcetines", "Calcetines"));
        // Ni uno solo sale gratis de aduana.
        Mockito.when(dutyBadges.badgesFor(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Map.of(candidatoGratis, new DutyBadge(330, "3,00 €", grupo, false)));
        Mockito.when(shippingQuotes.quote(Mockito.any(), Mockito.any()))
                .thenReturn(new ShippingQuote(true, "ES", 500, "YunExpress", "BPA", 5, 9, "EU"))
                .thenReturn(new ShippingQuote(true, "ES", 540, "YunExpress", "BPA", 5, 9, "EU"))
                .thenReturn(new ShippingQuote(true, "ES", 390, "YunExpress", "BPA", 5, 9, "EU"));

        List<Sugerencia> salida = service.para(List.of(new Linea(enCarrito, null, 1)), "es").items();

        // Que la aduana no perdone no significa que no haya nada que ahorrar: en el mismo bulto,
        // 0,40 € frente a los 3,90 € que costaría pedirlo aparte.
        assertEquals(1, salida.size());
        assertEquals("SHIPPING", salida.get(0).motivo());
        assertEquals("0.40 €", salida.get(0).shippingExtraFormatted());
        assertEquals("3.90 €", salida.get(0).shippingAloneFormatted());
        assertNull(salida.get(0).dutyExtraFormatted());
    }

    @Test
    @DisplayName("Nunca sugiere algo que haga cruzar el umbral de 150 EUR: con DDP eso es un cargo sorpresa")
    void nuncaCruzaElUmbral() {
        // Carrito a 148 EUR: solo caben 2 EUR y el candidato vale 20.
        Mockito.when(checkoutPreview.compute(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(preview(14_800));
        // Vale 10 USD y solo quedan 2 antes del umbral: no cabe.
        conCandidatos(producto(candidatoGratis, "abrigo", "Abrigo"));

        assertTrue(service.para(List.of(new Linea(enCarrito, null, 1)), "es").items().isEmpty());
        // Ni siquiera llega a mirar la aduana: se descarta antes.
        Mockito.verify(dutyBadges, Mockito.never()).badgesFor(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Avisa del hueco que queda y propone primero lo que cabe en él")
    void aprovechaElHueco() {
        // Bulto de 2 kg y un carrito de 1,7 kg: quedan 300 g.
        Mockito.when(channelLimits.resolve(Mockito.any(), Mockito.any()))
                .thenReturn(new CarrierChannelLimitService.ChannelLimit("", "", 2000, 0, 0, 0, 0, 0, false, null));
        UUID ligero = UUID.randomUUID();
        UUID pesado = UUID.randomUUID();
        conCandidatos(producto(pesado, "abrigo", "Abrigo de 900 g"),
                producto(ligero, "calcetines", "Calcetines de 200 g"));
        // El repositorio responde SOLO por lo que se le pide: si devolviera siempre todo, el peso
        // del carrito incluiría a los candidatos y el hueco saldría mal.
        java.util.Map<UUID, Integer> pesos = java.util.Map.of(enCarrito, 1700, ligero, 200, pesado, 900);
        Mockito.when(productRepository.findAllById(Mockito.any())).thenAnswer(i -> {
            Iterable<UUID> pedidos = i.getArgument(0);
            List<com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity> salida =
                    new java.util.ArrayList<>();
            for (UUID id : pedidos) {
                if (pesos.containsKey(id)) {
                    salida.add(producto(id, pesos.get(id)));
                }
            }
            return salida;
        });
        Mockito.when(dutyBadges.badgesFor(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Map.of(
                ligero, new DutyBadge(0, "0,00 €", grupo, false), pesado, new DutyBadge(0, "0,00 €", grupo, false)));
        Mockito.when(shippingQuotes.quote(Mockito.any(), Mockito.any()))
                .thenReturn(new ShippingQuote(true, "ES", 500, "YunExpress", "BPA", 5, 9, "EU"));

        CartSuggestionService.Sugerencias salida = service.para(List.of(new Linea(enCarrito, null, 1)), "es");

        // Quedan 300 g de los 2.000 del bulto.
        assertEquals(300, salida.gramosLibres());
        // Y delante va lo que cabe: el abrigo de 900 g abriría un segundo paquete.
        assertEquals("calcetines", salida.items().get(0).slug());
    }

    @Test
    @DisplayName("Nunca más de tres: es un globo, no un catálogo")
    void comoMuchoTres() {
        ProductSummaryView[] muchos = new ProductSummaryView[6];
        Map<UUID, DutyBadge> badges = new java.util.HashMap<>();
        for (int i = 0; i < 6; i++) {
            UUID id = UUID.randomUUID();
            muchos[i] = producto(id, "p" + i, "Producto " + i);
            badges.put(id, new DutyBadge(0, "0,00 €", grupo, false));
        }
        conCandidatos(muchos);
        Mockito.when(dutyBadges.badgesFor(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(badges);
        Mockito.when(shippingQuotes.quote(Mockito.any(), Mockito.any()))
                .thenReturn(new ShippingQuote(true, "ES", 500, "YunExpress", "BPA", 5, 9, "EU"));

        assertEquals(3, service.para(List.of(new Linea(enCarrito, null, 1)), "es").items().size());
    }
}
