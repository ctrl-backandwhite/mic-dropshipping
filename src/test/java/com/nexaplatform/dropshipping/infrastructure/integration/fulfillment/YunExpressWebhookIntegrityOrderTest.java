package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.api.controller.YunExpressWebhookController;
import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Blinda el ORDEN de operaciones del webhook de YunExpress: verificar la firma ANTES de descifrar.
 *
 * <p>Find Security Bugs marca {@code CIPHER_INTEGRITY} sobre {@link YunExpressEventCipher} porque usa
 * AES-CBC sin MAC. El modo lo impone el contrato del proveedor y no se puede cambiar a GCM por nuestra
 * cuenta, así que lo que sostiene que ese aviso sea un FALSO POSITIVO es exactamente esto: una firma
 * SHA-256 con clave secreta compartida que cubre el cuerpo crudo —criptograma incluido— y que se
 * comprueba antes de tocar el descifrado (construcción encrypt-then-MAC).
 *
 * <p>Ese razonamiento se cae en cuanto alguien reordene el controller o descifre para «echar un vistazo»
 * antes de validar. Estos tests fallan si eso ocurre: el falso positivo dejaría de estar justificado y
 * habría que volver a mirarlo.
 *
 * <p>Complementa a {@code YunExpressEventCipherTest}, que cubre el componente por dentro (vector oficial,
 * firmas alteradas); aquí lo que se prueba es cómo lo USA el controller.
 */
class YunExpressWebhookIntegrityOrderTest {

    /** Clave del ejemplo de la documentación oficial de YunExpress. */
    private static final String KEY = "test key";
    private static final String TIMESTAMP = "1785356088184";
    /** Criptograma del ejemplo oficial: descifra a "hello world" con {@link #KEY}. */
    private static final String CRIPTOGRAMA = "P37w+VZImNgPEO1RBhJ6RtKl7n6zymIbEG1pReEzghk=";

    private YunExpressEventCipher cipher;
    private FulfillmentService fulfillment;
    private YunExpressWebhookController controller;

    @BeforeEach
    void setUp() {
        YunExpressEventCipher real = new YunExpressEventCipher();
        ReflectionTestUtils.setField(real, "encryptKey", KEY);
        // Espía sobre el componente REAL: la firma y el descifrado se ejecutan de verdad, pero podemos
        // comprobar qué se llamó y en qué orden.
        cipher = spy(real);
        fulfillment = mock(FulfillmentService.class);
        controller = new YunExpressWebhookController(cipher, fulfillment);
    }

    private static String sobre(String criptograma) {
        return "{\"encrypt\":\"" + criptograma + "\"}";
    }

    private static String firmaDe(String cuerpo) {
        return YunExpressEventCipher.signature(TIMESTAMP, cuerpo, KEY);
    }

    /**
     * Con firma válida: PRIMERO se verifica y DESPUÉS se entrega el cuerpo al servicio, que es el único
     * camino hasta el descifrado. El {@link InOrder} es lo que fija el orden; sin él, un controller que
     * descifrara primero pasaría igual.
     */
    @Test
    void conFirmaValidaVerificaAntesDeEntregarElCuerpoAlDescifrado() {
        String cuerpo = sobre(CRIPTOGRAMA);
        String firma = firmaDe(cuerpo);

        ResponseEntity<String> respuesta = controller.receive(TIMESTAMP, firma, cuerpo);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        InOrder orden = inOrder(cipher, fulfillment);
        orden.verify(cipher).verify(TIMESTAMP, cuerpo, firma);
        orden.verify(fulfillment).applyYunExpressPush(cuerpo);
        // El controller nunca descifra por su cuenta: el descifrado vive detrás de la verificación.
        verify(cipher, never()).decrypt(anyString());
    }

    /** Con firma inválida NADA se descifra ni se procesa: 401 y punto. */
    @Test
    void conFirmaInvalidaNoSeDescifraNiSeProcesa() {
        String cuerpo = sobre(CRIPTOGRAMA);
        String firmaFalsa = "0".repeat(64);

        ResponseEntity<String> respuesta = controller.receive(TIMESTAMP, firmaFalsa, cuerpo);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(fulfillment);
        verify(cipher, never()).decrypt(anyString());
    }

    /**
     * ESTE es el test que sostiene el veredicto de {@code CIPHER_INTEGRITY}: manipular el CRIPTOGRAMA
     * —el ataque que el aviso teme, bit flipping sobre CBC— invalida la firma, porque la firma se calcula
     * sobre el cuerpo crudo y el criptograma va dentro. Quien intercepte un push legítimo y voltee un bit
     * no puede recalcular la firma sin la clave, así que su push se rechaza sin llegar a descifrarse.
     */
    @Test
    void manipularElCriptogramaInvalidaLaFirmaYSeRechazaSinDescifrar() {
        String cuerpoLegitimo = sobre(CRIPTOGRAMA);
        String firmaInterceptada = firmaDe(cuerpoLegitimo);
        // Un solo carácter del Base64: mismo tamaño, sigue siendo Base64 válido, otro texto cifrado.
        String cuerpoManipulado = sobre("Q" + CRIPTOGRAMA.substring(1));
        assertThat(cuerpoManipulado).isNotEqualTo(cuerpoLegitimo);

        ResponseEntity<String> respuesta = controller.receive(TIMESTAMP, firmaInterceptada, cuerpoManipulado);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(fulfillment);
        verify(cipher, never()).decrypt(anyString());
    }

    /** Sin clave configurada el webhook es fail-closed: mejor perder eventos que procesar uno sin verificar. */
    @Test
    void sinClaveConfiguradaRechazaElPush() {
        YunExpressEventCipher sinClave = new YunExpressEventCipher();
        ReflectionTestUtils.setField(sinClave, "encryptKey", "");
        YunExpressWebhookController controllerSinClave = new YunExpressWebhookController(sinClave, fulfillment);
        String cuerpo = sobre(CRIPTOGRAMA);

        ResponseEntity<String> respuesta = controllerSinClave.receive(TIMESTAMP, firmaDe(cuerpo), cuerpo);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(fulfillment);
    }

    /**
     * La otra mitad de {@code CIPHER_INTEGRITY} es el oráculo de padding, y aquí no existe: se descifra con
     * {@code AES/CBC/NoPadding}, así que el JCE ni valida ni delata el relleno. Un criptograma manipulado
     * sale por el mismo camino, sin excepción distinguible que un atacante pudiera interrogar.
     */
    @Test
    void elDescifradoSinPaddingNoDelataElRelleno() {
        assertThat(YunExpressEventCipher.decrypt(CRIPTOGRAMA, KEY)).isEqualTo("hello world");

        assertThatCode(() -> YunExpressEventCipher.decrypt("Q" + CRIPTOGRAMA.substring(1), KEY))
                .doesNotThrowAnyException();
    }
}
