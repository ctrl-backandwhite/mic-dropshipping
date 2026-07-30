package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.AttributeKeyView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CartQuoteItemIn;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CartQuoteOut;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.HistoryPoint;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.HomeSectionsResponse;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ImageSearchRequest;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ImageSearchResult;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ImportUrlRequest;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ImportUrlResponse;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.MarginEstimate;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ShippingQuoteItem;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ShippingQuoteRequest;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ShippingRateView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ShippingZoneView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.SuggestionView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.VariantView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.api.mapper.ProductListFilters;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ProductDetailQueryService;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductHistoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShippingRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShippingZoneEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductAttributeRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductHistoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductSpecificationRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductTagRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShippingRateRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShippingZoneRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API pública del catálogo. Es lo que consume la tienda y lo que ve un integrador: si aquí se rompe una
 * regla, el comprador ve un precio que no es el que se le cobra, un porte inventado o productos que
 * debería ver solo el administrador.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov03StorefrontCatalogControllerTest {

    @Mock
    CatalogUseCase catalogUseCase;
    @Mock
    CatalogStorefrontReadService storefrontRead;
    @Mock
    ProductDetailQueryService productDetailQuery;
    @Mock
    ProductRepository productRepository;
    @Mock
    ProductSpecificationRepository specRepository;
    @Mock
    ProductAttributeRepository attributeRepository;
    @Mock
    ProductTagRepository tagRepository;
    @Mock
    ShippingZoneRepository zoneRepository;
    @Mock
    ShippingRateRepository rateRepository;
    @Mock
    ProductHistoryRepository historyRepository;
    @Mock
    ProductMapper productMapper;
    @Mock
    PricingService pricingService;
    @Mock
    MarginService marginService;
    @Mock
    CurrencyRateService currencyService;
    @Mock
    ProductPriceTierRepository priceTierRepository;

    @InjectMocks
    StorefrontCatalogController controller;

    @AfterEach
    void limpiaSeguridad() {
        SecurityContextHolder.clearContext();
    }

    /* ============================ ayudantes ============================ */

    private static ProductEntity producto() {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setStatus(ProductStatus.ACTIVE);
        p.setVariants(new ArrayList<>());
        p.setTranslations(new ArrayList<>());
        return p;
    }

    private static SupplierEntity proveedor() {
        SupplierEntity s = new SupplierEntity();
        s.setId(UUID.randomUUID());
        s.setName("Shenzhen Co.");
        return s;
    }

    private static ShippingRateEntity tarifa(SupplierEntity s, int baseCents, int perKgCents, Integer minGramos,
            Integer maxGramos) {
        return ShippingRateEntity.builder().id(UUID.randomUUID()).supplier(s).countryCode("ES").method("STANDARD")
                .carrier("YunExpress").transitDaysMin(7).transitDaysMax(15).baseCents(baseCents).perKgCents(perKgCents)
                .minWeightGrams(minGramos).maxWeightGrams(maxGramos).active(true).build();
    }

    private static PricingService.PricedAmount precio(BigDecimal display) {
        return new PricingService.PricedAmount(null, null, display, "EUR", "€", null, null, null, null, null, null,
                null, null, null);
    }

    private static ProductSummaryView resumen(UUID id) {
        ProductSummaryView v = mock(ProductSummaryView.class);
        when(v.id()).thenReturn(id);
        return v;
    }

    private void identidadEnDivisa() {
        when(currencyService.usdToDisplay(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pricingService.displayCurrencyCode()).thenReturn("EUR");
    }

    private static void autenticaComoAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "x",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    /* ==================== filtro de verificación (permisos) ==================== */

    @Test
    void elFiltroDeProductosVerificadosSoloLoAplicaElAdministrador() {
        // `verified` es una marca interna de revisión: si un usuario cualquiera pudiera filtrar por ella,
        // se le estaría enseñando qué parte del catálogo está sin revisar.
        controller.list(0, 20, "es", null, null, null, null, null, null, null, null, null, null, null, null, null,
                Boolean.TRUE);

        ArgumentCaptor<ProductListFilters> captor = ArgumentCaptor.forClass(ProductListFilters.class);
        verify(storefrontRead).productListFull(eq(0), eq(20), eq("es"), captor.capture(), isNull());
        assertThat(captor.getValue().verified()).isNull();
    }

    @Test
    void elAdministradorSiPuedeFiltrarPorProductosVerificados() {
        autenticaComoAdmin();

        controller.list(0, 20, "es", null, null, null, null, null, null, null, null, null, null, null, null, null,
                Boolean.FALSE);

        ArgumentCaptor<ProductListFilters> captor = ArgumentCaptor.forClass(ProductListFilters.class);
        verify(storefrontRead).productListFull(eq(0), eq(20), eq("es"), captor.capture(), isNull());
        assertThat(captor.getValue().verified()).isFalse();
    }

    /* ==================== importar por URL ==================== */

    @ParameterizedTest
    @CsvSource({
            "https://detail.1688.com/offer/912345678.html, 1688,       OFFER-912345678",
            "https://item.taobao.com/item.htm?id=654321,   taobao,     654321",
            "https://es.aliexpress.com/item/100500.html,   aliexpress, 100500",
            "https://www.ebay.com/itm/zapatos-rojos/98765, ebay,       98765"
    })
    void cadaMercadoSeReconocePorSuFormaDeUrl(String url, String fuente, String externo) {
        when(productRepository.findBySourceAndExternalId(fuente, externo)).thenReturn(Optional.empty());

        ImportUrlResponse res = controller.importByUrl(new ImportUrlRequest(url), "es");

        assertThat(res.source()).isEqualTo(fuente);
        assertThat(res.externalId()).isEqualTo(externo);
        assertThat(res.matched()).isFalse();
        assertThat(res.resolveHint()).contains(fuente);
    }

    @Test
    void unaUrlDeUnMercadoDesconocidoNoInventaUnaOfertaYExplicaQuePegar() {
        ImportUrlResponse res = controller.importByUrl(new ImportUrlRequest("https://example.com/cosa"), "es");

        assertThat(res.matched()).isFalse();
        assertThat(res.source()).isNull();
        assertThat(res.externalId()).isNull();
        assertThat(res.resolveHint()).contains("1688");
        verify(productRepository, never()).findBySourceAndExternalId(anyString(), anyString());
    }

    @Test
    void siLaOfertaYaEstaEnElCatalogoSeDevuelveElProductoExistente() {
        ProductEntity p = producto();
        ProductSummaryView view = resumen(p.getId());
        when(productRepository.findBySourceAndExternalId("1688", "OFFER-77")).thenReturn(Optional.of(p));
        when(productMapper.toSummary(p, "es")).thenReturn(view);

        ImportUrlResponse res = controller.importByUrl(
                new ImportUrlRequest("https://detail.1688.com/offer/77.html"), "es");

        assertThat(res.matched()).isTrue();
        assertThat(res.product()).isSameAs(view);
        assertThat(res.resolveHint()).isNull();
    }

    @Test
    void importarSinUrlEsUnErrorDeNegocioYNoUnFalloInterno() {
        ImportUrlRequest sinUrl = new ImportUrlRequest(null);

        assertThatThrownBy(() -> controller.importByUrl(sinUrl, "es")).isInstanceOf(BusinessException.class);
    }

    /* ==================== cotización del carrito ==================== */

    @Test
    void elCarritoSeCotizaAlPrecioActualYElSubtotalEsLaSumaDeLasLineas() {
        // El carrito del navegador congela el precio al añadir; el checkout re-cotiza aquí para que lo
        // mostrado sea EXACTAMENTE lo cobrado.
        ProductEntity p = producto();
        when(pricingService.displayCurrencyCode()).thenReturn("EUR");
        when(pricingService.displayCurrencySymbol()).thenReturn("€");
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(pricingService.priceFor(p, null)).thenReturn(precio(new BigDecimal("12.50")));
        when(currencyService.formatDisplay(any(), eq("EUR"))).thenAnswer(inv -> inv.getArgument(0) + " €");

        CartQuoteOut out = controller.cartQuote(List.of(new CartQuoteItemIn(p.getId(), null, 3)));

        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).lineTotal()).isEqualByComparingTo("37.50");
        assertThat(out.subtotal()).isEqualByComparingTo("37.50");
        assertThat(out.currency()).isEqualTo("EUR");
    }

    @Test
    void unaLineaDeUnProductoRetiradoDelCatalogoSeDescartaSinTumbarLaCotizacion() {
        ProductEntity vivo = producto();
        UUID borrado = UUID.randomUUID();
        when(pricingService.displayCurrencyCode()).thenReturn("EUR");
        when(productRepository.findById(vivo.getId())).thenReturn(Optional.of(vivo));
        when(productRepository.findById(borrado)).thenReturn(Optional.empty());
        when(pricingService.priceFor(vivo, null)).thenReturn(precio(new BigDecimal("10.00")));

        CartQuoteOut out = controller.cartQuote(
                List.of(new CartQuoteItemIn(borrado, null, 1), new CartQuoteItemIn(vivo.getId(), null, 1)));

        assertThat(out.items()).hasSize(1);
        assertThat(out.subtotal()).isEqualByComparingTo("10.00");
    }

    @Test
    void unProductoSinPrecioNoSeCotizaEnLugarDeCobrarseACero() {
        ProductEntity p = producto();
        when(pricingService.displayCurrencyCode()).thenReturn("EUR");
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(pricingService.priceFor(p, null)).thenReturn(precio(null));

        CartQuoteOut out = controller.cartQuote(List.of(new CartQuoteItemIn(p.getId(), null, 2)));

        assertThat(out.items()).isEmpty();
        assertThat(out.subtotal()).isEqualByComparingTo("0");
    }

    @Test
    void unaCantidadNoPositivaSeCotizaComoUnaUnidadYNuncaComoUnAbono() {
        ProductEntity p = producto();
        when(pricingService.displayCurrencyCode()).thenReturn("EUR");
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(pricingService.priceFor(p, null)).thenReturn(precio(new BigDecimal("9.00")));

        CartQuoteOut out = controller.cartQuote(List.of(new CartQuoteItemIn(p.getId(), null, -5)));

        assertThat(out.subtotal()).isEqualByComparingTo("9.00");
    }

    @Test
    void elCarritoVacioONuloCotizaCeroSinConsultarElCatalogo() {
        when(pricingService.displayCurrencyCode()).thenReturn("USD");

        assertThat(controller.cartQuote(null).subtotal()).isEqualByComparingTo("0");
        assertThat(controller.cartQuote(List.of()).items()).isEmpty();
        verify(productRepository, never()).findById(any());
    }

    @Test
    void laLineaConVarianteSeCotizaConElPrecioDeEsaVariante() {
        ProductEntity p = producto();
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(UUID.randomUUID());
        p.getVariants().add(v);
        when(pricingService.displayCurrencyCode()).thenReturn("EUR");
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(pricingService.priceFor(p, v)).thenReturn(precio(new BigDecimal("20.00")));

        CartQuoteOut out = controller.cartQuote(List.of(new CartQuoteItemIn(p.getId(), v.getId(), 2)));

        assertThat(out.items().get(0).variantId()).isEqualTo(v.getId());
        assertThat(out.subtotal()).isEqualByComparingTo("40.00");
    }

    /* ==================== presupuesto de envío ==================== */

    @Test
    void elPorteSeCalculaComoBaseMasPrecioPorKiloDelPesoTotal() {
        ProductEntity p = producto();
        SupplierEntity s = proveedor();
        p.setSupplier(s);
        p.setWeightGrams(500);
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(s.getId(), "ES"))
                .thenReturn(List.of(tarifa(s, 500, 200, null, null)));

        List<ShippingQuoteItem> quote = controller.shippingQuote(
                new ShippingQuoteRequest(p.getId(), null, 3, "es"));

        // 3 x 500 g = 1,5 kg -> 500 + 200*1,5 = 800 céntimos
        assertThat(quote).hasSize(1);
        assertThat(quote.get(0).cost()).isEqualByComparingTo("8.00");
        assertThat(quote.get(0).currency()).isEqualTo("USD");
    }

    @Test
    void unaTarifaQueNoCubreElPesoDelPaqueteNoSeOfrece() {
        // Ofrecer una tarifa fuera de su rango de peso significa cobrar un porte que el transportista
        // rechazará después.
        ProductEntity p = producto();
        SupplierEntity s = proveedor();
        p.setSupplier(s);
        p.setPackageWeightGrams(2000);
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(s.getId(), "ES")).thenReturn(
                List.of(tarifa(s, 100, 0, null, 1000), tarifa(s, 200, 0, 5000, null), tarifa(s, 300, 0, 1000, 3000)));

        List<ShippingQuoteItem> quote = controller.shippingQuote(new ShippingQuoteRequest(p.getId(), null, 1, "ES"));

        assertThat(quote).hasSize(1);
        assertThat(quote.get(0).cost()).isEqualByComparingTo("3.00");
    }

    @Test
    void lasOpcionesDeEnvioSalenDeLaMasBarataALaMasCara() {
        ProductEntity p = producto();
        SupplierEntity s = proveedor();
        p.setSupplier(s);
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(s.getId(), "ES"))
                .thenReturn(List.of(tarifa(s, 1500, 0, null, null), tarifa(s, 400, 0, null, null)));

        List<ShippingQuoteItem> quote = controller.shippingQuote(new ShippingQuoteRequest(p.getId(), null, 1, "es"));

        assertThat(quote).extracting(ShippingQuoteItem::cost)
                .containsExactly(new BigDecimal("4.00"), new BigDecimal("15.00"));
    }

    @Test
    void sinPesoConocidoSeUsanQuinientosGramosParaNoDejarElPorteACero() {
        ProductEntity p = producto();
        SupplierEntity s = proveedor();
        p.setSupplier(s);
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(s.getId(), "ES"))
                .thenReturn(List.of(tarifa(s, 0, 1000, null, null)));

        List<ShippingQuoteItem> quote = controller.shippingQuote(new ShippingQuoteRequest(p.getId(), null, 1, "es"));

        assertThat(quote.get(0).cost()).isEqualByComparingTo("5.00"); // 0,5 kg * 10,00
    }

    @Test
    void unProductoSinProveedorNoSePuedeEnviarYSeAvisaComoNoEncontrado() {
        ProductEntity p = producto();
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        ShippingQuoteRequest req = new ShippingQuoteRequest(p.getId(), null, 1, "es");

        assertThatThrownBy(() -> controller.shippingQuote(req)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void presupuestarUnProductoInexistenteDaNoEncontrado() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());
        ShippingQuoteRequest req = new ShippingQuoteRequest(id, null, 1, "es");

        assertThatThrownBy(() -> controller.shippingQuote(req)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void lasTarifasSeConsultanConElPaisEnMayusculasYSePresentanEnEuros() {
        SupplierEntity s = proveedor();
        when(rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(s.getId(), "ES"))
                .thenReturn(List.of(tarifa(s, 1234, 567, null, 3000)));

        List<ShippingRateView> vistas = controller.shippingRates(s.getId(), "es");

        assertThat(vistas).hasSize(1);
        assertThat(vistas.get(0).baseCost()).isEqualByComparingTo("12.34");
        assertThat(vistas.get(0).perKgCost()).isEqualByComparingTo("5.67");
        assertThat(vistas.get(0).maxWeightGrams()).isEqualTo(3000);
    }

    @Test
    void lasZonasDeEnvioMuestranElProveedorYSuPais() {
        SupplierEntity s = proveedor();
        ShippingZoneEntity z = ShippingZoneEntity.builder().id(UUID.randomUUID()).supplier(s).countryCode("ES")
                .region("EU").active(true).build();
        when(zoneRepository.findBySupplier_IdAndActiveTrueOrderByCountryCodeAsc(s.getId())).thenReturn(List.of(z));

        List<ShippingZoneView> zonas = controller.shippingZones(s.getId());

        assertThat(zonas).hasSize(1);
        assertThat(zonas.get(0).supplierName()).isEqualTo("Shenzhen Co.");
        assertThat(zonas.get(0).countryCode()).isEqualTo("ES");
    }

    /* ==================== estimación de margen ==================== */

    @Test
    void laEstimacionParteDelTramoDePrecioAplicableALaCantidad() {
        // Sin tramo, el coste sale del precio unitario suelto y la rentabilidad estimada engaña: un pedido
        // de 100 unidades no se compra al precio de 1.
        ProductEntity p = producto();
        SupplierEntity s = proveedor();
        p.setSupplier(s);
        p.setCurrency("CNY");
        p.setBasePrice(new BigDecimal("99"));
        ProductPriceTierEntity tramo1 = ProductPriceTierEntity.builder().minQty(1).maxQty(9)
                .unitPrice(new BigDecimal("90")).build();
        ProductPriceTierEntity tramo10 = ProductPriceTierEntity.builder().minQty(10)
                .unitPrice(new BigDecimal("70")).build();
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId())).thenReturn(List.of(tramo1, tramo10));
        when(currencyService.toUsd(new BigDecimal("70"), "CNY")).thenReturn(new BigDecimal("10"));
        when(marginService.apply(new BigDecimal("10"), p, null)).thenReturn(
                new MarginService.PriceWithMargin(new BigDecimal("10"), new BigDecimal("25"), null,
                        new BigDecimal("150")));
        when(rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(s.getId(), "ES"))
                .thenReturn(List.of(tarifa(s, 300, 0, null, null), tarifa(s, 900, 0, null, null)));
        identidadEnDivisa();

        MarginEstimate est = controller.marginEstimate(p.getId(), "es", 12, null);

        assertThat(est.appliedTierMinQty()).isEqualTo(10);
        assertThat(est.cost()).isEqualByComparingTo("10.00");
        assertThat(est.suggestedRetail()).isEqualByComparingTo("25.00");
        assertThat(est.shipping()).isEqualByComparingTo("3.00"); // la más barata de las activas
        assertThat(est.commission()).isEqualByComparingTo("0.00"); // sin comisión configurada
        assertThat(est.netProfit()).isEqualByComparingTo("144.00"); // (25-10-3) x 12
        assertThat(est.marginPct()).isEqualByComparingTo("48.0");
        assertThat(est.currency()).isEqualTo("EUR");
        assertThat(est.appliedMarginPct()).isEqualByComparingTo("150");
    }

    @Test
    void sinTramosDePrecioSeUsaElPrecioBaseYNoSeDeclaraTramoAplicado() {
        ProductEntity p = producto();
        p.setBasePrice(new BigDecimal("50"));
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId())).thenReturn(List.of());
        // Sin divisa propia el catálogo importado de 1688 se asume en CNY.
        when(currencyService.toUsd(new BigDecimal("50"), "CNY")).thenReturn(new BigDecimal("7"));
        when(marginService.apply(new BigDecimal("7"), p, null)).thenReturn(
                new MarginService.PriceWithMargin(new BigDecimal("7"), new BigDecimal("17"), null, null));
        identidadEnDivisa();

        MarginEstimate est = controller.marginEstimate(p.getId(), "es", 1, null);

        assertThat(est.appliedTierMinQty()).isNull();
        assertThat(est.cost()).isEqualByComparingTo("7.00");
        assertThat(est.shipping()).isEqualByComparingTo("0.00"); // sin proveedor no hay porte estimado
        assertThat(est.netProfit()).isEqualByComparingTo("10.00");
    }

    @Test
    void unProductoSinCosteNiMargenNoDivideEntreCeroAlCalcularElPorcentaje() {
        ProductEntity p = producto();
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId())).thenReturn(List.of());
        when(marginService.apply(BigDecimal.ZERO, p, null))
                .thenReturn(new MarginService.PriceWithMargin(BigDecimal.ZERO, null, null, null));
        identidadEnDivisa();

        MarginEstimate est = controller.marginEstimate(p.getId(), "es", 1, null);

        assertThat(est.marginPct()).isEqualByComparingTo("0.0");
        assertThat(est.suggestedRetail()).isEqualByComparingTo("0.00");
    }

    @Test
    void laComisionDePlataformaConfiguradaSeRestaDelBeneficio() {
        ProductEntity p = producto();
        p.setBasePrice(new BigDecimal("1"));
        ReflectionTestUtils.setField(controller, "platformCommissionPct", new BigDecimal("10"));
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId())).thenReturn(List.of());
        when(currencyService.toUsd(new BigDecimal("1"), "CNY")).thenReturn(new BigDecimal("1"));
        when(marginService.apply(new BigDecimal("1"), p, null)).thenReturn(
                new MarginService.PriceWithMargin(new BigDecimal("1"), new BigDecimal("100"), null, null));
        identidadEnDivisa();

        MarginEstimate est = controller.marginEstimate(p.getId(), "es", 1, null);

        assertThat(est.commission()).isEqualByComparingTo("10.00"); // 10% de 100
        assertThat(est.netProfit()).isEqualByComparingTo("89.00");
    }

    @Test
    void elPorteEstimadoUsaElPesoDeLaVarianteElegidaAntesQueElDelProducto() {
        // DROP-675: dos colores del mismo producto pueden pesar muy distinto; estimar con el peso del
        // producto da un porte que no es el que se pagará.
        ProductEntity p = producto();
        SupplierEntity s = proveedor();
        p.setSupplier(s);
        p.setWeightGrams(200);
        p.setBasePrice(BigDecimal.ONE);
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(UUID.randomUUID());
        v.setPackageWeightGrams(4000);
        p.getVariants().add(v);
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId())).thenReturn(List.of());
        when(currencyService.toUsd(any(), anyString())).thenReturn(BigDecimal.ONE);
        when(marginService.apply(any(), any(), any())).thenReturn(
                new MarginService.PriceWithMargin(BigDecimal.ONE, new BigDecimal("2"), null, null));
        when(rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(s.getId(), "ES"))
                .thenReturn(List.of(tarifa(s, 0, 100, null, null)));
        identidadEnDivisa();

        MarginEstimate est = controller.marginEstimate(p.getId(), "es", 1, v.getId());

        assertThat(est.shipping()).isEqualByComparingTo("4.00"); // 4 kg x 1,00
    }

    @Test
    void estimarElMargenDeUnProductoInexistenteDaNoEncontrado() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.marginEstimate(id, "ES", 1, null)).isInstanceOf(NotFoundException.class);
    }

    /* ==================== histórico de precio ==================== */

    @Test
    void elHistoricoSeLimitaAUnAnioYSeFechaEnHorarioUniversal() {
        // Calcular la ventana con la zona de la máquina haría que el mismo "últimos N días" devolviera un
        // día más o menos según dónde corriera el servidor.
        UUID id = UUID.randomUUID();
        ProductHistoryEntity h = ProductHistoryEntity.builder().snapshotDate(LocalDate.of(2026, Month.JANUARY, 5))
                .priceUsdCents(1999).stock(7).build();
        when(historyRepository.findByProduct_IdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(eq(id), any()))
                .thenReturn(List.of(h));

        List<HistoryPoint> puntos = controller.priceHistory(id, 5000);

        ArgumentCaptor<LocalDate> desde = ArgumentCaptor.forClass(LocalDate.class);
        verify(historyRepository).findByProduct_IdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(eq(id),
                desde.capture());
        assertThat(desde.getValue()).isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(365));
        assertThat(puntos).hasSize(1);
        assertThat(puntos.get(0).price()).isEqualByComparingTo("19.99");
        assertThat(puntos.get(0).stock()).isEqualTo(7);
    }

    /* ==================== sugerencias ==================== */

    @Test
    void unaBusquedaVaciaNoConsultaElCatalogo() {
        assertThat(controller.suggest("   ", "es", 10)).isEmpty();
        assertThat(controller.suggest(null, "es", 10)).isEmpty();
        verify(productRepository, never()).findVisibleByStatus(any(), any());
    }

    @Test
    void laSugerenciaSeMuestraEnElIdiomaPedidoYCaeAlChinoSiNoHayTraduccion() {
        ProductEntity conTraduccion = producto();
        conTraduccion.setSlug("chaqueta-roja");
        conTraduccion.setTitleZh("红夹克");
        ProductTranslationEntity t = new ProductTranslationEntity();
        t.setLanguage("es");
        t.setTitle("Chaqueta roja");
        conTraduccion.getTranslations().add(t);
        ProductEntity sinTraduccion = producto();
        sinTraduccion.setSlug("chaqueta-azul");
        sinTraduccion.setTitleZh("蓝夹克");
        when(productRepository.findVisibleByStatus(eq(ProductStatus.ACTIVE), any()))
                .thenReturn(new PageImpl<>(List.of(conTraduccion, sinTraduccion)));

        List<SuggestionView> s = controller.suggest("chaqueta", "es", 10);

        assertThat(s).extracting(SuggestionView::text).containsExactly("Chaqueta roja", "蓝夹克");
        assertThat(s).allMatch(x -> "product".equals(x.type()));
    }

    @Test
    void laSugerenciaTambienEncuentraPorIdentificadorExternoYRespetaElLimite() {
        ProductEntity a = producto();
        a.setExternalId("OFFER-9911");
        a.setTitleZh("A");
        ProductEntity b = producto();
        b.setExternalId("OFFER-9912");
        b.setTitleZh("B");
        when(productRepository.findVisibleByStatus(eq(ProductStatus.ACTIVE), any()))
                .thenReturn(new PageImpl<>(List.of(a, b)));

        assertThat(controller.suggest("offer-99", "es", 1)).hasSize(1);
    }

    /* ==================== atributos y etiquetas ==================== */

    @Test
    void lasClavesDeAtributoSalenOrdenadasPorUsoDeMayorAMenor() {
        // El buscador pinta las facetas en este orden: invertirlo esconde los filtros que de verdad se usan.
        ProductAttributeEntity color1 = ProductAttributeEntity.builder().attrKey("color").attrValue("rojo").build();
        ProductAttributeEntity color2 = ProductAttributeEntity.builder().attrKey("color").attrValue("azul").build();
        ProductAttributeEntity talla = ProductAttributeEntity.builder().attrKey("talla").attrValue("M").build();
        when(attributeRepository.findAll()).thenReturn(List.of(talla, color1, color2));

        List<AttributeKeyView> claves = controller.attributeKeys();

        assertThat(claves).extracting(AttributeKeyView::key).containsExactly("color", "talla");
        assertThat(claves.get(0).usage()).isEqualTo(2L);
    }

    @Test
    void unaEtiquetaSoloDevuelveProductosActivosYHastaElLimitePedido() {
        // Un producto retirado seguiría etiquetado en base de datos; enseñarlo lleva a una ficha rota.
        ProductEntity activo = producto();
        ProductEntity otroActivo = producto();
        ProductEntity retirado = producto();
        retirado.setStatus(ProductStatus.ARCHIVED);
        when(tagRepository.findProductIdsByTag("oferta"))
                .thenReturn(List.of(activo.getId(), retirado.getId(), otroActivo.getId()));
        when(productRepository.findAllById(any())).thenReturn(List.of(activo, retirado, otroActivo));
        when(productMapper.toSummary(any(), anyString())).thenAnswer(inv -> resumen(
                ((ProductEntity) inv.getArgument(0)).getId()));

        List<ProductSummaryView> vistos = controller.productsByTag("oferta", "es", 1);

        assertThat(vistos).hasSize(1);
        assertThat(vistos.get(0).id()).isEqualTo(activo.getId());
    }

    /* ==================== emparejado de variantes ==================== */

    @Test
    void laVarianteSeEmparejaPorTodasLasOpcionesPedidasIgnorandoMayusculas() {
        UUID productId = UUID.randomUUID();
        VariantView rojaM = new VariantView(UUID.randomUUID(), "S1", null, "Roja M", BigDecimal.ONE, 5, null,
                Map.of("color", "Rojo", "talla", "M"), true);
        VariantView rojaL = new VariantView(UUID.randomUUID(), "S2", null, "Roja L", BigDecimal.ONE, 5, null,
                Map.of("color", "Rojo", "talla", "L"), true);
        when(storefrontRead.variantsForProduct(productId)).thenReturn(List.of(rojaM, rojaL));

        assertThat(controller.variantMatch(productId, Map.of("color", "rojo", "talla", "m"))).containsExactly(rojaM);
        assertThat(controller.variantMatch(productId, Map.of("color", "rojo"))).hasSize(2);
        assertThat(controller.variantMatch(productId, Map.of("color", "verde"))).isEmpty();
    }

    /* ==================== portada ==================== */

    @Test
    void laPortadaTraeLasCuatroSeccionesYSoloCategoriasConProductos() {
        // DROP-269: una categoría vacía en "destacadas" lleva a una parrilla en blanco.
        ProductSummaryView v = resumen(UUID.randomUUID());
        when(catalogUseCase.listBestsellers(isNull(), any(), eq("es"))).thenReturn(new PageImpl<>(List.of(v)));
        when(storefrontRead.productList(anyInt(), anyInt(), anyString(), any(), any(), any(), any(), any(),
                anyString())).thenReturn(new PageResponse<>(List.of(v), 0, 8, 1, 1));
        when(productRepository.findVisibleWithVideo(eq(ProductStatus.ACTIVE), any()))
                .thenReturn(new PageImpl<>(List.of()));
        CategoryView vacia = new CategoryView(UUID.randomUUID(), "vacia", "Vacía", null, null, 0, null, 0, List.of());
        CategoryView hija = new CategoryView(UUID.randomUUID(), "calzado", "Calzado", null, null, 0, null, 30,
                List.of());
        CategoryView raiz = new CategoryView(UUID.randomUUID(), "moda", "Moda", null, null, 0, null, 5,
                List.of(hija, vacia));
        when(storefrontRead.categoriesTree("es")).thenReturn(List.of(raiz));

        HomeSectionsResponse home = controller.homeSections("es", 8);

        assertThat(home.sections()).extracting("code").containsExactly("trending", "newest", "video", "top_selling");
        // Las destacadas se aplanan (subcategorías incluidas) y se ordenan por nº de productos directos.
        assertThat(home.hotCategories()).extracting(CategoryView::slug).containsExactly("calzado", "moda");
    }

    @Test
    void laPortadaNuncaPideMasDeVeinticuatroProductosPorSeccion() {
        // El tamaño de página no puede quedar a merced del cliente: una petición con perSection=5000
        // arrastraría el catálogo entero en cada visita a la portada.
        when(catalogUseCase.listBestsellers(isNull(), any(), eq("es"))).thenReturn(new PageImpl<>(List.of()));
        when(storefrontRead.productList(anyInt(), anyInt(), anyString(), any(), any(), any(), any(), any(),
                anyString())).thenReturn(new PageResponse<>(List.of(), 0, 24, 0, 0));
        when(productRepository.findVisibleWithVideo(eq(ProductStatus.ACTIVE), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(storefrontRead.categoriesTree("es")).thenReturn(List.of());

        controller.homeSections("es", 5000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(catalogUseCase).listBestsellers(isNull(), captor.capture(), eq("es"));
        assertThat(captor.getValue().getPageSize()).isEqualTo(24);
    }

    /* ==================== búsqueda por imagen ==================== */

    @Test
    void laBusquedaPorImagenEsDeterministaYLaFotoSubidaMandaSobreLaUrl() {
        // Sin resultados estables, recargar la misma búsqueda daría una lista distinta cada vez.
        List<ProductEntity> pool = List.of(producto(), producto(), producto());
        when(productRepository.findVisibleByStatus(eq(ProductStatus.ACTIVE), any())).thenReturn(new PageImpl<>(pool));
        when(productMapper.toSummary(any(), anyString())).thenAnswer(inv -> resumen(
                ((ProductEntity) inv.getArgument(0)).getId()));

        ImageSearchRequest conAmbas = new ImageSearchRequest("BASE64", "https://otra/foto.jpg", 3);
        ImageSearchRequest soloBase64 = new ImageSearchRequest("BASE64", null, 3);
        List<UUID> primera = controller.searchByImage(conAmbas, "es").stream().map(r -> r.product().id()).toList();
        List<UUID> repetida = controller.searchByImage(conAmbas, "es").stream().map(r -> r.product().id()).toList();
        List<UUID> mismaFoto = controller.searchByImage(soloBase64, "es").stream().map(r -> r.product().id()).toList();

        assertThat(repetida).isEqualTo(primera);
        assertThat(mismaFoto).isEqualTo(primera);
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 24})
    void laBusquedaPorImagenNuncaDevuelveMasDeVeinticuatroResultados(int pedidos) {
        List<ProductEntity> pool = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            pool.add(producto());
        }
        when(productRepository.findVisibleByStatus(eq(ProductStatus.ACTIVE), any())).thenReturn(new PageImpl<>(pool));
        when(productMapper.toSummary(any(), anyString())).thenAnswer(inv -> resumen(
                ((ProductEntity) inv.getArgument(0)).getId()));

        assertThat(controller.searchByImage(new ImageSearchRequest(null, "https://x/y.jpg", pedidos), "es"))
                .hasSize(24);
    }

    @Test
    void sinLimiteLaBusquedaPorImagenDevuelveDoceResultadosOrdenadosPorParecido() {
        List<ProductEntity> pool = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pool.add(producto());
        }
        when(productRepository.findVisibleByStatus(eq(ProductStatus.ACTIVE), any())).thenReturn(new PageImpl<>(pool));
        when(productMapper.toSummary(any(), anyString())).thenAnswer(inv -> resumen(
                ((ProductEntity) inv.getArgument(0)).getId()));

        List<ImageSearchResult> res = controller.searchByImage(new ImageSearchRequest(null, null, null), "es");

        assertThat(res).hasSize(12).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
    }

    /* ==================== paginación de los más vendidos ==================== */

    @Test
    void losMasVendidosNuncaPidenMasDeCienPorPagina() {
        Page<ProductSummaryView> page = new PageImpl<>(List.of());
        when(catalogUseCase.listBestsellers(isNull(), any(), eq("es"))).thenReturn(page);

        controller.bestsellers(null, 2, 5000, "es");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(catalogUseCase).listBestsellers(isNull(), captor.capture(), eq("es"));
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    void lasTendenciasSonLosMasVendidos() {
        // Son la misma consulta: si dejaran de serlo, la portada mostraría dos parrillas contradictorias.
        UUID categoria = UUID.randomUUID();
        when(catalogUseCase.listBestsellers(eq(categoria), any(), eq("es")))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        assertThat(controller.trending(categoria, 0, 10, "es")).isNotNull();
        verify(catalogUseCase).listBestsellers(eq(categoria), any(), eq("es"));
    }

    /* ==================== proyecciones delegadas ==================== */

    @Test
    void lasProyeccionesDeFichaSeSirvenDelServicioDeDetalleYNoDeConsultasPropias() {
        UUID id = UUID.randomUUID();
        ProductEntity relacionado = producto();
        // El doble se construye ANTES del when(): crear un mock dentro de un thenReturn a medio escribir
        // deja a Mockito con un stub sin terminar.
        ProductSummaryView vista = resumen(relacionado.getId());
        when(productDetailQuery.relatedProducts(id, 4)).thenReturn(List.of(relacionado));
        when(productMapper.toSummary(relacionado, "es")).thenReturn(vista);
        when(productDetailQuery.attributes(id, "es")).thenReturn(Map.of("color", "rojo"));
        when(productDetailQuery.tags(id)).thenReturn(List.of("oferta"));

        assertThat(controller.relatedProducts(id, "es", 4)).hasSize(1);
        assertThat(controller.attributes(id, "es")).singleElement()
                .satisfies(a -> assertThat(a.key()).isEqualTo("color"));
        assertThat(controller.tags(id)).containsExactly("oferta");
    }

    @Test
    void elListadoDeNovedadesPideExplicitamenteElOrdenPorFecha() {
        // Si el orden se perdiera, "Novedades" mostraría el mismo contenido que el listado por relevancia.
        when(storefrontRead.productList(0, 12, "es", null, null, null, null, null, "newest"))
                .thenReturn(new PageResponse<>(List.of(), 0, 12, 0, 0));

        assertThat(controller.newest(0, 12, "es")).isNotNull();
        verify(storefrontRead).productList(0, 12, "es", null, null, null, null, null, "newest");
    }
}
