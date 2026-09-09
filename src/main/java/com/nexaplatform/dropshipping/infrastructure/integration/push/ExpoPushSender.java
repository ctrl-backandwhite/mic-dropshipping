package com.nexaplatform.dropshipping.infrastructure.integration.push;

import com.nexaplatform.dropshipping.domain.model.UserDevice;
import com.nexaplatform.dropshipping.domain.repository.UserDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reparte un aviso a los dispositivos de una persona a través del servicio de Expo.
 *
 * <p>Se usa el servicio de Expo y no FCM/APNs directamente porque la aplicación se compila con Expo:
 * su servicio ya sabe hablar con las dos tiendas y evita tener que guardar en el servidor las claves
 * de firma de Apple y de Google.
 *
 * <p><b>Nunca tumba lo que lo llamó.</b> Un aviso que no sale no puede deshacer un pedido ni impedir
 * que el mensaje quede en el buzón: se registra y se sigue. El buzón dentro de la aplicación es la
 * fuente de verdad; el aviso del sistema es solo el toque en el hombro.
 */
@Slf4j
@Service
public class ExpoPushSender {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    /** Expo acepta cien mensajes por llamada; más de eso hay que trocearlo. */
    private static final int TAMANO_DE_LOTE = 100;

    /** Códigos con los que Expo dice que ese token ya no sirve: se retira en vez de reintentarlo. */
    private static final String TOKEN_INSERVIBLE = "DeviceNotRegistered";

    @Value("${nexadrop.push.enabled:false}")
    private boolean enabled;

    @Value("${nexadrop.push.expo-url:https://exp.host/--/api/v2/push/send}")
    private String expoUrl;

    private final WebClient.Builder webClientBuilder;
    private final UserDeviceRepository devices;

    public ExpoPushSender(WebClient.Builder webClientBuilder, UserDeviceRepository devices) {
        this.webClientBuilder = webClientBuilder;
        this.devices = devices;
    }

    /** Manda el aviso a todos los dispositivos del usuario. Sin dispositivos no hace nada. */
    public void reparte(UUID userId, String titulo, String cuerpo, Map<String, Object> datos) {
        if (!enabled) {
            log.debug("Avisos push desactivados; no se reparte a {}", userId);
            return;
        }
        List<UserDevice> destinos = devices.deUsuario(userId);
        if (destinos.isEmpty()) {
            return;
        }
        for (int desde = 0; desde < destinos.size(); desde += TAMANO_DE_LOTE) {
            List<UserDevice> lote = destinos.subList(desde, Math.min(desde + TAMANO_DE_LOTE, destinos.size()));
            enviaLote(lote, titulo, cuerpo, datos);
        }
    }

    private void enviaLote(List<UserDevice> lote, String titulo, String cuerpo, Map<String, Object> datos) {
        List<Map<String, Object>> mensajes = new ArrayList<>();
        for (UserDevice destino : lote) {
            Map<String, Object> mensaje = new java.util.HashMap<>();
            mensaje.put("to", destino.getPushToken());
            mensaje.put("title", titulo);
            mensaje.put("body", cuerpo);
            mensaje.put("sound", "default");
            if (datos != null && !datos.isEmpty()) {
                mensaje.put("data", datos);
            }
            mensajes.add(mensaje);
        }

        try {
            Map<String, Object> respuesta = webClientBuilder.build().post().uri(expoUrl)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(mensajes).retrieve().bodyToMono(MAP_TYPE)
                    .timeout(Duration.ofSeconds(10)).block();
            retiraLosQueYaNoSirven(lote, respuesta);
        } catch (RuntimeException e) {
            // Un aviso que no sale no puede tumbar lo que lo provocó: el mensaje ya está en el buzón.
            log.warn("No se han podido repartir {} avisos push: {}", lote.size(), e.getMessage());
        }
    }

    /**
     * Un token de Expo caduca cuando se desinstala la aplicación. Seguir mandándole avisos gasta
     * cuota y llena el registro de errores, así que en cuanto Expo lo declara inservible se retira.
     */
    private void retiraLosQueYaNoSirven(List<UserDevice> lote, Map<String, Object> respuesta) {
        if (respuesta == null || !(respuesta.get("data") instanceof List<?> resultados)) {
            return;
        }
        for (int i = 0; i < resultados.size() && i < lote.size(); i++) {
            if (!(resultados.get(i) instanceof Map<?, ?> resultado)) {
                continue;
            }
            if (!"error".equals(resultado.get("status"))) {
                continue;
            }
            Object detalles = resultado.get("details");
            if (detalles instanceof Map<?, ?> mapa && TOKEN_INSERVIBLE.equals(mapa.get("error"))) {
                devices.retiraToken(lote.get(i).getPushToken());
            }
        }
    }
}
