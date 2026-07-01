package com.nexaplatform.dropshipping.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica la traducción a los 8 idiomas de los enums de textos (factura, emails de pedido, auth,
 * método de pago y eventos de envío), incluyendo la sustitución de marcadores y los casos borde.
 */
class LabelTranslationTest {

    @Test
    void paymentMethod_translatesAndKeepsBrands() {
        assertThat(PaymentMethodLabel.localize("CARD", "es")).isEqualTo("Tarjeta");
        assertThat(PaymentMethodLabel.localize("CARD", "en")).isEqualTo("Card");
        assertThat(PaymentMethodLabel.localize("CARD", "fr")).isEqualTo("Carte");
        assertThat(PaymentMethodLabel.localize("WALLET", "es")).isEqualTo("Billetera");
        // Marcas/símbolos: iguales en todos los idiomas.
        assertThat(PaymentMethodLabel.localize("PAYPAL", "de")).isEqualTo("PayPal");
        assertThat(PaymentMethodLabel.localize("USDT", "zh")).isEqualTo("USDT");
        // Locale con región resuelve al idioma base; método desconocido/nulo se devuelve tal cual.
        assertThat(PaymentMethodLabel.localize("CARD", "es-ES")).isEqualTo("Tarjeta");
        assertThat(PaymentMethodLabel.localize("BIZUM", "es")).isEqualTo("BIZUM");
        assertThat(PaymentMethodLabel.localize(null, "es")).isNull();
    }

    @Test
    void invoiceLabel_translatesAndFallsBackToSpanish() {
        assertThat(InvoiceLabel.INVOICE.of("es")).isEqualTo("Factura");
        assertThat(InvoiceLabel.INVOICE.of("en")).isEqualTo("Invoice");
        assertThat(InvoiceLabel.BILL_TO.of("fr")).isEqualTo("Facturer à");
        // Idioma no soportado → español por defecto.
        assertThat(InvoiceLabel.INVOICE.of("xx")).isEqualTo("Factura");
        assertThat(InvoiceLabel.lang("pt-BR")).isEqualTo("pt");
        assertThat(InvoiceLabel.lang(null)).isEqualTo("es");
    }

    @Test
    void orderEmailLabel_substitutesOrderPlaceholder() {
        assertThat(OrderEmailLabel.DELIVERED_TITLE.of("en")).isEqualTo("Your order has been delivered");
        assertThat(OrderEmailLabel.SHIPPED_BODY.of("es", "NX-123")).contains("NX-123").doesNotContain("{order}");
        assertThat(OrderEmailLabel.SHIPPED_BODY.of("fr", "NX-1")).contains("NX-1").contains("expédiée");
    }

    @Test
    void authEmailLabel_substitutesNamePlaceholder() {
        assertThat(AuthEmailLabel.CONFIRM_BODY.of("es", "Ana")).contains("Ana").doesNotContain("{name}");
        assertThat(AuthEmailLabel.RESET_SUBJECT.of("en")).isEqualTo("Reset your password · NX036");
        assertThat(AuthEmailLabel.INVITE_CTA.of("it")).isEqualTo("Attiva account");
    }

    @Test
    void shipmentEventMessage_translatesKnownDescriptions() {
        assertThat(ShipmentEventMessage.translate("En reparto", "es")).isEqualTo("En reparto");
        assertThat(ShipmentEventMessage.translate("En reparto", "en")).isEqualTo("Out for delivery");
        assertThat(ShipmentEventMessage.translate("En reparto", "fr")).isEqualTo("En cours de livraison");
        // Descripción no reconocida → se devuelve tal cual (no se pierde información).
        assertThat(ShipmentEventMessage.translate("Texto libre", "en")).isEqualTo("Texto libre");
    }
}
