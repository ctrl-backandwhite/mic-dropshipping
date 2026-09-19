package com.nexaplatform.dropshipping.infrastructure.campaign;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryView;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.service.CountryCurrencyService;
import com.nexaplatform.dropshipping.domain.enums.NewProductsEmailLabel;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OutboundEmailRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Campaña diaria "Nuevos productos": si hoy se han ingerido productos, envía a cada usuario (activo, sin
 * opt-out de marketing) un correo con al menos 3 productos de cada categoría que tenga novedades hoy, en
 * su idioma, y un CTA al catálogo ordenado por "más nuevos". Se dispara por país cuando su hora local
 * llega a las 18:00 (ver {@link NewProductsCampaignScheduler}).
 */
@Service
public class NewProductsCampaignService {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String TITLE = "title";

    private static final Logger log = LoggerFactory.getLogger(NewProductsCampaignService.class);
    private static final String TEMPLATE = "emails/new-products";
    /**
     * Plantilla del envío de PRUEBA. Es la misma pieza con otro nombre porque la campaña se deduplica
     * por (destinatario, plantilla, día): mandar la prueba con el nombre real dejaba al destinatario
     * marcado como «ya recibido» y el barrido de ese día se lo saltaba.
     */
    private static final String TEMPLATE_TEST = "emails/new-products-test";
    private static final int PRODUCTS_PER_CATEGORY = 3;

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OutboundEmailRepository outboundEmailRepository;
    private final CatalogStorefrontReadService storefrontRead;
    private final EmailQueueService emailQueue;
    private final CountryCurrencyService countryCurrencyService;
    private final MarketingUnsubscribeService unsubscribeService;
    private final String storefrontBaseUrl;
    private final String backendBaseUrl;

