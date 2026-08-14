package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.EuComplianceService;
import com.nexaplatform.dropshipping.application.service.EuComplianceService.ResponsiblePersonView;
import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La factura ES el documento de acompañamiento del art. 16.3 del Reglamento (UE) 2023/988.
 *
 * <p>La norma admite que el operador económico establecido en la Unión figure «en el producto o en su
 * envase, en el paquete o en un documento de acompañamiento». Como el embalaje lo prepara el proveedor en
 * origen y no se controla, la factura es la única de esas cuatro vías que está en nuestra mano: si el
 * bloque desapareciera de aquí, el producto se estaría introduciendo en el mercado sin cumplir.
 *
 * <p>Se comprueba sobre el HTML RENDERIZADO con el motor Thymeleaf real y la plantilla real, no sobre el
 * PDF ni sobre el modelo: lo que puede romperse en silencio es justamente la plantilla —una clave mal
 * escrita, un {@code th:if} que no evalúa—, y un test que solo verificara que el PDF «se genera» daría
 * verde con el bloque ausente.
 */
class InvoiceEuResponsibleRenderTest {

    private InvoiceService service;
    private EuComplianceService compliance;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        CurrencyRateService currency = mock(CurrencyRateService.class);
        lenient().when(currency.usdTo(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.<BigDecimal>getArgument(0));
        lenient().when(currency.decimalsOf(anyString())).thenReturn(2);
        lenient().when(currency.formatDisplay(any(), anyString()))
                .thenAnswer(i -> i.getArgument(0) + " " + i.getArgument(1));
        compliance = mock(EuComplianceService.class);
        service = new InvoiceService(engine, currency, compliance, new OrderAmounts(currency),
                mock(PaymentJpaRepositoryAdapter.class), mock(ProductRepository.class),
                mock(ProductVariantRepository.class), mock(ObjectStorageService.class));
        ReflectionTestUtils.setField(service, "issuerLegalName", "NX036 Dropshipping S.L.");
        ReflectionTestUtils.setField(service, "issuerTaxId", "B12345678");
        ReflectionTestUtils.setField(service, "issuerEmail", "facturacion@example.com");
    }

    private static ResponsiblePersonView operador() {
        return new ResponsiblePersonView("Jesus Enrique Finol Finol", "Calle Castelví 7, 1D", "50004",
                "Zaragoza", "Zaragoza", "ES", "jfinol02@gmail.com", null, "IMPORTER", "Importador",
                true, true);
    }

    private static Order pedido() {
        return Order.builder().orderNumber("NX-100").currency("EUR").shippingCents(500).taxCents(210)
                .items(List.of(OrderItem.builder().unitPriceCents(4000).quantity(2)
                        .titleSnapshot("Blazer de mujer").skuSnapshot("SKU-1").build()))
                .shippingCountry("ES").placedAt(Instant.parse("2026-07-15T10:30:00Z")).build();
    }

    private String facturaEn(String locale) {
        return service.renderHtml(pedido(), locale, "https://pagos.example.com/f/1");
    }

    @Test
    @DisplayName("con operador publicable, la factura imprime nombre, dirección completa y correo")
    void imprimeLosDatosDelArticulo163() {
        when(compliance.publishedResponsible(anyString())).thenReturn(Optional.of(operador()));

        String html = facturaEn("es");

        assertThat(html).contains("Jesus Enrique Finol Finol");
        // La dirección tiene que salir COMPLETA: sin código postal no permite contactar, que es lo que la
        // norma pide de unos "datos de contacto".
        assertThat(html).contains("Calle Castelví 7, 1D, 50004 Zaragoza (ES)");
        assertThat(html).contains("jfinol02@gmail.com");
        assertThat(html).contains("Operador económico responsable en la UE");
        assertThat(html).contains("Importador");
        assertThat(html).contains("Reglamento (UE) 2023/988");
    }

    @Test
    @DisplayName("sin operador publicable, la factura sale igual pero SIN el bloque")
    void sinOperadorLaFacturaNoSeRompe() {
        // Es el estado de un entorno recién levantado. La factura tiene que emitirse igualmente: dejar al
        // cliente sin su documento por una configuración incompleta sería peor que la falta en sí.
        when(compliance.publishedResponsible(anyString())).thenReturn(Optional.empty());

        String html = facturaEn("es");

        assertThat(html).contains("NX-100");
        assertThat(html).doesNotContain("Operador económico responsable en la UE");
        assertThat(html).doesNotContain("Reglamento (UE) 2023/988");
    }

    @ParameterizedTest(name = "en {0} el bloque se titula «{1}»")
    @CsvSource({"es,Operador económico responsable en la UE", "en,Responsible economic operator in the EU",
            "pt,Operador económico responsável na UE", "fr,Opérateur économique responsable dans",
            "de,Verantwortlicher Wirtschaftsakteur in der EU",
            "it,Operatore economico responsabile nell",
            "nl,Verantwoordelijke marktdeelnemer in de EU"})
    @DisplayName("el bloque se imprime en el idioma de la factura")
    void tituloTraducidoEnLaFactura(String lang, String esperado) {
        // La factura se emite en el idioma con el que el comprador navega: un bloque legal en otra lengua
        // no cumple su propósito, que es que pueda identificar y contactar al responsable.
        //
        // Francés e italiano se comprueban hasta antes del apóstrofo (l'UE, nell'UE) porque Thymeleaf lo
        // escapa a &#39; en la salida: buscar el literal con apóstrofo fallaría por el escapado, no porque
        // el texto no esté.
        when(compliance.publishedResponsible(anyString())).thenReturn(Optional.of(operador()));

        assertThat(facturaEn(lang)).contains(esperado);
    }

    @Test
    @DisplayName("la dirección de una línea junta calle, código postal, ciudad y país")
    void direccionEnUnaLinea() {
        assertThat(operador().formattedAddress()).isEqualTo("Calle Castelví 7, 1D, 50004 Zaragoza (ES)");
    }
}
