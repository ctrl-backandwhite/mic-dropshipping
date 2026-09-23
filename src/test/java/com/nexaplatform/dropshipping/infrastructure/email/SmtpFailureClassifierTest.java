package com.nexaplatform.dropshipping.infrastructure.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clasificación de fallos SMTP en temporales (reintentar) o permanentes (descartar). El rate-limit del
 * proveedor —la causa real de que en pre-producción no llegaran los correos— debe salir SIEMPRE como
 * temporal para que no se pierda el mensaje.
 */
class SmtpFailureClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Failed messages: org.eclipse.angus.mail.smtp.SMTPSendFailedException: "
                    + "451 4.7.1 Ratelimit \"hostinger_out_ratelimit\" exceeded for key \"RL9\"",
            "421 4.7.0 Try again later, closing connection", "450 4.2.1 Mailbox temporarily unavailable",
            "452 4.5.3 Too many recipients", "Connection timed out"})
    void losFallosTemporalesSeReintentan(String message) {
        assertThat(SmtpFailureClassifier.classify(new RuntimeException(message)))
                .isEqualTo(SmtpFailureClassifier.Kind.TRANSIENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"550 5.1.1 <no@existe.com>: Recipient address rejected: User unknown",
            "553 5.7.1 Sender address rejected", "554 5.7.1 Message rejected as spam"})
    void losFallosPermanentesSeDescartan(String message) {
        assertThat(SmtpFailureClassifier.classify(new RuntimeException(message)))
                .isEqualTo(SmtpFailureClassifier.Kind.PERMANENT);
    }

    @Test
    void unErrorSinCodigoNiPistasSeTrataComoTemporalParaNoPerderElCorreo() {
        assertThat(SmtpFailureClassifier.classify(new IllegalStateException("algo raro pasó")))
                .isEqualTo(SmtpFailureClassifier.Kind.TRANSIENT);
    }

    @Test
    void elMensajePuedeVenirEnUnaCausaAnidada() {
        Throwable root = new IllegalStateException("450 4.2.0 greylisted");
        Throwable wrapper = new RuntimeException("fallo al enviar", root);

        assertThat(SmtpFailureClassifier.classify(wrapper)).isEqualTo(SmtpFailureClassifier.Kind.TRANSIENT);
    }

    @Test
    void unaCadenaDeCausasCiclicaNoCuelgaLaClasificacion() {
        RuntimeException a = new RuntimeException("550 5.0.0 rejected");
        RuntimeException b = new RuntimeException("wrapper", a);
        a.initCause(b); // ciclo deliberado

        assertThat(SmtpFailureClassifier.classify(b)).isEqualTo(SmtpFailureClassifier.Kind.PERMANENT);
    }
}
