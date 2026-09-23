package com.nexaplatform.dropshipping.infrastructure.campaign;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.service.CountryCurrencyService;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OutboundEmailRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductViewRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Recordatorio «lo que has estado mirando».
 *
 * <p>Es un envío automático y masivo, así que lo que se fija aquí no es el formato del correo sino cuándo
 * NO sale: a quien no ha visitado nada, a quien se dio de baja y a quien ya lo recibió hace menos de tres
 * días. Un recordatorio de productos que el usuario no ha mirado no es un recordatorio, y cada envío de más
 * se paga en bajas.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Recordatorio de visitas · solo a quien miró algo, y una vez cada tres días")
class ViewedProductsDigestServiceTest {

    private static final String TEMPLATE = "emails/viewed-products";
    private static final String TIENDA = "https://tienda.test";
    private static final String BACKEND = "https://api.test";

    @Mock
    ProductViewRepository viewRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    OutboundEmailRepository outboundEmailRepository;
    @Mock
    CatalogStorefrontReadService storefrontRead;
    @Mock
    EmailQueueService emailQueue;
    @Mock
    CountryCurrencyService countryCurrencyService;
    @Mock
    MarketingUnsubscribeService unsubscribeService;

    private ViewedProductsDigestService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new ViewedProductsDigestService(viewRepository, userRepository, outboundEmailRepository,
                storefrontRead, emailQueue, countryCurrencyService, unsubscribeService, TIENDA, BACKEND);
        userId = UUID.randomUUID();
        when(unsubscribeService.tokenFor(any(UUID.class))).thenReturn("tok en+/=");
        when(countryCurrencyService.forCountry(anyString())).thenReturn("EUR");
    }

    private static ProductSummaryView producto(String titulo, String imagen, String slug) {
        return new ProductSummaryView(UUID.randomUUID(), slug, titulo, imagen, BigDecimal.ONE, "CNY",
                BigDecimal.valueOf(4.5), 0, 10, BigDecimal.ONE, "ACTIVE", BigDecimal.TEN, BigDecimal.TEN, "EUR", "€",
                "10,00 €", 5, 5, true);
    }

    private static UserEntity usuario(UUID id, String email, String idioma) {
        UserEntity u = UserEntity.builder().email(email).country("ES").language(idioma).active(true).build();
        u.setId(id);
        return u;
    }

    /** Un usuario activo con una ficha visitada dentro de la ventana: el caso que sí manda correo. */
    private void escenarioConUnaVisita() {
        when(viewRepository.findUserIdsWithViewsSince(any(Instant.class))).thenReturn(List.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario(userId, "yo@test", "es")));
        when(viewRepository.findProductIdsByUserIdSince(eq(userId), any(Instant.class), any()))
                .thenReturn(List.of(UUID.randomUUID()));
        when(storefrontRead.favorites(anyList(), anyInt(), anyInt(), anyString())).thenReturn(
                new PageResponse<>(List.of(producto("Camiseta", "https://cdn/1.jpg", "camiseta")), 0, 9, 1, 1));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> varsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> imagenesCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    /* ============================== Cuándo NO se envía ============================== */

    /**
     * El requisito explícito del dueño del producto: sin visitas en la ventana, ese usuario no recibe nada.
     * Ni siquiera se le mira el perfil, porque no hay nada que decidir.
     */
    @Test
    @DisplayName("nadie ha visitado nada: no se envía ni un correo")
    void sinVisitasEnLaVentanaNoSeEnviaNada() {
        when(viewRepository.findUserIdsWithViewsSince(any(Instant.class))).thenReturn(List.of());

        assertThat(service.sendDigests()).isZero();

        verifyNoInteractions(userRepository, emailQueue, storefrontRead);
    }

    /**
     * Lo que se le pregunta a la base es «visitas desde hace tres días», no «todas las visitas». Sin este
     * corte, el recordatorio enseñaría fichas de hace meses como si acabara de mirarlas.
     */
    @Test
    @DisplayName("la ventana que se consulta es exactamente de tres días")
    void laVentanaConsultadaEsDeTresDias() {
        when(viewRepository.findUserIdsWithViewsSince(any(Instant.class))).thenReturn(List.of());
        Instant antes = Instant.now();

        service.sendDigests();

        ArgumentCaptor<Instant> desde = ArgumentCaptor.forClass(Instant.class);
        verify(viewRepository).findUserIdsWithViewsSince(desde.capture());
        Duration ventana = Duration.between(desde.getValue(), antes);
        assertThat(ventana).isBetween(Duration.ofDays(3).minusMinutes(1), Duration.ofDays(3).plusMinutes(1));
    }

    /**
     * Mismo corte al listar las fichas del usuario: quien entró en la ventana por una visita de ayer no
     * puede recibir además lo que miró hace un mes.
     */
    @Test
    @DisplayName("las visitas anteriores a la ventana no entran en la cuadrícula")
    void lasVisitasFueraDeLaVentanaNoEntran() {
        when(viewRepository.findUserIdsWithViewsSince(any(Instant.class))).thenReturn(List.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario(userId, "yo@test", "es")));
        // Todo lo que tenía es más viejo que la ventana: la consulta acotada no devuelve nada.
        when(viewRepository.findProductIdsByUserIdSince(eq(userId), any(Instant.class), any())).thenReturn(List.of());

        assertThat(service.sendDigests()).isZero();

        verifyNoInteractions(emailQueue);
        // Y no se llega siquiera a montar el modelo: no hay nada que leer del catálogo.
        verifyNoInteractions(storefrontRead);
    }

    /**
     * El correo enviado ES la marca que espacia los envíos: mientras haya uno dentro de la ventana, no sale
     * otro. Es lo que convierte un barrido diario en un recordatorio cada tres días por persona.
     */
    @Test
    @DisplayName("quien ya lo recibió hace menos de tres días no lo recibe otra vez")
    void noSeRepiteDentroDeLaVentana() {
        escenarioConUnaVisita();
        when(outboundEmailRepository.existsByToAddressAndTemplateAndCreatedAtGreaterThanEqual(eq("yo@test"),
                eq(TEMPLATE), any(Instant.class))).thenReturn(true);

        assertThat(service.sendDigests()).isZero();

        verifyNoInteractions(emailQueue);
    }

    /** Es comunicación comercial: quien pulsó «dejar de recibir» deja de recibir, y punto. */
    @Test
    @DisplayName("quien se dio de baja de marketing no recibe el recordatorio")
    void elOptOutDeMarketingLoDejaFuera() {
        escenarioConUnaVisita();
        UserEntity dadoDeBaja = usuario(userId, "yo@test", "es");
        dadoDeBaja.setMarketingOptOut(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(dadoDeBaja));

        assertThat(service.sendDigests()).isZero();

        verifyNoInteractions(emailQueue);
    }

    @Test
    @DisplayName("una cuenta desactivada o sin correo tampoco recibe")
    void cuentaInactivaOSinCorreoNoRecibe() {
        UUID otro = UUID.randomUUID();
        when(viewRepository.findUserIdsWithViewsSince(any(Instant.class))).thenReturn(List.of(userId, otro));
        UserEntity inactivo = usuario(userId, "yo@test", "es");
        inactivo.setActive(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(inactivo));
        when(userRepository.findById(otro)).thenReturn(Optional.of(usuario(otro, null, "es")));

        assertThat(service.sendDigests()).isZero();

        verifyNoInteractions(emailQueue);
    }

    /**
     * El caso que motiva la prueba: entre que el usuario miró la ficha y sale el correo, el producto se
     * retiró del catálogo. No puede reventar el envío ni salir un correo con la cuadrícula vacía.
     */
    @Test
    @DisplayName("si el producto visitado se retiró, no se manda un correo vacío ni revienta el barrido")
    void unProductoRetiradoNoRompeElEnvio() {
        escenarioConUnaVisita();
        // El listado por IDs descarta lo que ya no está activo: no queda nada que enseñar.
        when(storefrontRead.favorites(anyList(), anyInt(), anyInt(), anyString()))
                .thenReturn(new PageResponse<>(List.of(), 0, 9, 0, 0));

        assertThat(service.sendDigests()).isZero();

        verifyNoInteractions(emailQueue);
    }

    /* ============================== Qué se envía ============================== */

    @Test
    @DisplayName("el correo lleva la cuadrícula, el enlace al historial y el de baja del usuario")
    void elCorreoLlevaCuadriculaCtaYBaja() {
        escenarioConUnaVisita();

        assertThat(service.sendDigests()).isEqualTo(1);

        ArgumentCaptor<Map<String, Object>> captor = varsCaptor();
        verify(emailQueue).enqueue(eq("yo@test"), eq(null), anyString(), eq(TEMPLATE), captor.capture(), any());
        Map<String, Object> vars = captor.getValue();
        assertThat(vars).containsEntry("ctaUrl", TIENDA + "/history");
        assertThat((List<?>) vars.get("rows")).hasSize(1);
        // El token va URL-encoded: sin codificar, un '+' del HMAC llegaría como espacio y la baja fallaría.
        assertThat((String) vars.get("unsubscribeUrl")).startsWith(BACKEND + "/api/campaigns/unsubscribe")
                .contains("lang=es").contains("token=tok+en%2B%2F%3D");
    }

    /**
     * Las fotos viajan DENTRO del mensaje. Con la URL del storage no se ven —en local es localhost, y
     * Outlook y Apple Mail bloquean las remotas—, y un correo cuya gracia es la cuadrícula de fotos no
     * puede depender de que el cliente decida cargarlas.
     */
    @Test
    @DisplayName("las fotos se adjuntan al mensaje (cid:), no se enlazan por URL")
    void lasFotosVanAdjuntasPorCid() {
        escenarioConUnaVisita();

        service.sendDigests();

        ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
        ArgumentCaptor<Map<String, String>> imagenes = imagenesCaptor();
        verify(emailQueue).enqueue(anyString(), eq(null), anyString(), eq(TEMPLATE), vars.capture(),
                imagenes.capture());
        assertThat(imagenes.getValue()).containsExactly(Map.entry("vp0", "https://cdn/1.jpg"));
        List<?> filas = (List<?>) vars.getValue().get("rows");
        @SuppressWarnings("unchecked")
        Map<String, Object> celda = ((List<Map<String, Object>>) filas.get(0)).get(0);
        assertThat(celda).containsEntry("image", "cid:vp0").containsEntry("url", TIENDA + "/catalog/camiseta")
                .containsEntry("price", "10,00 €");
    }

    /** La cuadrícula se sirve por filas de tres: siete fichas son tres filas, la última con una sola. */
    @Test
    @DisplayName("la cuadrícula se reparte en filas de tres")
    void laCuadriculaSeParteEnFilasDeTres() {
        escenarioConUnaVisita();
        List<ProductSummaryView> siete = List.of(producto("a", "i", "a"), producto("b", "i", "b"),
                producto("c", "i", "c"), producto("d", "i", "d"), producto("e", "i", "e"), producto("f", "i", "f"),
                producto("g", "i", "g"));
        when(storefrontRead.favorites(anyList(), anyInt(), anyInt(), anyString()))
                .thenReturn(new PageResponse<>(siete, 0, 9, 7, 1));

        service.sendDigests();

        ArgumentCaptor<Map<String, Object>> captor = varsCaptor();
        verify(emailQueue).enqueue(anyString(), eq(null), anyString(), eq(TEMPLATE), captor.capture(), any());
        @SuppressWarnings("unchecked")
        List<List<Map<String, Object>>> filas = (List<List<Map<String, Object>>>) captor.getValue().get("rows");
        assertThat(filas).hasSize(3);
        assertThat(filas.get(0)).hasSize(3);
        assertThat(filas.get(2)).hasSize(1);
    }

    @Test
    @DisplayName("el correo sale en el idioma del usuario y sin idioma cae a español")
    void elCorreoVaEnElIdiomaDelUsuario() {
        UUID otro = UUID.randomUUID();
        when(viewRepository.findUserIdsWithViewsSince(any(Instant.class))).thenReturn(List.of(userId, otro));
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario(userId, "en@test", "EN")));
        when(userRepository.findById(otro)).thenReturn(Optional.of(usuario(otro, "sin@test", null)));
        when(viewRepository.findProductIdsByUserIdSince(any(UUID.class), any(Instant.class), any()))
                .thenReturn(List.of(UUID.randomUUID()));
        when(storefrontRead.favorites(anyList(), anyInt(), anyInt(), anyString())).thenReturn(
                new PageResponse<>(List.of(producto("Camiseta", "https://cdn/1.jpg", "camiseta")), 0, 9, 1, 1));

        assertThat(service.sendDigests()).isEqualTo(2);

        ArgumentCaptor<String> asuntos = ArgumentCaptor.forClass(String.class);
        verify(emailQueue, times(2)).enqueue(anyString(), eq(null), asuntos.capture(), eq(TEMPLATE), any(), any());
        assertThat(asuntos.getAllValues().get(0)).isEqualTo("What you've been looking at on NX036");
        assertThat(asuntos.getAllValues().get(1)).isEqualTo("Lo que has estado mirando en NX036");
    }

    /**
     * Un destinatario que falla no puede dejar sin correo a los que van detrás. Es la otra cara de no
     * envolver el lote en una transacción: el barrido sigue y el fallo se queda en ese usuario.
     */
    @Test
    @DisplayName("un usuario que falla no interrumpe el envío del resto")
    void unUsuarioQueFallaNoCortaElLote() {
        UUID roto = UUID.randomUUID();
        when(viewRepository.findUserIdsWithViewsSince(any(Instant.class))).thenReturn(List.of(roto, userId));
        when(userRepository.findById(roto)).thenThrow(new IllegalStateException("base caída"));
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario(userId, "yo@test", "es")));
        when(viewRepository.findProductIdsByUserIdSince(eq(userId), any(Instant.class), any()))
                .thenReturn(List.of(UUID.randomUUID()));
        when(storefrontRead.favorites(anyList(), anyInt(), anyInt(), anyString())).thenReturn(
                new PageResponse<>(List.of(producto("Camiseta", "https://cdn/1.jpg", "camiseta")), 0, 9, 1, 1));

        assertThat(service.sendDigests()).isEqualTo(1);

        verify(emailQueue).enqueue(eq("yo@test"), eq(null), anyString(), eq(TEMPLATE), any(), any());
    }
}
