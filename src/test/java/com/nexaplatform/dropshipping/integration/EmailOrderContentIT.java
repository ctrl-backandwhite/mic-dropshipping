package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.InvoiceService.PlanInvoiceData;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.enums.InvoiceLabel;
import com.nexaplatform.dropshipping.domain.enums.OrderEmailLabel;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionEmailLabel;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.User;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Certificación del CONTENIDO de los correos del ciclo de vida del pedido: pedido registrado, factura
 * de pago confirmado (con sus importes al céntimo y sus fotos incrustadas), envío y seguimiento,
 * reembolso y cancelación, más la factura del plan que viaja como PDF adjunto.
 *
 * <p>Los pedidos se construyen a mano (modelo de dominio) en vez de recorrer un checkout completo:
 * lo que se está certificando es lo que dice el correo, y así cada importe del cuerpo se puede
 * contrastar contra una cuenta hecha a mano, sin que la aritmética del carrito se cuele en la prueba.
 * Todo lo demás —modelo de la factura, plantillas, cola de salida y composición MIME— es el de
 * producción.
 */
class EmailOrderContentIT extends EmailITSupport {

    /** Divisa USD: sin filas de tipos de cambio en la base, la factura se emite y formatea en en-US. */
    private static final String CLIENTE = "cliente@example.com";

    /** Número de pedido distinto en cada caso, para que ninguna aserción case por casualidad con otro. */
    private static final AtomicInteger NUMERADOR = new AtomicInteger(100000);

    @Autowired
    private OrderEmailService orderEmailService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private SubscriptionNotificationService subscriptionNotifications;

    @Autowired
    private UserUseCase userUseCase;

    /* ==================================================================================
     * Pedido registrado / factura
     * ================================================================================== */

    @Test
    @DisplayName("el correo de pedido registrado lleva el número de pedido y el enlace a su ficha")
    void pedidoRegistradoLlevaSuNumeroYEnlace() {
        Order pedido = pedidoDeUnaLinea();

        orderEmailService.placedAwaitingPayment(pedido, CLIENTE, "es");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        String html = cuerpoHtml(correo);
        assertThat(asuntoDe(correo)).isEqualTo(OrderEmailLabel.PLACED_TITLE.of("es"));
        assertThat(html).contains(pedido.getOrderNumber()).contains(OrderEmailLabel.CTA_VIEW_ORDER.of("es"))
                .contains("http://localhost:3003/orders/" + pedido.getId())
                .contains(OrderEmailLabel.AUTO_NOTE.of("es"));
    }

    /**
     * Cuenta hecha a mano (dos líneas):
     * <pre>
     *   19,99 × 2 = 39,98      5,50 × 3 = 16,50      subtotal = 56,48
     *   + envío 4,99 + IVA 12,90 − descuento 0,00  →  total = 74,37
     *   IVA sobre base (56,48 + 4,99 = 61,47) = 20,98 % → se muestra redondeado al 21 %
     * </pre>
     */
    @Test
    @DisplayName("la factura del pago confirmado cuadra al céntimo: líneas, subtotal, envío, IVA y total")
    void laFacturaCuadraAlCentimo() {
        Order pedido = pedido(item("Vestido de lino", "SKU-LIN-1", "Azul / M", 1999, 2),
                item("Cinturón de piel", "SKU-CIN-9", "Negro", 550, 3));
        pedido.setShippingCents(499);
        pedido.setTaxCents(1290);
        pedido.setDiscountCents(0);

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "CARD");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        String html = cuerpoHtml(correo);

