package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.in.SubscribeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BillingConfigDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.BillingInvoiceDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MySubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PaymentMethodDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SetupIntentDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SubscribeStatusDtoOut;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del billing del usuario autenticado: el usuario SIEMPRE sale del principal (nunca del cuerpo
 * de la petición) y la ausencia de suscripción se responde sin cuerpo, no con un objeto vacío.
 */
@ExtendWith(MockitoExtension.class)
class Cov01MeBillingControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    CustomerSubscriptionUseCase useCase;
    @Mock
    com.nexaplatform.dropshipping.application.service.SavedPaymentMethodsService savedMethods;
    @Mock
    Authentication auth;

    @InjectMocks
    MeBillingController controller;

    private void authenticatedAs(UUID id) {
        when(auth.getName()).thenReturn(id.toString());
    }

    @Test
    void laConfigDeStripeViajaConLaClavePublicaYSuInterruptor() {
        authenticatedAs(USER_ID);
        when(useCase.billingConfig(USER_ID))
                .thenReturn(new CustomerSubscriptionUseCase.BillingConfigInfo("pk_test_123", true, false));

        ResponseEntity<BillingConfigDtoOut> resp = controller.billingConfig(auth);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getPublishableKey()).isEqualTo("pk_test_123");
        assertThat(resp.getBody().isEnabled()).isTrue();
    }

    @Test
    void elSecretoDelSetupIntentSePideParaElUsuarioDelPrincipal() throws Exception {
        // Si el id saliera del cuerpo, cualquiera podría guardar una tarjeta en la cuenta de otro.
        authenticatedAs(USER_ID);
        when(useCase.createSetupIntentSecret(USER_ID)).thenReturn("seti_123_secret");

        ResponseEntity<SetupIntentDtoOut> resp = controller.createSetupIntent(auth);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getClientSecret()).isEqualTo("seti_123_secret");
    }

    @Test
    void lasTarjetasSeProyectanConLaMarcaLosCuatroDigitosYCualEsLaPredeterminada() throws Exception {
        // Tras la Fase 1, el listado unificado lo sirve SavedPaymentMethodsService (tarjetas Stripe + PayPal).
        authenticatedAs(USER_ID);
        when(savedMethods.list(USER_ID)).thenReturn(List.of(
                PaymentMethodDtoOut.builder().id("pm_1").type("CARD").brand("visa").last4("4242")
                        .expMonth(12L).expYear(2030L).isDefault(true).build(),
                PaymentMethodDtoOut.builder().id("pm_2").type("CARD").brand("mastercard").last4("5555")
                        .expMonth(1L).expYear(2031L).isDefault(false).build()));

        ResponseEntity<List<PaymentMethodDtoOut>> resp = controller.listPaymentMethods(auth);

        assertThat(resp.getBody()).hasSize(2);
        PaymentMethodDtoOut first = resp.getBody().get(0);
        assertThat(first.getId()).isEqualTo("pm_1");
        assertThat(first.getBrand()).isEqualTo("visa");
        assertThat(first.getLast4()).isEqualTo("4242");
        assertThat(first.getExpMonth()).isEqualTo(12L);
        assertThat(first.getExpYear()).isEqualTo(2030L);
        assertThat(first.isDefault()).isTrue();
        assertThat(resp.getBody().get(1).isDefault()).isFalse();
    }

    @Test
    void marcarPredeterminadaYBorrarTarjetaRespondenSinCuerpo() throws Exception {
        authenticatedAs(USER_ID);

        ResponseEntity<Void> setDefault = controller.setDefault(auth, "pm_1");
        ResponseEntity<Void> deleted = controller.delete(auth, "pm_2");

        assertThat(setDefault.getStatusCode().value()).isEqualTo(204);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        verify(savedMethods).setDefault(USER_ID, "pm_1");
        verify(savedMethods).delete(USER_ID, "pm_2");
    }

    @Test
    void suscribirseDevuelveElIdentificadorYElEstadoQueDaLaPasarela() throws Exception {
        // El estado puede ser "incomplete" (3-D Secure pendiente): el front decide si pide autenticación,
        // así que no se puede dar por buena la suscripción sólo por responder 200.
        authenticatedAs(USER_ID);
        when(useCase.subscribeWithSavedCard(USER_ID, "PRO", "MONTHLY"))
                .thenReturn(new CustomerSubscriptionUseCase.SubscribeOutcome("sub_123", "incomplete"));

        ResponseEntity<SubscribeStatusDtoOut> resp = controller.subscribe(auth, SubscribeDtoIn.builder()
                .planCode("PRO").period("MONTHLY").build());

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getSubscriptionId()).isEqualTo("sub_123");
        assertThat(resp.getBody().getStatus()).isEqualTo("incomplete");
    }

    @Test
    void sinSuscripcionSeResponde204YNoUnObjetoVacio() {
        // Un cuerpo con todos los campos nulos haría que el perfil pintara un plan fantasma.
        authenticatedAs(USER_ID);
        when(useCase.currentSubscription(USER_ID)).thenReturn(null);

        ResponseEntity<MySubscriptionDtoOut> resp = controller.currentSubscription(auth);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        assertThat(resp.getBody()).isNull();
    }

    @Test
    void laSuscripcionVigenteSeProyectaConPlanEstadoYFechas() {
        authenticatedAs(USER_ID);
        UUID planId = UUID.randomUUID();
        Instant end = Instant.parse("2026-12-31T00:00:00Z");
        Instant cancelAt = Instant.parse("2026-11-30T00:00:00Z");
        when(useCase.currentSubscription(USER_ID)).thenReturn(CustomerSubscription.builder().planId(planId)
                .status(SubscriptionStatus.ACTIVE).billingPeriod("YEARLY").currentPeriodEnd(end).cancelAt(cancelAt)
                .build());

        ResponseEntity<MySubscriptionDtoOut> resp = controller.currentSubscription(auth);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getPlanId()).isEqualTo(planId.toString());
        assertThat(resp.getBody().getStatus()).isEqualTo("ACTIVE");
        assertThat(resp.getBody().getBillingPeriod()).isEqualTo("YEARLY");
        assertThat(resp.getBody().getCurrentPeriodEnd()).isEqualTo(end);
        assertThat(resp.getBody().getCancelAt()).isEqualTo(cancelAt);
    }

    @Test
    void unaSuscripcionSinPlanNiEstadoNoRompeLaRespuesta() {
        // Suscripción a medio crear (Stripe aún no confirmó): debe salir con nulos, no con un 500.
        authenticatedAs(USER_ID);
        when(useCase.currentSubscription(USER_ID)).thenReturn(CustomerSubscription.builder().build());

        ResponseEntity<MySubscriptionDtoOut> resp = controller.currentSubscription(auth);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getPlanId()).isNull();
        assertThat(resp.getBody().getStatus()).isNull();
    }

    @Test
    void cancelarLaSuscripcionRespondeSinCuerpo() throws Exception {
        authenticatedAs(USER_ID);

        ResponseEntity<Void> resp = controller.cancelSubscription(auth);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(useCase).cancelMySubscription(USER_ID);
    }

    @Test
    void lasFacturasSeProyectanConSusEnlacesDeDescarga() throws Exception {
        authenticatedAs(USER_ID);
        when(useCase.listInvoices(USER_ID)).thenReturn(List.of(new CustomerSubscriptionUseCase.InvoiceView("F-001",
                2900L, "eur", "paid", 1750000000L, "https://stripe.test/f.pdf", "https://stripe.test/f")));

        ResponseEntity<List<BillingInvoiceDtoOut>> resp = controller.invoices(auth);

        assertThat(resp.getBody()).hasSize(1);
        BillingInvoiceDtoOut inv = resp.getBody().get(0);
        assertThat(inv.getNumber()).isEqualTo("F-001");
        assertThat(inv.getTotal()).isEqualTo(2900L);
        assertThat(inv.getCurrency()).isEqualTo("eur");
        assertThat(inv.getStatus()).isEqualTo("paid");
        assertThat(inv.getPdfUrl()).isEqualTo("https://stripe.test/f.pdf");
        assertThat(inv.getHostedUrl()).isEqualTo("https://stripe.test/f");
    }

    @Test
    void unPrincipalQueNoEsUnIdentificadorValidoNoLlegaAlCasoDeUso() {
        // Un token con un "sub" manipulado no puede acabar consultando facturas de nadie.
        when(auth.getName()).thenReturn("no-soy-un-uuid");

        assertThatThrownBy(() -> controller.invoices(auth)).isInstanceOf(IllegalArgumentException.class);
    }
}
