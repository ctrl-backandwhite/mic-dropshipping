package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OpsAlertService.Alert;
import com.nexaplatform.dropshipping.application.service.OpsAlertService.AlertKind;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Avisos al responsable cuando el transportista o una pasarela dejan de funcionar.
 *
 * <p>Lo que más importa aquí no es que el correo salga, sino que <b>no salgan cien</b>: si el carrier se
 * cae, cada pedido pendiente generaría su propio aviso y el buzón quedaría inservible justo cuando hay que
 * leerlo. Y que un fallo enviando el aviso jamás tumbe el flujo que lo originó.
 */
class OpsAlertServiceTest {

    private EmailQueueService emailQueue;
    private OpsAlertService service;

    @BeforeEach
    void setUp() {
        emailQueue = Mockito.mock(EmailQueueService.class);
        service = new OpsAlertService(emailQueue);
        ReflectionTestUtils.setField(service, "alertEmail", "jfinol02@gmail.com");
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "cooldownMinutes", 30L);
    }

    @Test
    void avisaAlResponsableConElMotivoDelTransportista() {
        service.fulfillmentFailed("NX-1784936692-7159", "ES", 3, "02039171 Weight should not exceed 2KG");

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(to.capture(), subject.capture(), anyString(), vars.capture());

        assertThat(to.getValue()).isEqualTo("jfinol02@gmail.com");
        assertThat(subject.getValue()).contains("envío");
        assertThat(vars.getValue().get("body").toString())
                .contains("NX-1784936692-7159")
                .contains("02039171")
                .contains("3 intento");
    }

    @Test
    void elMismoProblemaNoSeNotificaDosVecesSeguidas() {
        // Carrier caído: tres pedidos distintos, misma causa -> un solo correo.
        service.fulfillmentFailed("NX-1", "ES", 3, "02039171 Weight should not exceed 2KG");
        service.fulfillmentFailed("NX-2", "FR", 3, "02039171 Weight should not exceed 2KG");
        service.fulfillmentFailed("NX-3", "DE", 3, "02039171 Weight should not exceed 2KG");

        verify(emailQueue, times(1)).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void problemasDistintosSiGeneranAvisosDistintos() {
        service.fulfillmentFailed("NX-1", "ES", 3, "02039171 Weight should not exceed 2KG");
        service.paymentFailed("stripe", "cobro del pedido", "pay-1", "connection refused");

        verify(emailQueue, times(2)).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void elAvisoDePagoIdentificaPasarelaYOperacion() {
        service.paymentFailed("stripe", "cobro del pedido", "pay-1", "connection refused");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(anyString(), subject.capture(), anyString(), vars.capture());

        assertThat(subject.getValue()).contains("stripe");
        assertThat(vars.getValue().get("body").toString())
                .contains("cobro del pedido").contains("connection refused");
    }

    @Test
    void desactivadoNoEnviaNada() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.fulfillmentFailed("NX-1", "ES", 3, "cualquier cosa");

        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void unFalloEnviandoElAvisoNoPropagaAlFlujoQueLoOrigino() {
        when(emailQueue.enqueue(anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("SMTP caído"));

        assertThatCode(() -> service.fulfillmentFailed("NX-1", "ES", 3, "error"))
                .doesNotThrowAnyException();
    }

    @Test
    void elAvisoSeLeeEnEspanolYDejaElOriginalComoDetalleTecnico() {
        // El transportista contesta en inglés; el aviso no puede quedar a medias entre dos idiomas.
        service.fulfillmentFailed("NX-1", "ES", 3,
                "02039171 Order rule verification failed: Weight : should not exceed 2KG");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailQueue).enqueue(anyString(), subject.capture(), anyString(), vars.capture());
        String body = vars.getValue().get("body").toString();

        assertThat(body).contains("no cumple las reglas del canal contratado");
        // El texto original se conserva, pero etiquetado y separado del mensaje en español.
        assertThat(body).contains("Detalle técnico").contains("Order rule verification failed");
        // El asunto identifica el PEDIDO, no la causa técnica truncada.
        assertThat(subject.getValue()).contains("NX-1").doesNotContain("Order rule verification");
    }

    @Test
    void noSeMandaIconoPorqueLaPlantillaLoTrataComoImagenAdjunta() {
        service.fulfillmentFailed("NX-1", "ES", 3, "error");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(anyString(), anyString(), anyString(), vars.capture());

        // La plantilla hace src="cid:${icon}": cualquier valor que no sea un adjunto real sale roto.
        assertThat(vars.getValue()).doesNotContainKey("icon");
    }

    @Test
    void laCausaSeRecortaParaPoderAgrupar() {
        String largo = "a".repeat(200);

        assertThat(OpsAlertService.shortCause(largo)).hasSize(60);
        assertThat(OpsAlertService.shortCause("  varias   palabras  ")).isEqualTo("varias palabras");
        assertThat(OpsAlertService.shortCause(null)).isEqualTo("desconocido");
    }

    @Test
    void seRespetaLaClaveDeAgrupacionDelAviso() {
        service.notifyFailure(new Alert(AlertKind.FULFILLMENT, "t", "d", "clave-A"));
        service.notifyFailure(new Alert(AlertKind.FULFILLMENT, "t", "d", "clave-A"));
        service.notifyFailure(new Alert(AlertKind.FULFILLMENT, "t", "d", "clave-B"));

        verify(emailQueue, times(2)).enqueue(anyString(), anyString(), anyString(), anyMap());
    }
}
