package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.BillingInvoiceDtoOut;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase.InvoiceView;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CurrencyRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Historial de facturas del plan: el importe que se enseña es el justificante de un cargo YA HECHO a la
 * tarjeta del cliente, así que tiene que coincidir con el extracto bancario al céntimo.
 *
 * <p>El navegador lo componía por su cuenta con {@code (total / 100)} más el código de divisa. Dos
 * defectos en una línea: rompía la norma de la plataforma —todo importe se calcula y se formatea en el
 * BACKEND, el cliente sólo pinta la cadena— y, en las divisas que NO tienen céntimos (yen, won), Stripe
 * manda unidades enteras, de modo que dividir entre cien enseñaba la factura CIEN VECES MÁS BARATA:
 * 5.000 ¥ cobrados se anunciaban como «50,00 JPY».
 *
 * <p>Y el importe se formatea en la divisa EN QUE SE EMITIÓ la factura, nunca en la que el usuario tenga
 * activa hoy: convertirlo a la divisa de hoy cambiaría el número del justificante de un cobro pasado
 * cada vez que cambian las tasas de cambio.
 */
class BillingInvoiceDtoMapperTest {

    private BillingInvoiceDtoMapper mapper;

    /**
     * Se usa el servicio de divisas REAL (sólo con el repositorio simulado) en lugar de una simulación:
     * lo que se está protegiendo es justamente cuántos decimales tiene cada divisa y cómo queda la
     * cadena final, y con un doble de prueba esas dos cosas las decidiría la propia prueba.
     */
    @BeforeEach
    void prepararDivisas() {
        CurrencyRateRepository repositorio = mock(CurrencyRateRepository.class);
        when(repositorio.findAll()).thenReturn(List.of(divisa("EUR", "Euro", "€", "es-ES", "0.92"),
                divisa("USD", "US Dollar", "$", "en-US", "1.00"), divisa("JPY", "Japanese Yen", "¥", "ja-JP", "156.40"),
                divisa("KRW", "South Korean Won", "₩", "ko-KR", "1340.00")));
        mapper = new BillingInvoiceDtoMapper(new CurrencyRateService(repositorio));
    }

    @Test
    @DisplayName("una factura en euros se pinta con sus dos decimales y el símbolo de la divisa")
    void unaFacturaEnEurosSePintaConSusDosDecimales() {
        BillingInvoiceDtoOut dto = mapper.toDtoOut(factura(9900L, "eur"));

        assertThat(normalizar(dto.getTotalFormatted())).isEqualTo("99,00 €");
    }

    @Test
    @DisplayName("una factura en dólares se pinta con la convención de su divisa")
    void unaFacturaEnDolaresSePintaConLaConvencionDeSuDivisa() {
        BillingInvoiceDtoOut dto = mapper.toDtoOut(factura(1234L, "usd"));

        assertThat(normalizar(dto.getTotalFormatted())).isEqualTo("$12.34");
    }

    @Test
    @DisplayName("una factura en yenes NO se divide entre cien: el yen no tiene céntimos")
    void unaFacturaEnYenesNoSeDivideEntreCien() {
        // Stripe manda 5000 para un cobro de 5.000 ¥, no de 50 ¥: en las divisas sin decimales la unidad
        // mínima ES la unidad. Éste es el cálculo que hacía el navegador y que enseñaba «50,00 JPY».
        BillingInvoiceDtoOut dto = mapper.toDtoOut(factura(5000L, "jpy"));

        assertThat(normalizar(dto.getTotalFormatted())).isEqualTo("￥5,000");
        assertThat(dto.getTotalFormatted()).doesNotContain("50,00").doesNotContain("50.00");
    }

    @Test
    @DisplayName("una factura en wones tampoco se divide entre cien")
    void unaFacturaEnWonesTampocoSeDivideEntreCien() {
        BillingInvoiceDtoOut dto = mapper.toDtoOut(factura(129000L, "krw"));

        assertThat(normalizar(dto.getTotalFormatted())).isEqualTo("₩129,000");
    }

    @Test
    @DisplayName("los decimales los manda la norma ISO 4217, no la tabla de tasas de cambio")
    void losDecimalesLosMandaLaNormaIsoNoLaTablaDeTasas() {
        // Divisa sin fila en currency_rate (tasa aún no sincronizada). Aunque no se conozca su locale, el
        // yen sigue sin tener céntimos: el número que se enseña tiene que ser 5.000, jamás 50.
        BillingInvoiceDtoOut dto = mapper.toDtoOut(factura(5000L, "vnd"));

        assertThat(dto.getTotalFormatted()).contains("5,000").doesNotContain("50.00");
    }

