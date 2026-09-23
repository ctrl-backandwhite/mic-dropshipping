package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.ParcelSpec;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.SupportedCountry;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService.RateOption;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CainiaoZoneEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CainiaoZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cobertura de destinos, cotización de envío y utilidades de cuenta de YunExpress.
 *
 * <p>La cotización está en el camino del checkout: si el transportista no contesta o no recomienda canal,
 * hay que tarifar por la tabla de zonas y seguir vendiendo. Un fallo propagado aquí dejaría al cliente sin
 * poder pagar; una tarifa mal convertida se comería el margen en cada pedido.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov04YunExpressQuoteTest {

    private static final String PATH_PRICE_TRIAL = "/v1/price-trial/get";

    @Mock
    CainiaoZoneRepository zoneRepository;
    @Mock
    YunExpressClient client;
    @Mock
    CustomsValuationService customsValuation;
    @Mock
    CurrencyRateService currencyRateService;

    private final ObjectMapper mapper = new ObjectMapper();
    private YunExpressFulfillmentService service;

    @BeforeEach
    void setUp() {
        service = new YunExpressFulfillmentService(zoneRepository, client, customsValuation,
                new CustomsDutyLinesService(null), null, currencyRateService, new MockEnvironment(), null);
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "quoteTimeoutSeconds", 5L);
        ReflectionTestUtils.setField(service, "volumetricDivisor", 6000.0);
        ReflectionTestUtils.setField(service, "volumetricMinCm3", 6000.0);
        ReflectionTestUtils.setField(service, "productCode", "");
        ReflectionTestUtils.setField(service, "productGroupCode", "");
        ReflectionTestUtils.setField(service, "iossNumber", "");
        ReflectionTestUtils.setField(service, "defaultTaxMode", "DDP");
    }

    /** Enciende la integración real (bandera + credenciales), que es lo que mira {@code isActive()}. */
    private void conYunExpressOperativo() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(client.hasCredentials()).thenReturn(true);
    }

    private CainiaoZoneEntity zonaEspana() {
        CainiaoZoneEntity z = CainiaoZoneEntity.builder().countryCode("ES").countryName("España").zone("EU")
                .baseCents(500).perKgCents(1000).etaMinDays(5).etaMaxDays(12).enabled(true).build();
        when(zoneRepository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.of(z));
        return z;
    }

    private JsonNode json(String raw) throws IOException {
        return mapper.readTree(raw);
    }

    // ── Cobertura ────────────────────────────────────────────────────────────────────────────────

    @Test
    void unPaisSinZonaEnLaTablaNoSePuedeEnviar() {
        when(zoneRepository.findByCountryCodeIgnoreCase("XX")).thenReturn(Optional.empty());

        assertThat(service.isSupported("XX")).isFalse();
    }

    @Test
    void unaZonaDesactivadaDejaDeSerDestinoValido() {
        // El admin apaga un país cuando el transportista deja de cubrirlo: seguir aceptando pedidos ahí
        // significaría cobrar un envío que nadie puede hacer.
        CainiaoZoneEntity z = CainiaoZoneEntity.builder().countryCode("RU").countryName("Rusia").zone("EU")
                .baseCents(500).perKgCents(1000).etaMinDays(5).etaMaxDays(12).enabled(false).build();
        when(zoneRepository.findByCountryCodeIgnoreCase("RU")).thenReturn(Optional.of(z));

        assertThat(service.isSupported("RU")).isFalse();
    }

    @Test
    void unPaisNuloOEnBlancoNiSiquieraSeConsultaEnLaTabla() {
        assertThat(service.isSupported(null)).isFalse();
        assertThat(service.isSupported("   ")).isFalse();
        verify(zoneRepository, never()).findByCountryCodeIgnoreCase(anyString());
    }

    @Test
    void elCodigoDePaisSeBuscaSinEspaciosSobrantes() {
        zonaEspana();

        assertThat(service.isSupported("  ES  ")).isTrue();
    }

    @Test
    void losPaisesCubiertosSonSoloLosHabilitados() {
        CainiaoZoneEntity es = CainiaoZoneEntity.builder().countryCode("ES").countryName("España").zone("EU")
                .baseCents(1).perKgCents(1).etaMinDays(1).etaMaxDays(2).enabled(true).build();
        when(zoneRepository.findByEnabledTrueOrderByCountryNameAsc()).thenReturn(List.of(es));

        List<SupportedCountry> countries = service.supportedCountries();

        assertThat(countries).containsExactly(new SupportedCountry("ES", "España"));
    }

    // ── Cotización ───────────────────────────────────────────────────────────────────────────────

    @Test
    void unDestinoNoCubiertoNoSeCotizaYSeMarcaComoNoSoportado() {
        when(zoneRepository.findByCountryCodeIgnoreCase("XX")).thenReturn(Optional.empty());

        ShippingQuote quote = service.quote("XX", ParcelSpec.ofWeight(500));

        assertThat(quote.supported()).isFalse();
        assertThat(quote.amountUsdCents()).isZero();
    }

    @Test
    void sinYunExpressSeTarifaConLaTablaDeZonas() {
        zonaEspana();

        ShippingQuote quote = service.quote("ES", ParcelSpec.ofWeight(250));

        // 500 de base + 1000 €/kg × 0,25 kg = 750 céntimos USD
        assertThat(quote.amountUsdCents()).isEqualTo(750);
        assertThat(quote.etaMinDays()).isEqualTo(5);
        assertThat(quote.etaMaxDays()).isEqualTo(12);
        assertThat(quote.zone()).isEqualTo("EU");
        assertThat(quote.carrier()).isEqualTo("Standard Shipping");
    }

    @Test
    void unBultoLigerisimoSeCobraComoSiPesaraCienGramos() {
        // Sin este suelo un bulto de 10 g se cotizaría casi a coste cero y el envío lo pagaría el margen.
        zonaEspana();

        assertThat(service.quote("ES", ParcelSpec.ofWeight(10)).amountUsdCents()).isEqualTo(600);
    }

    @Test
    void conYunExpressActivoSeCotizaSuTarifaConvertidaACentimosUsd() throws IOException {
        zonaEspana();
        conYunExpressOperativo();
        when(client.get(eq(PATH_PRICE_TRIAL), anyMap(), any(Duration.class))).thenReturn(json("""
                {"success":true,"result":[
                  {"product_code":"BPA","product_name":"Small packet","calculate_amount":29,
                   "currency":"RMB","interval_day":"7-15"}]}"""));
        when(currencyRateService.toUsd(any(BigDecimal.class), eq("CNY"))).thenReturn(new BigDecimal("4.132"));

        ShippingQuote quote = service.quote("ES", ParcelSpec.ofWeight(250));

        // 4,132 USD → 413 céntimos (redondeo al alza en el medio punto)
        assertThat(quote.amountUsdCents()).isEqualTo(413);
    }

    @Test
    void devuelveTodasLasFormasDeEnvioParaQueElClienteElija() throws IOException {
        zonaEspana();
        conYunExpressOperativo();
        // Lo que cotiza España de verdad: la línea de ropa es la más barata y llega antes, el postal es
        // barato pero no admite IOSS, y el -AMZ exige el número de Amazon.
        when(client.get(eq(PATH_PRICE_TRIAL), anyMap(), any(Duration.class))).thenReturn(json("""
                {"success":true,"result":[
                  {"product_code":"THPHR","product_name":"Global line","calculate_amount":57.5,
                   "currency":"RMB","interval_day":"6-10"},
                  {"product_code":"CNDWA","product_name":"China Post","calculate_amount":57,
                   "currency":"RMB","interval_day":"9-30"},
                  {"product_code":"FZZXR","product_name":"Apparel line","calculate_amount":55,
                   "currency":"RMB","interval_day":"5-8"},
                  {"product_code":"FZZXR-AMZ","product_name":"Apparel AMZ","calculate_amount":55,
                   "currency":"RMB","interval_day":"5-8"}]}"""));
        when(customsValuation.carrierPrepaysVatFor("ES")).thenReturn(true);
        when(currencyRateService.toUsd(any(BigDecimal.class), eq("CNY"))).thenAnswer(inv -> inv
                .getArgument(0, BigDecimal.class).divide(new BigDecimal("7"), 4, java.math.RoundingMode.HALF_UP));

        ShippingQuote quote = service.quote("ES", ParcelSpec.ofWeight(250));

        // Solo las utilizables, de más barata a más cara: fuera el postal y el de Amazon.
        assertThat(quote.options()).extracting(ShippingOption::code).containsExactly("FZZXR", "THPHR");
        // Y el plazo es el DEL CANAL, no el de la tabla de zonas: es el envío que se está cobrando.
        assertThat(quote.options().getFirst().etaMinDays()).isEqualTo(5);
        assertThat(quote.options().getFirst().etaMaxDays()).isEqualTo(8);
        // Sin elección, se cobra la primera.
        assertThat(quote.amountUsdCents()).isEqualTo(quote.options().getFirst().amountUsdCents());
    }

    @Test
    void sinTarifaDelTransportistaNoHayNadaQueElegir() throws IOException {
        zonaEspana();
        conYunExpressOperativo();
        when(client.get(eq(PATH_PRICE_TRIAL), anyMap(), any(Duration.class)))
                .thenReturn(json("{\"success\":true,\"result\":[]}"));

        // Se sigue vendiendo con la tarifa de la tabla de zonas, pero sin opciones que ofrecer.
        ShippingQuote quote = service.quote("ES", ParcelSpec.ofWeight(250));

        assertThat(quote.supported()).isTrue();
        assertThat(quote.options()).isEmpty();
    }

    @Test
    void siYunExpressNoRecomiendaCanalSeSigueVendiendoConLaTarifaLocal() throws IOException {
        zonaEspana();
        conYunExpressOperativo();
        when(client.get(eq(PATH_PRICE_TRIAL), anyMap(), any(Duration.class))).thenReturn(json("""
                {"success":false,"code":"02030008","msg":"no product"}"""));

        assertThat(service.quote("ES", ParcelSpec.ofWeight(250)).amountUsdCents()).isEqualTo(750);
    }

    @Test
    void unFalloDeRedCotizandoNoPuedeTumbarElCheckout() {
        zonaEspana();
        conYunExpressOperativo();
        when(client.get(eq(PATH_PRICE_TRIAL), anyMap(), any(Duration.class)))
                .thenThrow(new IllegalStateException("gateway timeout"));

        ShippingQuote quote = service.quote("ES", ParcelSpec.ofWeight(250));

        assertThat(quote.supported()).isTrue();
        assertThat(quote.amountUsdCents()).isEqualTo(750);
    }

    @Test
    void siNoSeSabeConvertirLaDivisaDelCarrierSeUsaLaTablaEnVezDeUnImporteInventado() throws IOException {
        zonaEspana();
        conYunExpressOperativo();
        when(client.get(eq(PATH_PRICE_TRIAL), anyMap(), any(Duration.class))).thenReturn(json("""
                {"success":true,"result":[
                  {"product_code":"BPA","calculate_amount":29,"currency":"XYZ","interval_day":"7-15"}]}"""));
        when(currencyRateService.toUsd(any(BigDecimal.class), anyString())).thenReturn(null);

        assertThat(service.quote("ES", ParcelSpec.ofWeight(250)).amountUsdCents()).isEqualTo(750);
    }

    @Test
    void seCotizaElCanalMasBaratoDeLosQueOfreceElDestino() throws IOException {
        when(client.get(eq(PATH_PRICE_TRIAL), anyMap(), any(Duration.class))).thenReturn(json("""
                {"success":true,"result":[
                  {"product_code":"CARO","calculate_amount":50,"currency":"RMB","interval_day":"3-8"},
                  {"product_code":"BARATO","calculate_amount":11,"currency":"RMB","interval_day":"7-15"}]}"""));

        RateOption best = service.cheapestRate("ES", ParcelSpec.ofWeight(500), 500);

        assertThat(best).isNotNull();
        assertThat(best.productCode()).isEqualTo("BARATO");
    }

    @Test
    void laSimulacionDeTarifaViajaConPesoEnKgMedidasEnCmYElGrupoContratado() throws IOException {
        ReflectionTestUtils.setField(service, "productGroupCode", "  EM  ");
        when(client.get(eq(PATH_PRICE_TRIAL), anyMap(), any(Duration.class)))
                .thenReturn(json("{\"success\":true,\"result\":[]}"));

        service.cheapestRate("ES", new ParcelSpec(1500, 320, 240, 50, true), 1500);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).get(eq(PATH_PRICE_TRIAL), captor.capture(), any(Duration.class));
        Map<String, String> query = captor.getValue();
        assertThat(query).containsEntry("country_code", "ES").containsEntry("weight", "1.500")
                .containsEntry("weight_unit", "KG")
                // "E" = 带电 (con batería): cambia de canal y de tarifa, y omitirlo hace que el
                // transportista rechace el bulto en almacén.
                .containsEntry("package_type", "E").containsEntry("length", "32.0").containsEntry("width", "24.0")
                .containsEntry("height", "5.0").containsEntry("size_unit", "CM")
                .containsEntry("product_group_code", "EM");
    }

    @Test
    void unBultoSinMedidasNoDeclaraDimensionesEnLaSimulacion() throws IOException {
        when(client.get(eq(PATH_PRICE_TRIAL), anyMap(), any(Duration.class)))
                .thenReturn(json("{\"success\":true,\"result\":[]}"));

        service.cheapestRate("ES", ParcelSpec.ofWeight(400), 400);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).get(eq(PATH_PRICE_TRIAL), captor.capture(), any(Duration.class));
        assertThat(captor.getValue()).doesNotContainKey("length").doesNotContainKey("size_unit")
                .doesNotContainKey("product_group_code");
    }

    @Test
    void sinCanalesEnLaRespuestaNoHayTarifaQueElegir() throws IOException {
        when(client.get(eq(PATH_PRICE_TRIAL), anyMap(), any(Duration.class)))
                .thenReturn(json("{\"success\":true,\"result\":[]}"));

        assertThat(service.cheapestRate("ES", ParcelSpec.ofWeight(400), 400)).isNull();
    }

    // ── Canales, etiqueta y anulación ────────────────────────────────────────────────────────────

    @Test
    void losCanalesContratadosSeLeenTantoDeDetailComoDeResultList() throws IOException {
        when(client.get(eq("/v1/basic-data/products/getlist"), anyMap())).thenReturn(json("""
                {"success":true,"detail":[{"product_code":"BPA","product_name":"Small packet"}]}"""));
        assertThat(service.logisticsProducts()).containsExactly(new SupportedCountry("BPA", "Small packet"));

        when(client.get(eq("/v1/basic-data/products/getlist"), anyMap())).thenReturn(json("""
                {"success":true,"result":{"list":[{"product_code":"EM","product_name":"Economy"}]}}"""));
        assertThat(service.logisticsProducts()).containsExactly(new SupportedCountry("EM", "Economy"));
    }

    @Test
    void laEtiquetaSeExtraeDeLaPrimeraEntradaCuandoLlegaComoLista() throws IOException {
        when(client.get(eq("/v1/order/label/get"), anyMap())).thenReturn(json("""
                {"success":true,"result":[{"label_string":"JVBERi0xLjQK"},{"label_string":"otra"}]}"""));

        assertThat(service.labelFor("NX-1")).isEqualTo("JVBERi0xLjQK");
    }

    @Test
    void siElCarrierNoDevuelveEtiquetaSeAvisaConSuCodigoDeError() throws IOException {
        when(client.get(eq("/v1/order/label/get"), anyMap())).thenReturn(json("""
                {"success":false,"code":"02030014","msg":"not ready"}"""));

        assertThatThrownBy(() -> service.labelFor("NX-1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("02030014");
    }

    @Test
    void laAnulacionDeGuiaDevuelveSiElCarrierLaAcepto() throws IOException {
        when(client.post(eq("/v1/order/cancel"), any(Object.class))).thenReturn(json("{\"success\":true}"));
        assertThat(service.cancelShipment("YT1")).isTrue();

        when(client.post(eq("/v1/order/cancel"), any(Object.class)))
                .thenReturn(json("{\"success\":false,\"code\":\"02030001\",\"msg\":\"already in warehouse\"}"));
        assertThat(service.cancelShipment("YT1")).isFalse();
    }

    // ── Impuestos ────────────────────────────────────────────────────────────────────────────────

    @Test
    void laReglaAduaneraDelPaisMandaSobreElModoFiscalPorDefecto() {
        when(customsValuation.taxModeFor("GB")).thenReturn(TaxMode.DDU);

        assertThat(service.taxModeFor("GB")).isEqualTo(TaxMode.DDU);
    }

    @Test
    void unDestinoSinReglaAduaneraCaeAlModoFiscalConfigurado() {
        ReflectionTestUtils.setField(service, "defaultTaxMode", "DDU");
        when(customsValuation.taxModeFor("MX")).thenReturn(null);

        assertThat(service.taxModeFor("MX")).isEqualTo(TaxMode.DDU);
    }

    @Test
    void unIossVacioEsAusenciaDeIossYNoUnaCadenaVacia() {
        // Mandar un IOSS en blanco hace que la aduana rechace la liquidación; tiene que ser null.
        assertThat(service.iossNumberOrNull()).isNull();

        ReflectionTestUtils.setField(service, "iossNumber", "  IM2760000742  ");
        assertThat(service.iossNumberOrNull()).isEqualTo("IM2760000742");
    }
}