    public NewProductsCampaignService(ProductRepository productRepository, UserRepository userRepository,
            OutboundEmailRepository outboundEmailRepository, CatalogStorefrontReadService storefrontRead,
            EmailQueueService emailQueue, CountryCurrencyService countryCurrencyService,
            MarketingUnsubscribeService unsubscribeService,
            @Value("${nexadrop.storefront.base-url:http://localhost:3003}") String storefrontBaseUrl,
            @Value("${nexadrop.oauth.issuer:http://localhost:18082}") String backendBaseUrl) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.outboundEmailRepository = outboundEmailRepository;
        this.storefrontRead = storefrontRead;
        this.emailQueue = emailQueue;
        this.countryCurrencyService = countryCurrencyService;
        this.unsubscribeService = unsubscribeService;
        this.storefrontBaseUrl = storefrontBaseUrl;
        this.backendBaseUrl = backendBaseUrl;
    }

    /** URL de baja de un clic para un usuario, en su idioma. */
    private String unsubscribeUrl(UUID userId, String lang) {
        return backendBaseUrl + "/api/campaigns/unsubscribe?lang=" + lang + "&token="
                + URLEncoder.encode(unsubscribeService.tokenFor(userId), StandardCharsets.UTF_8);
    }

    /**
     * Envía la campaña a los usuarios cuyos países están en {@code countries} (su hora local es 18:00).
     * No hace nada si hoy no hay productos nuevos. Deduplica: un usuario recibe como máximo un correo hoy.
     *
     * @return número de correos encolados.
     */
    @Transactional
    public int sendForCountries(Set<String> countries) {
        if (countries == null || countries.isEmpty()) {
            return 0;
        }
        Instant since = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<UUID> categoryIds = productRepository.findCategoryIdsWithProductsIngestedSince(ProductStatus.ACTIVE, since);
        if (categoryIds.isEmpty()) {
            return 0;
        }
        List<UserEntity> audience = userRepository.findByActiveTrueAndMarketingOptOutFalse().stream()
                .filter(u -> u.getEmail() != null && u.getCountry() != null
                        && countries.contains(u.getCountry().trim().toUpperCase()))
                .toList();
        if (audience.isEmpty()) {
            return 0;
        }

        String ctaUrl = storefrontBaseUrl + "/catalog?sort=newest";
        Map<String, List<Map<String, Object>>> categoriesByLang = new HashMap<>();
        int sent = 0;
        for (UserEntity user : audience) {
            if (enqueueIfDue(user, since, ctaUrl, categoryIds, categoriesByLang)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.info("New-products campaign: {} emails enqueued (countries={}, categories={})", sent, countries,
                    categoryIds.size());
        }
        return sent;
    }

    /**
     * Envía un correo de prueba de la campaña a una dirección concreta, en el idioma dado. Ignora audiencia,
     * opt-out, país y deduplicación, y — para que siempre tenga contenido — usa las primeras categorías con
     * productos (sin filtrar por fecha de ingesta). Pensado para verificar el formato desde el panel admin.
     *
     * @return true si se encoló (había contenido que mostrar).
     */
    @Transactional
    public boolean sendTest(String email, String lang) {
        String language = normalizeLang(lang);
        List<UUID> categoryIds = storefrontRead.categoriesFlat(language).stream()
                .map(CategoryView::id).limit(12).toList();
        // La prueba tiene que salir EXACTAMENTE como el envío real, divisa incluida: si aquí se vieran
        // euros y en el envío real dólares, la prueba dejaría de servir para lo único que sirve.
        String divisa = userRepository.findByEmail(email)
                .map(u -> countryCurrencyService.forCountry(u.getCountry()))
                .orElse("USD");
        List<Map<String, Object>> categories = conDivisa(divisa,
                () -> buildCategories(categoryIds, language)).stream().limit(4).toList();
        if (categories.isEmpty()) {
            return false;
        }
        String unsubscribeUrl = userRepository.findByEmail(email)
                .map(u -> unsubscribeUrl(u.getId(), language))
                .orElse(storefrontBaseUrl + "/account/email-preferences");
        Map<String, Object> vars = new HashMap<>();
        vars.put(TITLE, NewProductsEmailLabel.TITLE.of(language));
        vars.put("intro", NewProductsEmailLabel.INTRO.of(language));
        vars.put("categories", categories);
        vars.put("ctaUrl", storefrontBaseUrl + "/catalog?sort=newest");
        vars.put("ctaLabel", NewProductsEmailLabel.CTA.of(language));
        vars.put("footerNote", NewProductsEmailLabel.FOOTER.of(language));
        vars.put("unsubscribeUrl", unsubscribeUrl);
        vars.put("unsubscribeLabel", NewProductsEmailLabel.UNSUBSCRIBE.of(language));
        emailQueue.enqueue(email, NewProductsEmailLabel.SUBJECT.of(language), TEMPLATE_TEST, vars);
        return true;
    }

    /**
     * Encola la campaña para UN usuario, si le toca. No le toca cuando ya recibió esta plantilla hoy
     * (deduplicación: como máximo un correo al día) o cuando en su idioma no hay contenido que enseñar
     * — preferimos no enviar nada antes que un correo vacío.
     *
     * @param categoriesByLang caché por idioma dentro de la misma tanda: el modelo de categorías es caro
     *        (una consulta de productos por categoría) y se repite para todos los usuarios de un idioma.
     * @return true si el correo se encoló.
     */
    private boolean enqueueIfDue(UserEntity user, Instant since, String ctaUrl, List<UUID> categoryIds,
            Map<String, List<Map<String, Object>>> categoriesByLang) {
        if (outboundEmailRepository.existsByToAddressAndTemplateAndCreatedAtGreaterThanEqual(
                user.getEmail(), TEMPLATE, since)) {
            return false;
        }
        String lang = normalizeLang(user.getLanguage());
        // Divisa del país con el que el usuario se dio de alta. Sin esto, el correo se genera fuera de
        // cualquier petición HTTP, CurrencyHolder está vacío y los precios salen en dólares para todo el
        // mundo: un usuario español recibía la campaña con importes que no puede comparar con lo que verá
        // en la tienda.
        String divisa = countryCurrencyService.forCountry(user.getCountry());
        // La caché es por idioma Y DIVISA: el modelo lleva los precios ya formateados dentro, así que dos
        // usuarios del mismo idioma en países con distinta divisa no pueden compartirlo.
        String claveCache = lang + "|" + divisa;
        List<Map<String, Object>> categories = categoriesByLang.computeIfAbsent(claveCache,
                c -> conDivisa(divisa, () -> buildCategories(categoryIds, lang)));
        if (categories.isEmpty()) {
            return false;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put(TITLE, NewProductsEmailLabel.TITLE.of(lang));
        vars.put("intro", NewProductsEmailLabel.INTRO.of(lang));
        vars.put("categories", categories);
        vars.put("ctaUrl", ctaUrl);
        vars.put("ctaLabel", NewProductsEmailLabel.CTA.of(lang));
        vars.put("footerNote", NewProductsEmailLabel.FOOTER.of(lang));
        vars.put("unsubscribeUrl", unsubscribeUrl(user.getId(), lang));
        vars.put("unsubscribeLabel", NewProductsEmailLabel.UNSUBSCRIBE.of(lang));
        emailQueue.enqueue(user.getEmail(), NewProductsEmailLabel.SUBJECT.of(lang), TEMPLATE, vars);
        return true;
    }

    /**
     * Ejecuta la construcción del modelo con la divisa dada, y deja el hilo como estaba.
     *
     * <p>El precio formateado sale de {@code CurrencyHolder}, que normalmente rellena un filtro HTTP. Aquí
     * no hay petición, así que se fija a mano — y se restaura en un {@code finally} porque el hilo se
     * reutiliza: dejarlo apuntando a la divisa del último usuario contaminaría lo siguiente que corriera.
     */
    private <T> T conDivisa(String divisa, java.util.function.Supplier<T> accion) {
        String anterior = CurrencyHolder.get();
        try {
            CurrencyHolder.set(divisa);
            return accion.get();
        } finally {
            CurrencyHolder.set(anterior);
        }
    }

    /** Modelo de categorías→productos para el idioma dado; solo categorías con al menos un producto visible. */
    private List<Map<String, Object>> buildCategories(List<UUID> categoryIds, String lang) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID categoryId : categoryIds) {
            PageResponse<ProductSummaryView> page = storefrontRead.productsByCategory(categoryId.toString(), 0,
                    PRODUCTS_PER_CATEGORY, lang, "newest");
            if (page.items().isEmpty()) {
                continue;
            }
            List<Map<String, Object>> products = new ArrayList<>();
            for (ProductSummaryView p : page.items()) {
                Map<String, Object> item = new HashMap<>();
                item.put(TITLE, p.title());
                item.put("price", p.displayFormatted());
                item.put("image", p.mainImage());
                item.put("url", storefrontBaseUrl + "/catalog/" + p.slug());
                products.add(item);
            }
            CategoryView category = storefrontRead.categoryDetail(categoryId.toString(), lang);
            Map<String, Object> group = new HashMap<>();
            group.put("name", category.name());
            // Enlace a la categoría en el escaparate. Sin esto el correo enseñaba las novedades agrupadas por
            // categoría pero no dejaba entrar en ninguna: el único enlace era el botón final, que lleva al
            // catálogo entero ordenado por novedad y obliga a buscar otra vez lo que el correo ya mostraba.
            group.put("url", storefrontBaseUrl + "/catalog?categoryId=" + categoryId);
            group.put("products", products);
            out.add(group);
        }
        return out;
    }

    private String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return "es";
        }
        return lang.trim().toLowerCase();
    }
}