    @Test
    @DisplayName("una factura de importe cero se formatea como cero, no como ausencia de dato")
    void unaFacturaDeImporteCeroSeFormateaComoCero() {
        // Una factura a cero (cupón del 100 %, prorrateo que se anula) es un justificante válido. Si se
        // confundiera con «sin importe» el cliente vería un guión donde hubo un documento contable.
        BillingInvoiceDtoOut dto = mapper.toDtoOut(factura(0L, "eur"));

        assertThat(normalizar(dto.getTotalFormatted())).isEqualTo("0,00 €");
    }

    @Test
    @DisplayName("una factura sin importe no se inventa un cero")
    void unaFacturaSinImporteNoSeInventaUnCero() {
        // Sin campo formateado el navegador pinta «—». Devolver "0,00 €" diría que no se cobró nada.
        BillingInvoiceDtoOut dto = mapper.toDtoOut(factura(null, "eur"));

        assertThat(dto.getTotalFormatted()).isNull();
    }

    @Test
    @DisplayName("una factura sin divisa no se formatea a ciegas")
    void unaFacturaSinDivisaNoSeFormateaACiegas() {
        // Sin divisa no hay importe que enseñar: un número pelado sería «cuánto» sin decir «de qué».
        assertThat(mapper.toDtoOut(factura(9900L, null)).getTotalFormatted()).isNull();
        assertThat(mapper.toDtoOut(factura(9900L, "  ")).getTotalFormatted()).isNull();
    }

    @Test
    @DisplayName("el resto de los datos de la factura viajan intactos")
    void elRestoDeLosDatosDeLaFacturaViajanIntactos() {
        // El importe en unidades mínimas y su divisa siguen saliendo: los consumen las integraciones por
        // API, que sí hacen sus propias cuentas. Lo que no puede hacer cuentas es la pantalla.
        InvoiceView origen = new InvoiceView("F-2026-0001", 9900L, "eur", "paid", 1750000000L,
                "https://stripe.test/f.pdf", "https://stripe.test/f");

        BillingInvoiceDtoOut dto = mapper.toDtoOut(origen);

        assertThat(dto.getNumber()).isEqualTo("F-2026-0001");
        assertThat(dto.getTotal()).isEqualTo(9900L);
        assertThat(dto.getCurrency()).isEqualTo("eur");
        assertThat(dto.getStatus()).isEqualTo("paid");
        assertThat(dto.getCreated()).isEqualTo(1750000000L);
        assertThat(dto.getPdfUrl()).isEqualTo("https://stripe.test/f.pdf");
        assertThat(dto.getHostedUrl()).isEqualTo("https://stripe.test/f");
    }

    @Test
    @DisplayName("la lista se proyecta entera y sin facturas no devuelve nulo")
    void laListaSeProyectaEnteraYSinFacturasNoDevuelveNulo() {
        // El controlador entrega el resultado tal cual: un nulo aquí sería un 500 en el perfil.
        List<BillingInvoiceDtoOut> dtos = mapper.toDtoOutList(List.of(factura(9900L, "eur"), factura(5000L, "jpy")));

        assertThat(dtos).hasSize(2);
        assertThat(normalizar(dtos.get(0).getTotalFormatted())).isEqualTo("99,00 €");
        assertThat(normalizar(dtos.get(1).getTotalFormatted())).isEqualTo("￥5,000");
        assertThat(mapper.toDtoOutList(null)).isEmpty();
    }

    private static InvoiceView factura(Long total, String currency) {
        return new InvoiceView("F-2026-0001", total, currency, "paid", 1750000000L, null, null);
    }

    private static CurrencyRateEntity divisa(String code, String name, String symbol, String locale, String rate) {
        return CurrencyRateEntity.builder().code(code).name(name).symbol(symbol).locale(locale)
                .rateVsUsd(new BigDecimal(rate)).active(true).build();
    }

    /**
     * El formateador de la JDK separa el número del símbolo con un espacio DURO (U+00A0) para que no se
     * parta la línea entre «99,00» y «€». Se cambia por un espacio normal sólo para poder escribir el
     * valor esperado como texto corriente en la prueba.
     */
    private static String normalizar(String formateado) {
        return formateado == null ? null : formateado.replace('\u00A0', ' ').replace('\u202F', ' ');
    }
}
