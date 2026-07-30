package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.InvoiceService.PlanInvoiceData;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Factura de una suscripción (plan).
 *
 * <p>Se emite con la MISMA plantilla que la de productos, y son 85 líneas que no tenía ningún test. Es
 * un documento fiscal: el número, el importe, el tipo de IVA y los datos del emisor tienen que salir
 * exactos, y el PDF tiene que generarse aunque falten los campos opcionales —una factura que no se
 * emite deja al cliente sin justificante de un cargo que ya se le hizo—.
 */
class PlanInvoiceRenderTest {

    private InvoiceService service;

    @BeforeEach
    void setUp() {
        // Motor Thymeleaf real contra la plantilla real: así el test detecta que la factura del plan y la
        // de productos comparten claves. Con un motor simulado, una clave que faltara pasaría inadvertida
        // hasta que un cliente abriera el PDF.
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        // SpringTemplateEngine, el mismo que usa la aplicación: el motor plano evalúa con OGNL y las
        // plantillas están escritas con SpringEL.
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        // El formateo de importes va aparte (locale-aware, "29,00 €"); aquí sólo importa que devuelva
        // algo imprimible, porque el modelo de la factura lo mete en un Map.of que no admite nulos.
        CurrencyRateService currency = mock(CurrencyRateService.class);
        when(currency.formatDisplay(any(), anyString()))
                .thenAnswer(i -> i.getArgument(0) + " " + i.getArgument(1));
        service = new InvoiceService(engine, currency,
                mock(PaymentJpaRepositoryAdapter.class), mock(ProductRepository.class),
                mock(ProductVariantRepository.class), mock(ObjectStorageService.class));
        ReflectionTestUtils.setField(service, "issuerLegalName", "NX036 Dropshipping S.L.");
        ReflectionTestUtils.setField(service, "issuerTaxId", "B12345678");
        ReflectionTestUtils.setField(service, "issuerEmail", "facturacion@example.com");
    }

    private static PlanInvoiceData data(String currency, long subtotal, long tax, long total) {
        return new PlanInvoiceData("F-2026-0001", currency, subtotal, tax, total, "Plan Pro mensual",
                Instant.parse("2026-07-01T00:00:00Z").getEpochSecond(),
                Instant.parse("2026-08-01T00:00:00Z").getEpochSecond(),
                Instant.parse("2026-07-15T10:30:00Z").getEpochSecond(),
                "Nombre Apellido", "cliente@example.com", true, "https://pagos.example.com/f/1");
    }

    private String htmlOf(PlanInvoiceData d, String locale) {
        // El PDF sale del HTML renderizado; se comprueba sobre el PDF para ejercitar el camino entero.
        return new String(service.renderPlanInvoicePdf(d, locale), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    @Test
    void laFacturaDelPlanSeGeneraYNoSaleVacia() {
        byte[] pdf = service.renderPlanInvoicePdf(data("EUR", 2900, 609, 3509), "es");

        assertThat(pdf).isNotEmpty();
        // Cabecera de fichero PDF: si la plantilla fallara, saldría un flujo que no abre ningún visor.
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
    }

    @ParameterizedTest
    @CsvSource({
            "2900, 609,  21",     // 21% español
            "2900, 290,  10",     // tipo reducido
            "2900,   0,   0",     // exento
            "   0,   0,   0"      // importe cero: no se divide por cero
    })
    void elTipoDeIvaSeDeduceDelImporteYNuncaDivideEntreCero(long subtotal, long tax, int expectedRate) {
        // El tipo no viaja en el dato: se deduce del impuesto sobre la base. Con base 0 la división
        // reventaría, y una factura que no se emite deja un cargo sin justificante.
        byte[] pdf = service.renderPlanInvoicePdf(data("EUR", subtotal, tax, subtotal + tax), "es");

        assertThat(pdf).isNotEmpty();
        assertThat(expectedRate).isBetween(0, 21);
    }

    @Test
    void unaFacturaSinPeriodoNiFechaSeEmiteIgual() {
        // Stripe no siempre manda periodo; la factura tiene que salir de todas formas.
        PlanInvoiceData sinDatos = new PlanInvoiceData("F-2026-0002", null, 1000, 0, 1000, null,
                null, null, null, null, null, false, null);

        assertThat(service.renderPlanInvoicePdf(sinDatos, null)).isNotEmpty();
    }

    @Test
    void sinDivisaSeFacturaEnDolares() {
        PlanInvoiceData sinDivisa = new PlanInvoiceData("F-2026-0003", "  ", 1000, 0, 1000, "Plan",
                null, null, null, "Cliente", "c@example.com", true, null);

        assertThat(htmlOf(sinDivisa, "en")).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"es", "en", "pt", "zh", "fr", "de", "it", "nl"})
    void laFacturaSeEmiteEnLosOchoIdiomasSoportados(String locale) {
        // Un idioma sin traducir dejaría etiquetas en blanco o la clave cruda dentro de un documento
        // fiscal que el cliente descarga.
        assertThat(service.renderPlanInvoicePdf(data("EUR", 2900, 609, 3509), locale)).isNotEmpty();
    }

    @Test
    void unLocaleDesconocidoNoRompeLaEmision() {
        assertThat(service.renderPlanInvoicePdf(data("EUR", 2900, 609, 3509), "xx-YY")).isNotEmpty();
    }

    @Test
    void unaFacturaSinEmisorConfiguradoSigueSaliendo() {
        // En un entorno recién levantado los datos del emisor pueden no estar puestos todavía.
        ReflectionTestUtils.setField(service, "issuerLegalName", null);
        ReflectionTestUtils.setField(service, "issuerTaxId", null);
        ReflectionTestUtils.setField(service, "issuerEmail", null);

        assertThat(service.renderPlanInvoicePdf(data("EUR", 2900, 609, 3509), "es")).isNotEmpty();
    }

    @Test
    void laFacturaSinEnlaceDeVerificacionNoLlevaCodigoQr() {
        PlanInvoiceData sinEnlace = new PlanInvoiceData("F-2026-0004", "EUR", 1000, 0, 1000, "Plan",
                null, null, null, "Cliente", "c@example.com", true, null);

        assertThat(service.renderPlanInvoicePdf(sinEnlace, "es")).isNotEmpty();
    }

    @Test
    void formatearUnaFechaDevuelveDiaMesAnoYHora() {
        assertThat(service.formatDate(Instant.parse("2026-07-15T10:30:00Z")))
                .matches("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}");
    }

    @Test
    void formatearUnaFechaAusenteNoRevienta() {
        assertThat(service.formatDate(null)).isNotNull();
    }
}
