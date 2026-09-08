package com.nexaplatform.dropshipping.infrastructure.integration.push;

import com.nexaplatform.dropshipping.domain.model.UserDevice;
import com.nexaplatform.dropshipping.domain.repository.UserDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El reparto de avisos al teléfono.
 *
 * <p>Lo que se comprueba aquí no es que «llegue el aviso» —eso lo decide Expo y el sistema
 * operativo—, sino las tres cosas que sí son responsabilidad nuestra: no tumbar nunca lo que provocó
 * el aviso, no mandar más de lo que Expo admite por llamada, y retirar los tokens que ya no sirven.
 */
class ExpoPushSenderTest {

    private static final UUID USUARIO = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private WebClient.RequestBodySpec bodySpec;
    private WebClient.ResponseSpec responseSpec;
    private UserDeviceRepository devices;
    private ExpoPushSender sender;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        WebClient.Builder builder = mock(WebClient.Builder.class);
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);

        when(builder.build()).thenReturn(webClient);
        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);

        devices = mock(UserDeviceRepository.class);
        sender = new ExpoPushSender(builder, devices);
        ReflectionTestUtils.setField(sender, "enabled", true);
        ReflectionTestUtils.setField(sender, "expoUrl", "https://expo.test/send");
    }

    @SuppressWarnings("unchecked")
    private void respondeCon(Map<String, Object> cuerpo) {
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenReturn((Mono) Mono.just(cuerpo));
    }

    private static UserDevice dispositivo(String token) {
        return UserDevice.builder().id(UUID.randomUUID()).userId(USUARIO).pushToken(token)
                .plataforma("android").build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ultimoLoteEnviado() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec, times(1)).bodyValue(captor.capture());
        return (List<Map<String, Object>>) captor.getValue();
    }

    @Test
    void mandaTituloYCuerpoACadaDispositivo() {
        when(devices.deUsuario(USUARIO)).thenReturn(List.of(dispositivo("tok-1")));
        respondeCon(Map.of("data", List.of(Map.of("status", "ok"))));

        sender.reparte(USUARIO, "Tu pedido va en camino", "NX-2026-0001 ha salido", Map.of("avisoId", "a-1"));

        List<Map<String, Object>> lote = ultimoLoteEnviado();
        assertThat(lote).hasSize(1);
        assertThat(lote.get(0)).containsEntry("to", "tok-1")
                .containsEntry("title", "Tu pedido va en camino")
                .containsEntry("body", "NX-2026-0001 ha salido")
                .containsEntry("data", Map.of("avisoId", "a-1"));
    }

    /** Sin dispositivos no hay a quién mandar: ni se llama a Expo. */
    @Test
    void noLlamaAExpoSinDispositivos() {
        when(devices.deUsuario(USUARIO)).thenReturn(List.of());

        sender.reparte(USUARIO, "t", "c", Map.of());

        verify(bodySpec, never()).bodyValue(any());
    }

    /** Apagado no manda nada, ni siquiera consulta los dispositivos. */
    @Test
    void apagadoNoHaceNada() {
        ReflectionTestUtils.setField(sender, "enabled", false);

        sender.reparte(USUARIO, "t", "c", Map.of());

        verify(devices, never()).deUsuario(any());
    }

    /** Expo admite cien mensajes por llamada; ciento uno tienen que ir en dos. */
    @Test
    void troceaEnLotesDeCien() {
        List<UserDevice> muchos = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            muchos.add(dispositivo("tok-" + i));
        }
        when(devices.deUsuario(USUARIO)).thenReturn(muchos);
        respondeCon(Map.of("data", List.of()));

        sender.reparte(USUARIO, "t", "c", Map.of());

        verify(bodySpec, times(2)).bodyValue(any());
    }

    /**
     * Un token de Expo caduca cuando se desinstala la aplicación. Seguir mandándole avisos gasta
     * cuota y llena el registro de errores, así que se retira en cuanto Expo lo declara inservible.
     */
    @Test
    void retiraElTokenQueExpoDeclaraInservible() {
        when(devices.deUsuario(USUARIO)).thenReturn(List.of(dispositivo("tok-viejo"), dispositivo("tok-bueno")));
        respondeCon(Map.of("data", List.of(
                Map.of("status", "error", "details", Map.of("error", "DeviceNotRegistered")),
                Map.of("status", "ok"))));

        sender.reparte(USUARIO, "t", "c", Map.of());

        verify(devices).retiraToken("tok-viejo");
        verify(devices, never()).retiraToken("tok-bueno");
    }

    /** Otros errores de Expo no retiran el token: pueden ser temporales. */
    @Test
    void noRetiraPorUnErrorCualquiera() {
        when(devices.deUsuario(USUARIO)).thenReturn(List.of(dispositivo("tok-1")));
        respondeCon(Map.of("data", List.of(
                Map.of("status", "error", "details", Map.of("error", "MessageRateExceeded")))));

        sender.reparte(USUARIO, "t", "c", Map.of());

        verify(devices, never()).retiraToken(anyString());
    }

    /**
     * Un aviso que no sale NO puede tumbar lo que lo provocó: el mensaje ya está en el buzón y el
     * pedido ya está hecho. Se registra y se sigue.
     */
    @SuppressWarnings("unchecked")
    @Test
    void unFalloDeExpoNoTumbaLoQueLoLlamo() {
        when(devices.deUsuario(USUARIO)).thenReturn(List.of(dispositivo("tok-1")));
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenThrow(new IllegalStateException("expo caído"));

        assertThatCode(() -> sender.reparte(USUARIO, "t", "c", Map.of())).doesNotThrowAnyException();
    }

    /** Una respuesta sin la lista de resultados no puede reventar el reparto. */
    @Test
    void aguantaUnaRespuestaSinResultados() {
        when(devices.deUsuario(USUARIO)).thenReturn(List.of(dispositivo("tok-1")));
        respondeCon(Map.of("errors", List.of()));

        assertThatCode(() -> sender.reparte(USUARIO, "t", "c", Map.of())).doesNotThrowAnyException();
        verify(devices, never()).retiraToken(anyString());
    }
}
