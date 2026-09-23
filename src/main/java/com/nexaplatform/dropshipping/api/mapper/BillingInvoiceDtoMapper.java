package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.BillingInvoiceDtoOut;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase.InvoiceView;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Proyecta el historial de facturas del plan (lo que devuelve Stripe) al contrato que consume el perfil.
 *
 * <p>Además del importe en crudo, entrega el importe YA FORMATEADO. Antes lo componía el navegador con
 * {@code (total / 100) + código de divisa} y eso rompía la norma que sostiene toda la plataforma: los
 * importes se calculan y se formatean SIEMPRE aquí, y el cliente sólo pinta la cadena. La norma existe
 * porque el defecto que más se repite es «veo X y me cobran Y», y una factura es precisamente el
 * justificante de un cargo ya hecho: el número tiene que cuadrar con el extracto del banco.
 */
@Component
@RequiredArgsConstructor
public class BillingInvoiceDtoMapper {

    private final CurrencyRateService currencyRateService;

    public List<BillingInvoiceDtoOut> toDtoOutList(List<InvoiceView> invoices) {
        if (invoices == null) {
            return List.of();
        }
        return invoices.stream().map(this::toDtoOut).toList();
    }

    public BillingInvoiceDtoOut toDtoOut(InvoiceView invoice) {
        return BillingInvoiceDtoOut.builder().number(invoice.number()).total(invoice.total())
                .currency(invoice.currency()).totalFormatted(totalFormatted(invoice.total(), invoice.currency()))
                .status(invoice.status()).created(invoice.created()).pdfUrl(invoice.pdfUrl())
                .hostedUrl(invoice.hostedUrl()).build();
    }

    /**
     * Convierte el importe de Stripe —que viaja en la UNIDAD MÍNIMA de su divisa— a unidades enteras y lo
     * formatea en LA DIVISA EN QUE SE EMITIÓ la factura.
     *
     * <p>Cuántos decimales tiene esa unidad mínima lo dice la norma ISO 4217, no una constante: son dos en
     * el euro y el dólar, pero CERO en el yen y el won —donde Stripe manda ya unidades enteras— y tres en
     * el dinar kuwaití. Si alguien vuelve a fijar aquí un «entre cien», una factura de 5.000 ¥ se anunciará
     * como 50 ¥: cien veces más barata de lo que se cobró.
     *
     * <p>Y NO se convierte a la divisa activa del usuario a propósito. La factura ya se cobró en la suya;
     * pasarla por la tasa de cambio de hoy haría que el justificante de un cobro pasado cambiara de número
     * cada vez que se sincronizan las tasas y no cuadrara nunca con el banco.
     */
    private String totalFormatted(Long totalMinorUnits, String currency) {
        if (totalMinorUnits == null || currency == null || currency.isBlank()) {
            return null;
        }
        BigDecimal amount = BigDecimal.valueOf(totalMinorUnits).movePointLeft(currencyRateService.decimalsOf(currency));
        return currencyRateService.formatDisplay(amount, currency);
    }
}
