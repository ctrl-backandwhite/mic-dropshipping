package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderEmailServiceTest {

    @Mock
    EmailQueueService emailQueue;
    @Mock
    InvoiceService invoiceService;
    @InjectMocks
    OrderEmailService service;

    private static Order order(String number, String trackingNumber, String carrier, String currency) {
        return Order.builder().id(UUID.randomUUID()).orderNumber(number).trackingNumber(trackingNumber)
                .carrier(carrier).currency(currency).build();
    }

    /* ---------------- paymentConfirmed ---------------- */

    @Test
    void paymentConfirmed_enqueuesInvoiceWithModelVarsAndPaymentMethod() {
        Order o = order("NX-100", null, null, "USD");
        Map<String, Object> model = new HashMap<>();
        model.put("subject", "Invoice NX-100");
        when(invoiceService.model(eq(o), eq("es"), any(), eq("EUR"))).thenReturn(model);

        service.paymentConfirmed(o, "buyer@x.com", "es", "PayPal", "EUR");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> varsCap = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(eq("buyer@x.com"), isNull(), eq("Invoice NX-100"), eq("emails/invoice"),
                varsCap.capture(), anyMap());
        Map<String, Object> vars = varsCap.getValue();
        assertThat(vars).containsEntry("paymentMethod", "PayPal");
        assertThat(vars).containsEntry("ctaLabel", "Ver pedido y factura");
    }

    @Test
    void paymentConfirmed_englishCtaWhenLocaleNotEs() {
        Order o = order("NX-100", null, null, "USD");
        when(invoiceService.model(eq(o), eq("en"), any(), any())).thenReturn(new HashMap<>());

        service.paymentConfirmed(o, "buyer@x.com", "en", "Stripe");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> varsCap = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(any(), any(), any(), eq("emails/invoice"), varsCap.capture(), anyMap());
        assertThat(varsCap.getValue()).containsEntry("ctaLabel", "View order & invoice");
    }

    @Test
    void paymentConfirmed_fallsBackToOrderCurrencyWhenInvoiceCurrencyNull() {
        Order o = order("NX-100", null, null, "USD");
        when(invoiceService.model(eq(o), eq("es"), any(), eq("USD"))).thenReturn(new HashMap<>());

        service.paymentConfirmed(o, "buyer@x.com", "es", "Stripe");

        verify(invoiceService).model(eq(o), eq("es"), any(), eq("USD"));
        verify(emailQueue).enqueue(any(), any(), any(), eq("emails/invoice"), anyMap(), anyMap());
    }

    @Test
    void paymentConfirmed_skippedWhenEmailBlank() {
        Order o = order("NX-100", null, null, "USD");

        service.paymentConfirmed(o, "  ", "es", "Stripe", "EUR");

        verifyNoInteractions(invoiceService, emailQueue);
    }

    @Test
    void paymentConfirmed_swallowsInvoiceFailureWithoutBreakingFlow() {
        Order o = order("NX-100", null, null, "USD");
        when(invoiceService.model(any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> service.paymentConfirmed(o, "buyer@x.com", "es", "Stripe", "EUR"))
                .doesNotThrowAnyException();
        verify(emailQueue, never()).enqueue(any(), any(), any(), any());
    }

    /* ---------------- shipped ---------------- */

    @Test
    void shipped_includesTrackingAndCarrierInSpanishBody() {
        Order o = order("NX-200", "TRK-1", "Cainiao", "USD");

        service.shipped(o, "buyer@x.com", "es");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> varsCap = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(eq("buyer@x.com"), eq("Tu pedido va en camino"), eq("emails/notification"),
                varsCap.capture());
        Map<String, Object> vars = varsCap.getValue();
        assertThat((String) vars.get("bodyHtml")).contains("NX-200").contains("TRK-1").contains("Cainiao");
        assertThat(vars).containsEntry("ctaLabel", "Seguir mi pedido");
    }

    @Test
    void shipped_englishSubjectAndOmitsTrackingWhenMissing() {
        Order o = order("NX-200", null, null, "USD");

        service.shipped(o, "buyer@x.com", "en");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> varsCap = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(eq("buyer@x.com"), eq("Your order is on its way"), eq("emails/notification"),
                varsCap.capture());
        assertThat((String) varsCap.getValue().get("bodyHtml")).contains("has been shipped").doesNotContain("Tracking");
    }

    @Test
    void shipped_skippedWhenEmailBlank() {
        service.shipped(order("NX-200", "TRK-1", "Cainiao", "USD"), null, "es");
        verifyNoInteractions(emailQueue);
    }

    /* ---------------- delivered ---------------- */

    @Test
    void delivered_enqueuesSpanishDeliveredNotification() {
        Order o = order("NX-300", null, null, "USD");

        service.delivered(o, "buyer@x.com", "es");

        verify(emailQueue).enqueue(eq("buyer@x.com"), eq("Tu pedido ha sido entregado"),
                eq("emails/notification"), anyMap());
    }

    /* ---------------- refunded ---------------- */

    @Test
    void refunded_enqueuesEnglishRefundNotification() {
        Order o = order("NX-400", null, null, "USD");

        service.refunded(o, "buyer@x.com", "en");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> varsCap = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(eq("buyer@x.com"), eq("Refund processed"), eq("emails/notification"),
                varsCap.capture());
        assertThat((String) varsCap.getValue().get("bodyHtml")).contains("NX-400").contains("refund");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refunded_toCard_includesDetailBlockWithAmountAndOriginalCardDestination() {
        Order o = order("NX-401", null, null, "USD");
        Map<String, Object> model = new HashMap<>();
        model.put("total", "27,80 €");
        when(invoiceService.model(eq(o), eq("es"), any(), eq("EUR"))).thenReturn(model);

        service.refunded(o, "buyer@x.com", "es", false, "EUR", "CARD");

        ArgumentCaptor<Map<String, Object>> varsCap = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(eq("buyer@x.com"), eq("Reembolso procesado"), eq("emails/notification"),
                varsCap.capture());
        List<String[]> details = (List<String[]>) varsCap.getValue().get("details");
        assertThat(details).isNotNull();
        // Nº de pedido, importe y destino (tarjeta original) presentes en el bloque.
        assertThat(details).anySatisfy(r -> assertThat(r[1]).isEqualTo("NX-401"));
        assertThat(details).anySatisfy(r -> assertThat(r[1]).isEqualTo("27,80 €"));
        assertThat(details).anySatisfy(r -> assertThat(r[1]).contains("Tarjeta original"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refunded_toWallet_marksImmediateWalletDestination() {
        Order o = order("NX-402", null, null, "USD");
        when(invoiceService.model(any(), any(), any(), any())).thenReturn(new HashMap<>());

        service.refunded(o, "buyer@x.com", "es", true, "EUR", "CARD");

        ArgumentCaptor<Map<String, Object>> varsCap = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(eq("buyer@x.com"), eq("Reembolso procesado"), eq("emails/notification"),
                varsCap.capture());
        List<String[]> details = (List<String[]>) varsCap.getValue().get("details");
        assertThat(details).anySatisfy(r -> assertThat(r[1]).contains("Billetera"));
    }

    /* ---------------- trackingUpdate ---------------- */

    @Test
    void trackingUpdate_includesStateLocationAndTracking() {
        Order o = order("NX-500", "TRK-9", null, "USD");

        service.trackingUpdate(o, "buyer@x.com", "es", "En tránsito", "Madrid");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> varsCap = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(eq("buyer@x.com"), eq("Actualización de tu envío"),
                eq("emails/notification"), varsCap.capture());
        String body = (String) varsCap.getValue().get("bodyHtml");
        assertThat(body).contains("NX-500").contains("Madrid").contains("TRK-9");
    }

    @Test
    void trackingUpdate_swallowsEnqueueFailureWithoutBreakingFlow() {
        Order o = order("NX-500", "TRK-9", null, "USD");
        org.mockito.Mockito.doThrow(new RuntimeException("queue down"))
                .when(emailQueue).enqueue(any(), any(), any(), anyMap());

        assertThatCode(() -> service.trackingUpdate(o, "buyer@x.com", "es", "En tránsito", "Madrid"))
                .doesNotThrowAnyException();
    }
}