        assertThat(asuntoDe(correo)).isEqualTo("Factura " + pedido.getOrderNumber());
        assertThat(html).contains(pedido.getOrderNumber())
                // líneas
                .contains("Vestido de lino").contains("SKU-LIN-1").contains("Azul / M").contains("$19.99")
                .contains("$39.98").contains("Cinturón de piel").contains("SKU-CIN-9").contains("$5.50")
                .contains("$16.50")
                // totales
                .contains("$56.48").contains("$4.99").contains("$12.90").contains("$74.37").contains("IVA (21%)")
                // método de pago traducido, no el código crudo
                .contains("Tarjeta").doesNotContain(">CARD<");
        assertThat(html).contains(InvoiceLabel.PAID.of("es"));
        // La factura sale del remitente de facturación, no del no-reply genérico.
        assertThat(remitenteDe(correo)).contains("noreply@nexadrop.local");
    }

    @Test
    @DisplayName("un pedido de UNA sola línea sale completo y sin filas de más")
    void pedidoDeUnaSolaLinea() {
        Order pedido = pedido(item("Lámpara de mesa", "SKU-LAM-1", "", 2500, 1));
        pedido.setShippingCents(0);
        pedido.setTaxCents(0);

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "WALLET");
        despacharCola();

        String html = cuerpoHtml(unicoCorreoPara(CLIENTE));
        assertThat(html).contains("Lámpara de mesa").contains("$25.00");
        assertThat(vecesQueAparece(html, "SKU-LAM-1")).isEqualTo(1);
        assertThat(html).contains("Billetera");
    }

    @Test
    @DisplayName("un pedido de MUCHAS líneas las lleva todas, cada una con su importe")
    void pedidoDeMuchasLineas() {
        List<OrderItem> lineas = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            lineas.add(item("Artículo " + i, "SKU-" + i, "", 100 * i, i));
        }
        Order pedido = pedido(lineas.toArray(new OrderItem[0]));

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "CARD");
        despacharCola();

        String html = cuerpoHtml(unicoCorreoPara(CLIENTE));
        // 1×1,00 + 2×2,00 + … + 8×8,00 = 204,00
        for (int i = 1; i <= 8; i++) {
            assertThat(html).as("falta la línea %d", i).contains("Artículo " + i).contains("SKU-" + i);
        }
        assertThat(html).contains("$204.00");
    }

    @Test
    @DisplayName("un importe de cero se escribe como cero, no en blanco ni como 'null'")
    void importeCero() {
        Order pedido = pedido(item("Muestra gratuita", "SKU-FREE", "", 0, 1));
        pedido.setShippingCents(0);
        pedido.setTaxCents(0);

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "WALLET");
        despacharCola();

        String html = cuerpoHtml(unicoCorreoPara(CLIENTE));
        assertThat(html).contains("$0.00").contains("IVA (0%)").doesNotContain("null");
    }

    @Test
    @DisplayName("un importe muy grande se escribe con sus separadores de miles y sin perder céntimos")
    void importeMuyGrande() {
        // 1.234.567.890 céntimos = 12.345.678,90 USD (cabe en el int de céntimos del pedido).
        Order pedido = pedido(item("Contenedor completo", "SKU-BIG", "", 1_234_567_890, 1));
        pedido.setShippingCents(0);
        pedido.setTaxCents(0);

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "CARD");
        despacharCola();

        String html = cuerpoHtml(unicoCorreoPara(CLIENTE));
        assertThat(html).contains("$12,345,678.90");
    }

    /**
     * Cuando el pedido YA se cobró, la factura tiene que decir lo que se cobró de verdad, no lo que
     * costaría hoy. Cuenta hecha a mano:
     * <pre>
     *   pedido: 19,99 × 2 = 39,98 + envío 4,99 + IVA 12,90 = 57,87
     *   cobrado (liquidación del pago): 55,00  →  factor = 55,00 / 57,87 = 0,9504060826
     *   subtotal = 39,98 × factor = 37,997… → 38,00      envío = 4,99 × factor = 4,742… → 4,74
     *   IVA = resto = 55,00 − 38,00 − 4,74 = 12,26       38,00 + 4,74 + 12,26 = 55,00 exacto
     * </pre>
     */
    @Test
    @DisplayName("si el pedido ya se cobró, la factura dice EXACTAMENTE lo cobrado y el desglose sigue sumando")
    void laFacturaDiceExactamenteLoCobrado() {
        Order pedido = pedido(item("Vestido de lino", "SKU-LIN-1", "Azul / M", 1999, 2));
        pedido.setShippingCents(499);
        pedido.setTaxCents(1290);
        registrarCobro(pedido.getId(), "USD", "55.0000");

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "CARD", "USD");
        despacharCola();

        String html = cuerpoHtml(unicoCorreoPara(CLIENTE));
        assertThat(html).contains("$55.00").contains("$38.00").contains("$4.74").contains("$12.26")
                .doesNotContain("$57.87");
    }

    /* ==================================================================================
     * Imágenes de producto: dentro del mensaje (CID), nunca por URL
     * ================================================================================== */

    @Test
    @DisplayName("las fotos de la factura viajan DENTRO del correo (cid:), no como URL del storage")
    void lasFotosDeLaFacturaVanIncrustadasPorCid() {
        String urlEnElBucket = "http://localhost:9000/test/productos/vestido.png";
        when(objectStorage.bytesFromPublicUrl(anyString())).thenReturn(pngDePrueba(400));

        OrderItem linea = item("Vestido de lino", "SKU-LIN-1", "Azul / M", 1999, 1);
        linea.setImageUrlSnapshot(urlEnElBucket);
        Order pedido = pedido(linea);

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "CARD");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        String html = cuerpoHtml(correo);

        // El HTML apunta al adjunto, NUNCA a la URL del bucket: localhost es inalcanzable para el
        // proveedor de correo del destinatario y Outlook/Apple Mail bloquean las imágenes remotas.
        assertThat(html).contains("src=\"cid:invitem-0\"").doesNotContain(urlEnElBucket);

        Part foto = parteConCid(correo, "invitem-0");
        assertThat(foto).as("la foto de la línea tiene que viajar dentro del mensaje").isNotNull();
        assertThat(tipoMimeDe(foto)).containsIgnoringCase("image/jpeg");
        assertThat(disposicionDe(foto)).isEqualToIgnoringCase(Part.INLINE);
        // A una imagen incrustada la identifica su Content-ID (es lo que referencia el <img>), no el
        // nombre de fichero: Spring nombra "inline" todas las que se adjuntan desde memoria.
        assertThat(nombreDe(foto)).isEqualTo("inline");
        assertThat(bytesDe(foto)).as("el adjunto no puede ir vacío").isNotEmpty();

        // El icono del encabezado también va incrustado (los data-URI los bloquea Gmail).
        Part icono = parteConCid(correo, "circle-check");
        assertThat(icono).isNotNull();
        assertThat(tipoMimeDe(icono)).containsIgnoringCase("image/png");
        assertThat(disposicionDe(icono)).isEqualToIgnoringCase(Part.INLINE);
        assertThat(bytesDe(icono)).isNotEmpty();
    }

    @Test
    @DisplayName("un producto SIN imagen no rompe el correo ni deja un hueco de imagen rota")
    void productoSinImagenNoRompeElCorreo() {
        OrderItem linea = item("Producto sin foto", "SKU-NOIMG", "", 1000, 1);
        linea.setImageUrlSnapshot(null);
        Order pedido = pedido(linea);

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "CARD");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        String html = cuerpoHtml(correo);
        assertThat(html).contains("Producto sin foto").contains("$10.00").doesNotContain("cid:invitem-")
                .doesNotContain("src=\"\"");
        assertThat(parteConCid(correo, "invitem-0")).isNull();
    }

    @Test
    @DisplayName("si la foto no está en el bucket, el correo sale igual (sin la imagen) en vez de no salir")
    void fotoIlocalizableNoImpideElEnvio() {
        // El doble devuelve "no hay bytes": es lo que ocurre con una URL externa (alicdn) o una clave
        // borrada del bucket al re-espejar el catálogo.
        when(objectStorage.bytesFromPublicUrl(anyString())).thenReturn(new byte[0]);
        OrderItem linea = item("Producto con foto perdida", "SKU-LOST", "", 1000, 1);
        linea.setImageUrlSnapshot("http://localhost:9000/test/productos/borrada.png");

        orderEmailService.paymentConfirmed(pedido(linea), CLIENTE, "es", "CARD");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        assertThat(cuerpoHtml(correo)).contains("Producto con foto perdida");
        assertThat(parteConCid(correo, "invitem-0")).isNull();
    }

    /* ==================================================================================
     * Envío, seguimiento, entrega
     * ================================================================================== */

    @Test
    @DisplayName("el correo de envío lleva el número de seguimiento y el transportista")
    void correoDeEnvioConSeguimiento() {
        Order pedido = pedidoDeUnaLinea();
        pedido.setTrackingNumber("YT2612345678901234");
        pedido.setCarrier("YunExpress");

        orderEmailService.shipped(pedido, CLIENTE, "es");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        String html = cuerpoHtml(correo);
        assertThat(asuntoDe(correo)).isEqualTo(OrderEmailLabel.SHIPPED_TITLE.of("es"));
        assertThat(html).contains(pedido.getOrderNumber()).contains(OrderEmailLabel.TRACKING_NUMBER.of("es"))
                .contains("YT2612345678901234").contains("YunExpress").contains(OrderEmailLabel.CTA_TRACK.of("es"));
    }

    @Test
    @DisplayName("la actualización de seguimiento traduce el estado y la ubicación al idioma del usuario")
    void correoDeActualizacionDeSeguimiento() {
        Order pedido = pedidoDeUnaLinea();
        pedido.setTrackingNumber("YT2699999999999999");

        orderEmailService.trackingUpdate(pedido, CLIENTE, "en", "En tránsito internacional", "Shenzhen, CN");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        String html = cuerpoHtml(correo);
        assertThat(asuntoDe(correo)).isEqualTo(OrderEmailLabel.TRACK_TITLE.of("en"));
        assertThat(html).contains("In international transit").contains("Shenzhen, China").contains("YT2699999999999999")
                .doesNotContain("En tránsito internacional");
    }

    @Test
    @DisplayName("un envío sin número de seguimiento no deja el hueco del número a medias")
    void envioSinNumeroDeSeguimiento() {
        Order pedido = pedidoDeUnaLinea();
        pedido.setTrackingNumber(null);

        orderEmailService.shipped(pedido, CLIENTE, "es");
        despacharCola();

        String html = cuerpoHtml(unicoCorreoPara(CLIENTE));
        assertThat(html).contains(pedido.getOrderNumber()).doesNotContain(OrderEmailLabel.TRACKING_NUMBER.of("es"))
                .doesNotContain("null");
    }

    @Test
    @DisplayName("el correo de entrega avisa con el número de pedido")
    void correoDeEntrega() {
        Order pedido = pedidoDeUnaLinea();

        orderEmailService.delivered(pedido, CLIENTE, "es");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        assertThat(asuntoDe(correo)).isEqualTo(OrderEmailLabel.DELIVERED_TITLE.of("es"));
        assertThat(cuerpoHtml(correo)).contains(pedido.getOrderNumber());
    }

    /* ==================================================================================
     * Reembolso y cancelación
     * ================================================================================== */

    @Test
    @DisplayName("el reembolso detalla pedido, importe exacto, artículos y destino (tarjeta original)")
    void correoDeReembolsoAlMetodoOriginal() {
        Order pedido = pedido(item("Vestido de lino", "SKU-LIN-1", "Azul / M", 1999, 2),
                item("Cinturón de piel", "SKU-CIN-9", "Negro", 550, 3));
        pedido.setShippingCents(499);
        pedido.setTaxCents(1290);

        orderEmailService.refunded(pedido, CLIENTE, "es", false, "USD", "CARD");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        String html = cuerpoHtml(correo);
        assertThat(asuntoDe(correo)).isEqualTo(OrderEmailLabel.REFUNDED_TITLE.of("es"));
        assertThat(html).contains(pedido.getOrderNumber()).contains(OrderEmailLabel.REFUND_L_AMOUNT.of("es"))
                // Se devuelve EXACTAMENTE lo cobrado: el total de la factura, al céntimo.
                .contains("$74.37").contains(OrderEmailLabel.REFUND_L_ITEMS.of("es"))
                .contains(OrderEmailLabel.REFUND_L_DEST.of("es")).contains(OrderEmailLabel.REFUND_DEST_CARD.of("es"));
    }

    /**
     * La cancelación no tiene correo propio: se avisa con el de reembolso, acreditado al saldo y fechado
     * en el momento de la cancelación. Esta prueba fija ese comportamiento.
     */
    @Test
    @DisplayName("al cancelar, el aviso es el de reembolso: destino billetera y fecha de la cancelación")
    void correoDeCancelacionConReembolsoAlSaldo() {
        Order pedido = pedidoDeUnaLinea();
        pedido.setStatus(OrderStatus.CANCELLED);
        Instant cancelado = Instant.parse("2026-08-14T09:30:00Z");
        pedido.setCancelledAt(cancelado);

        orderEmailService.refunded(pedido, CLIENTE, "es", true, "USD", "WALLET");
        despacharCola();

        String html = cuerpoHtml(unicoCorreoPara(CLIENTE));
        assertThat(html).contains(pedido.getOrderNumber()).contains(OrderEmailLabel.REFUND_DEST_WALLET.of("es"))
                .contains(OrderEmailLabel.REFUND_L_DATE.of("es")).contains(invoiceService.formatDate(cancelado));
    }

    /* ==================================================================================
     * Idiomas
     * ================================================================================== */

    @ParameterizedTest(name = "idioma {0}")
    @ValueSource(strings = {"es", "en", "pt", "zh", "fr", "de", "it", "nl"})
    @DisplayName("la factura se emite en el idioma del usuario y sin claves de traducción crudas")
    void laFacturaSaleEnElIdiomaDelUsuario(String idioma) {
        Order pedido = pedido(item("Vestido de lino", "SKU-LIN-1", "", 1999, 1));

        orderEmailService.paymentConfirmed(pedido, CLIENTE, idioma, "CARD");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        String html = cuerpoHtml(correo);
        assertThat(asuntoDe(correo)).isEqualTo(InvoiceLabel.INVOICE.of(idioma) + " " + pedido.getOrderNumber());
        assertThat(html).contains(InvoiceLabel.TITLE_PAID.of(idioma)).contains(InvoiceLabel.DESCRIPTION.of(idioma))
                .contains(InvoiceLabel.TOTAL.of(idioma)).contains(comoSeVeEnElHtml(InvoiceLabel.CTA_VIEW.of(idioma)));
        assertThat(html).doesNotContain("${").doesNotContain("th:text");
        assertThat(html.matches("(?s).*\\b(email|order|invoice)\\.[a-z]+\\.[a-z.]+\\b.*"))
                .as("el cuerpo no puede llevar claves de traducción sin resolver").isFalse();
    }

    @Test
    @DisplayName("el chino de la factura llega intacto por el cable (asunto y cuerpo)")
    void elChinoNoSeRompeAlSerializarElMensaje() {
        Order pedido = pedido(item("亚麻连衣裙", "SKU-ZH-1", "蓝色 / M", 1999, 1));
        pedido.setShippingFullName("张伟");

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "zh", "CARD");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        assertThat(asuntoDe(correo)).isEqualTo("发票 " + pedido.getOrderNumber());
        assertThat(cuerpoHtml(correo)).contains("亚麻连衣裙").contains("蓝色 / M").contains("张伟");
    }

    /* ==================================================================================
     * Factura del plan: PDF adjunto
     * ================================================================================== */

    @Test
    @DisplayName("el correo del plan lleva la factura ADJUNTA en PDF, con su nombre y su tipo")
    void elCorreoDelPlanLlevaLaFacturaAdjuntaEnPdf() {
        String email = "plan-" + UUID.randomUUID() + "@example.com";
        User usuario = userUseCase
                .register(User.builder().email(email).language("es").displayName("Cliente Plan").build(), "Segura123!");
        byte[] pdf = invoiceService.renderPlanInvoicePdf(new PlanInvoiceData("NX-PLAN-0001", "EUR", 2500L, 525L, 3025L,
                "Plan Starter", null, null, null, "Cliente Plan", email, true, null), "es");

        subscriptionNotifications.planActivated(usuario.getId(), "starter", Instant.now(), false, pdf,
                "factura-NX-PLAN-0001.pdf");
        despacharCola();

        // El alta manda su correo de confirmación; el del plan es el segundo.
        List<MimeMessage> suyos = correosPara(email);
        assertThat(suyos).hasSize(2);
        MimeMessage correo = suyos.get(1);

        assertThat(asuntoDe(correo)).isEqualTo(SubscriptionEmailLabel.SUBJECT.of("es"));
        assertThat(cuerpoHtml(correo)).contains(SubscriptionEmailLabel.TITLE.of("es"));

        List<Part> adjuntos = ficherosAdjuntos(correo);
        assertThat(adjuntos).as("la factura del plan tiene que viajar adjunta").hasSize(1);
        Part factura = adjuntos.get(0);
        assertThat(nombreDe(factura)).isEqualTo("factura-NX-PLAN-0001.pdf");
        assertThat(tipoMimeDe(factura)).containsIgnoringCase("application/pdf");
        byte[] adjunto = bytesDe(factura);
        assertThat(adjunto).isNotEmpty().hasSize(pdf.length);
        assertThat(new String(adjunto, 0, 5, StandardCharsets.ISO_8859_1))
                .as("los bytes adjuntos tienen que ser un PDF de verdad").isEqualTo("%PDF-");
    }

    /**
     * Contrato ACTUAL de la factura del pedido: viaja en el CUERPO del correo (con sus fotos incrustadas)
     * y con un botón para descargar el PDF; el mensaje no lleva ningún fichero adjunto. Se fija aquí para
     * que un cambio en esa decisión no pase inadvertido.
     */
    @Test
    @DisplayName("la factura del pedido va en el cuerpo con enlace de descarga, sin fichero adjunto")
    void laFacturaDelPedidoVaEnElCuerpoYNoComoFichero() {
        Order pedido = pedidoDeUnaLinea();

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "CARD");
        despacharCola();

        MimeMessage correo = unicoCorreoPara(CLIENTE);
        assertThat(ficherosAdjuntos(correo)).isEmpty();
        assertThat(cuerpoHtml(correo)).contains(InvoiceLabel.INVOICE.of("es"))
                .contains(comoSeVeEnElHtml(InvoiceLabel.CTA_VIEW.of("es")))
                .contains("http://localhost:3003/orders/" + pedido.getId());
    }

    @Test
    @Disabled("""
            No se puede ejecutar: hoy el correo de pago confirmado NO adjunta el PDF de la factura del
            PEDIDO. La plataforma sabe generarlo (InvoiceService.renderPdf) y la cola sabe adjuntarlo
            (EmailQueueService.enqueueWithAttachment, que sí usa la factura del PLAN), pero
            OrderEmailService.paymentConfirmed solo encola el HTML con un enlace de descarga, que exige
            volver a la web e iniciar sesión. Cuando se decida adjuntarlo, quitar @Disabled: el caso ya
            comprueba que el adjunto existe, se llama como el pedido y viaja como application/pdf.""")
    @DisplayName("el correo de pago confirmado debería llevar la factura del pedido ADJUNTA en PDF")
    void laFacturaDelPedidoDeberiaViajarAdjuntaEnPdf() {
        Order pedido = pedidoDeUnaLinea();

        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "CARD");
        despacharCola();

        List<Part> adjuntos = ficherosAdjuntos(unicoCorreoPara(CLIENTE));
        assertThat(adjuntos).hasSize(1);
        assertThat(nombreDe(adjuntos.get(0))).contains(pedido.getOrderNumber()).endsWith(".pdf");
        assertThat(tipoMimeDe(adjuntos.get(0))).containsIgnoringCase("application/pdf");
        assertThat(bytesDe(adjuntos.get(0))).isNotEmpty();
    }

    /* ==================================================================================
     * Robustez
     * ================================================================================== */

    @Test
    @DisplayName("si el SMTP falla, el correo del pedido no se pierde: queda pendiente y no revienta el flujo")
    void unFalloDeSmtpNoInterrumpeElFlujoDelPedido() {
        doThrow(new MailSendException("451 4.7.1 Ratelimit exceeded, try again later")).when(mailSender)
                .send(any(MimeMessage.class));
        Order pedido = pedidoDeUnaLinea();

        // No puede propagar: el pago ya está cobrado y el pedido no puede quedarse a medias por un correo.
        orderEmailService.paymentConfirmed(pedido, CLIENTE, "es", "CARD");
        despacharCola();

        assertThat(estadoDelCorreo(CLIENTE)).isEqualTo("PENDING");
        assertThat(proximoIntentoPendiente(CLIENTE))
                .as("un rate-limit es temporal: hay que reintentar más tarde, no descartar el correo").isTrue();
    }

    @Test
    @DisplayName("sin dirección de correo no se encola nada (el pedido sigue su curso)")
    void sinDireccionNoSeEncolaNada() {
        orderEmailService.paymentConfirmed(pedidoDeUnaLinea(), null, "es", "CARD");
        orderEmailService.shipped(pedidoDeUnaLinea(), "  ", "es");
        despacharCola();

        assertThat(correosEnviados()).isEmpty();
        assertThat(correosEncolados()).isZero();
    }

    /* ==================================================================================
     * Utilidades del caso de prueba
     * ================================================================================== */

    private Order pedidoDeUnaLinea() {
        return pedido(item("Vestido de lino", "SKU-LIN-1", "Azul / M", 1999, 1));
    }

    /** Pedido pagado, en USD, con las líneas indicadas y los totales coherentes con ellas. */
    private Order pedido(OrderItem... lineas) {
        int subtotal = 0;
        for (OrderItem linea : lineas) {
            subtotal += linea.getLineTotalCents();
        }
        return Order.builder().id(UUID.randomUUID()).orderNumber("NX-" + NUMERADOR.incrementAndGet())
                .status(OrderStatus.PAID).currency("USD").items(new ArrayList<>(List.of(lineas)))
                .subtotalCents(subtotal).shippingCents(0).taxCents(0).discountCents(0).totalCents(subtotal)
                .placedAt(Instant.parse("2026-08-14T08:00:00Z")).shippingFullName("Ana López").shippingEmail(CLIENTE)
                .shippingLine1("Calle Mayor 1").shippingCity("Madrid").shippingPostalCode("28001").shippingCountry("ES")
                .build();
    }

    /**
     * Deja constancia de un cobro satisfactorio del pedido. Se escribe por SQL porque lo que se prueba es
     * la factura, no la pasarela: lo único que necesita el modelo de la factura es que exista un pago
     * SUCCEEDED con su importe liquidado.
     */
    private void registrarCobro(UUID pedidoId, String divisa, String importeLiquidado) {
        UUID pagador = userUseCase
                .register(User.builder().email("pagador-" + UUID.randomUUID() + "@example.com").language("es").build(),
                        "Segura123!")
                .getId();
        jdbcTemplate.update("INSERT INTO payment (id, user_id, order_id, purpose, method, status,"
                + " amount_usd_cents, settlement_currency, settlement_amount, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'ORDER_PAYMENT', 'CARD', 'SUCCEEDED', ?, ?, CAST(? AS NUMERIC), now(), now())",
                UUID.randomUUID(), pagador, pedidoId, 5787L, divisa, importeLiquidado);
    }

    private static OrderItem item(String titulo, String sku, String variante, int precioUnitario, int cantidad) {
        return OrderItem.builder().id(UUID.randomUUID()).titleSnapshot(titulo).skuSnapshot(sku).variantName(variante)
                .unitPriceCents(precioUnitario).quantity(cantidad).lineTotalCents(precioUnitario * cantidad).build();
    }

    private static int vecesQueAparece(String texto, String fragmento) {
        int veces = 0;
        int desde = texto.indexOf(fragmento);
        while (desde >= 0) {
            veces++;
            desde = texto.indexOf(fragmento, desde + fragmento.length());
        }
        return veces;
    }

    private String estadoDelCorreo(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbound_email WHERE to_address = ? ORDER BY created_at DESC LIMIT 1", String.class,
                email);
    }

    private boolean proximoIntentoPendiente(String email) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbound_email WHERE to_address = ? AND next_attempt_at > now()", Integer.class,
                email);
        return n != null && n > 0;
    }

    private int correosEncolados() {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM outbound_email", Integer.class);
        return n == null ? 0 : n;
    }
}
