package com.nexaplatform.dropshipping.infrastructure.campaign;

import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.application.service.CountryCurrencyService;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OutboundEmailRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Campaña diaria de "nuevos productos".
 *
 * <p>Es un envío masivo automático: los errores aquí se multiplican por toda la base de usuarios. Las dos
 * reglas que no se pueden romper son no enviar dos veces al mismo usuario el mismo día y no enviar un
 * correo vacío — ambas cosas queman la reputación del dominio y provocan bajas.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov04NewProductsCampaignServiceTest {

    private static final String TEMPLATE = "emails/new-products";
    /**
     * El envío de PRUEBA usa su propia plantilla: la campaña se deduplica por (destinatario, plantilla,
     * día), así que mandar la prueba con el nombre de la real dejaba al destinatario marcado como «ya
     * recibido» y el barrido de ese día se lo saltaba.
     */
    private static final String TEMPLATE_TEST = "emails/new-products-test";
    private static final String TIENDA = "https://tienda.test";
    private static final String BACKEND = "https://api.test";

    @Mock
    ProductRepository productRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    OutboundEmailRepository outboundEmailRepository;
    @Mock
    CatalogStorefrontReadService storefrontRead;
    @Mock
    EmailQueueService emailQueue;
    @Mock
    MarketingUnsubscribeService unsubscribeService;
    @Mock
    CountryCurrencyService countryCurrencyService;

    private NewProductsCampaignService service;
    private UUID categoriaId;

    @BeforeEach
    void setUp() {
        service = new NewProductsCampaignService(productRepository, userRepository, outboundEmailRepository,
                storefrontRead, emailQueue, countryCurrencyService, unsubscribeService, TIENDA, BACKEND);
        categoriaId = UUID.randomUUID();
        when(unsubscribeService.tokenFor(any(UUID.class))).thenReturn("tok en+/=");
    }

    private static ProductSummaryView producto(String titulo) {
        return new ProductSummaryView(UUID.randomUUID(), "slug", titulo, "https://img/1.jpg",
                BigDecimal.ONE, "CNY", BigDecimal.valueOf(4.5), 0, 10, BigDecimal.ONE, "ACTIVE",
                BigDecimal.TEN, BigDecimal.TEN, "EUR", "€", "10,00 €", 5, 5, true);
    }

    private static CategoryView categoria(UUID id, String nombre) {
        return new CategoryView(id, "moda", nombre, "时尚", null, 0, null, 3, List.of());
    }

    /** Deja el catálogo con UNA categoría que sí tiene novedades hoy. */
    private void catalogoConNovedades() {
        when(productRepository.findCategoryIdsWithProductsIngestedSince(eq(ProductStatus.ACTIVE),
                any(Instant.class))).thenReturn(List.of(categoriaId));
        when(storefrontRead.productsByCategory(eq(categoriaId.toString()), anyInt(), anyInt(), anyString(),
                eq("newest"))).thenReturn(new PageResponse<>(List.of(producto("Camiseta")), 0, 3, 1, 1));
        when(storefrontRead.categoryDetail(eq(categoriaId.toString()), anyString()))
                .thenReturn(categoria(categoriaId, "Moda"));
    }

    private static UserEntity usuario(String email, String pais, String idioma) {
        UserEntity u = UserEntity.builder().email(email).country(pais).language(idioma).active(true).build();
        u.setId(UUID.randomUUID());
        return u;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> varsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    // ── Cuándo NO se envía ───────────────────────────────────────────────────────────────────────

    @Test
    void sinPaisesEnHorarioDeEnvioNoSeConsultaSiquieraElCatalogo() {
        assertThat(service.sendForCountries(null)).isZero();
        assertThat(service.sendForCountries(Set.of())).isZero();
        verifyNoInteractions(productRepository, emailQueue);
    }

    @Test
    void siHoyNoSeHaIngeridoNingunProductoNoHayCampana() {
        when(productRepository.findCategoryIdsWithProductsIngestedSince(eq(ProductStatus.ACTIVE),
                any(Instant.class))).thenReturn(List.of());

        assertThat(service.sendForCountries(Set.of("ES"))).isZero();
        verifyNoInteractions(emailQueue);
    }

    @Test
    void soloRecibenLosUsuariosDeLosPaisesCuyaHoraLocalEsLaDelEnvio() {
        catalogoConNovedades();
        when(userRepository.findByActiveTrueAndMarketingOptOutFalse())
                .thenReturn(List.of(usuario("es@test", "ES", "es"), usuario("mx@test", "MX", "es")));

        int enviados = service.sendForCountries(Set.of("ES"));

        assertThat(enviados).isEqualTo(1);
        verify(emailQueue).enqueue(eq("es@test"), anyString(), eq(TEMPLATE), any());
    }

    @Test
    void unUsuarioSinPaisOSinCorreoNoEntraEnLaCampana() {
        // Sin país no se sabe si le toca ahora; sin correo no hay a dónde enviarlo.
        catalogoConNovedades();
        when(userRepository.findByActiveTrueAndMarketingOptOutFalse())
                .thenReturn(List.of(usuario(null, "ES", "es"), usuario("sinpais@test", null, "es")));

        assertThat(service.sendForCountries(Set.of("ES"))).isZero();
        verifyNoInteractions(emailQueue);
    }

    @Test
    void elPaisDelUsuarioSeComparaSinEspaciosNiMinusculas() {
        catalogoConNovedades();
        when(userRepository.findByActiveTrueAndMarketingOptOutFalse())
                .thenReturn(List.of(usuario("es@test", " es ", "es")));

        assertThat(service.sendForCountries(Set.of("ES"))).isEqualTo(1);
    }

    @Test
    void unUsuarioQueYaRecibioLaCampanaHoyNoLaRecibeDosVeces() {
        catalogoConNovedades();
        when(userRepository.findByActiveTrueAndMarketingOptOutFalse())
                .thenReturn(List.of(usuario("es@test", "ES", "es")));
        when(outboundEmailRepository.existsByToAddressAndTemplateAndCreatedAtGreaterThanEqual(
                eq("es@test"), eq(TEMPLATE), any(Instant.class))).thenReturn(true);

        assertThat(service.sendForCountries(Set.of("ES"))).isZero();
        verifyNoInteractions(emailQueue);
    }

    @Test
    void siEnSuIdiomaNoHayNadaQueEnsenarNoSeMandaUnCorreoVacio() {
        when(productRepository.findCategoryIdsWithProductsIngestedSince(eq(ProductStatus.ACTIVE),
                any(Instant.class))).thenReturn(List.of(categoriaId));
        when(storefrontRead.productsByCategory(anyString(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(new PageResponse<>(List.of(), 0, 3, 0, 0));
        when(userRepository.findByActiveTrueAndMarketingOptOutFalse())
                .thenReturn(List.of(usuario("es@test", "ES", "es")));

        assertThat(service.sendForCountries(Set.of("ES"))).isZero();
        verifyNoInteractions(emailQueue);
    }

    // ── Qué se envía ─────────────────────────────────────────────────────────────────────────────

    @Test
    void elCorreoLlevaLasCategoriasConNovedadesElCtaYElEnlaceDeBajaDelUsuario() {
        catalogoConNovedades();
        when(userRepository.findByActiveTrueAndMarketingOptOutFalse())
                .thenReturn(List.of(usuario("es@test", "ES", "es")));

        service.sendForCountries(Set.of("ES"));

        ArgumentCaptor<Map<String, Object>> captor = varsCaptor();
        verify(emailQueue).enqueue(eq("es@test"), anyString(), eq(TEMPLATE), captor.capture());
        Map<String, Object> vars = captor.getValue();
        assertThat(vars).containsEntry("ctaUrl", TIENDA + "/catalog?sort=newest");
        assertThat((List<?>) vars.get("categories")).hasSize(1);
        // El token va URL-encoded: sin codificar, un '+' del HMAC llegaría como espacio y la baja fallaría.
        assertThat((String) vars.get("unsubscribeUrl")).startsWith(BACKEND + "/api/campaigns/unsubscribe")
                .contains("lang=es").contains("token=tok+en%2B%2F%3D");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cadaCategoriaYCadaProductoLlevanSuEnlaceAlEscaparate() {
        // El correo agrupaba las novedades por categoría pero no dejaba entrar en ninguna: el único enlace
        // era el botón final, que lleva al catálogo entero y obliga a buscar otra vez lo que ya se enseñaba.
        catalogoConNovedades();
        when(userRepository.findByActiveTrueAndMarketingOptOutFalse())
                .thenReturn(List.of(usuario("es@test", "ES", "es")));

        service.sendForCountries(Set.of("ES"));

        ArgumentCaptor<Map<String, Object>> captor = varsCaptor();
        verify(emailQueue).enqueue(eq("es@test"), anyString(), eq(TEMPLATE), captor.capture());
        List<Map<String, Object>> categorias = (List<Map<String, Object>>) captor.getValue().get("categories");
        assertThat(categorias).hasSize(1);
        assertThat(categorias.get(0)).containsEntry("url", TIENDA + "/catalog?categoryId=" + categoriaId);
        List<Map<String, Object>> productos = (List<Map<String, Object>>) categorias.get(0).get("products");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0)).containsEntry("url", TIENDA + "/catalog/slug");
    }

    @Test
    void elCorreoVaEnElIdiomaDelUsuarioYSinIdiomaCaeAEspanol() {
        catalogoConNovedades();
        when(userRepository.findByActiveTrueAndMarketingOptOutFalse())
                .thenReturn(List.of(usuario("en@test", "ES", "EN"), usuario("sin@test", "ES", null)));

        service.sendForCountries(Set.of("ES"));

        ArgumentCaptor<String> subjects = ArgumentCaptor.forClass(String.class);
        verify(emailQueue, times(2)).enqueue(anyString(), subjects.capture(), eq(TEMPLATE), any());
        assertThat(subjects.getAllValues().get(0)).isEqualTo("New products at NX036 · Today's arrivals");
        assertThat(subjects.getAllValues().get(1)).isEqualTo("Nuevos productos en NX036 · Novedades de hoy");
    }

    @Test
    void elModeloDeCategoriasSeConstruyeUnaVezPorIdiomaYNoPorUsuario() {
        // Cada construcción es una consulta de productos por categoría: repetirla por usuario convierte
        // una campaña de 10.000 correos en 10.000 barridos del catálogo.
        catalogoConNovedades();
        when(userRepository.findByActiveTrueAndMarketingOptOutFalse())
                .thenReturn(List.of(usuario("a@test", "ES", "es"), usuario("b@test", "ES", "es")));

        assertThat(service.sendForCountries(Set.of("ES"))).isEqualTo(2);
        verify(storefrontRead, times(1)).productsByCategory(anyString(), anyInt(), anyInt(), anyString(),
                anyString());
    }

    // ── Correo de prueba del panel admin ─────────────────────────────────────────────────────────

    @Test
    void elCorreoDePruebaIgnoraAudienciaYUsaLasPrimerasCategoriasConProductos() {
        when(storefrontRead.categoriesFlat("fr")).thenReturn(List.of(categoria(categoriaId, "Mode")));
        when(storefrontRead.productsByCategory(eq(categoriaId.toString()), anyInt(), anyInt(), eq("fr"),
                eq("newest"))).thenReturn(new PageResponse<>(List.of(producto("T-shirt")), 0, 3, 1, 1));
        when(storefrontRead.categoryDetail(categoriaId.toString(), "fr"))
                .thenReturn(categoria(categoriaId, "Mode"));
        when(userRepository.findByEmail("qa@test")).thenReturn(Optional.empty());

        assertThat(service.sendTest("qa@test", "FR")).isTrue();

        ArgumentCaptor<Map<String, Object>> captor = varsCaptor();
        verify(emailQueue).enqueue(eq("qa@test"), anyString(), eq(TEMPLATE_TEST), captor.capture());
        // Sin usuario registrado no hay token de baja: se enlaza la página de preferencias.
        assertThat(captor.getValue()).containsEntry("unsubscribeUrl", TIENDA + "/account/email-preferences");
        verifyNoInteractions(productRepository);
    }

    @Test
    void elCorreoDePruebaNoSeEnviaSiNoHayNadaQueEnsenar() {
        when(storefrontRead.categoriesFlat(anyString())).thenReturn(List.of());

        assertThat(service.sendTest("qa@test", null)).isFalse();
        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), any());
    }
}
