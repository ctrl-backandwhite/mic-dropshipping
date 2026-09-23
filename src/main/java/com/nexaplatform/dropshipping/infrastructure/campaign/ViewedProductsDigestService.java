package com.nexaplatform.dropshipping.infrastructure.campaign;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.service.CountryCurrencyService;
import com.nexaplatform.dropshipping.domain.enums.ViewedProductsEmailLabel;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OutboundEmailRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductViewRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Correo recordatorio «lo que has estado mirando»: cada tres días, a quien haya abierto alguna ficha en esa
 * ventana, una cuadrícula con esos productos.
 *
 * <p>Quien no ha visitado nada NO recibe nada. Es la regla que sostiene toda la pieza: un correo con la
 * cuadrícula vacía —o peor, con productos que el usuario no ha mirado— no es un recordatorio, es publicidad
 * no pedida, y se paga en bajas y en reputación del dominio.
 *
 * <p>Es comunicación comercial, así que respeta el opt-out de marketing y lleva enlace de baja de un clic
 * ({@link MarketingUnsubscribeService}), el mismo de la campaña de novedades.
 */
@Service
public class ViewedProductsDigestService {

    private static final Logger log = LoggerFactory.getLogger(ViewedProductsDigestService.class);

    static final String TEMPLATE = "emails/viewed-products";

    /** Ventana del recordatorio: lo visitado en los últimos tres días, y como mucho un correo cada tres. */
    static final Duration VENTANA = Duration.ofDays(3);

    /**
     * Tope de fichas en la cuadrícula. Las imágenes viajan DENTRO del mensaje, así que cada producto de más
     * es peso real en el buzón del destinatario; nueve llenan una cuadrícula de tres por tres y es lo que
     * cabe sin que el correo se recorte en Gmail.
     */
    private static final int MAX_PRODUCTOS = 9;

    /** Celdas por fila de la cuadrícula. Tres de 180 px es lo que cabe en los 600 px de ancho del correo. */
    private static final int COLUMNAS = 3;

    /** Prefijo de los Content-ID de las fotos. El patrón que las busca solo admite letras, dígitos, - y _. */
    private static final String CID_PREFIJO = "vp";

    private final ProductViewRepository viewRepository;
    private final UserRepository userRepository;
    private final OutboundEmailRepository outboundEmailRepository;
    private final CatalogStorefrontReadService storefrontRead;
    private final EmailQueueService emailQueue;
    private final CountryCurrencyService countryCurrencyService;
    private final MarketingUnsubscribeService unsubscribeService;
    private final String storefrontBaseUrl;
    private final String backendBaseUrl;

    @SuppressWarnings("java:S107")
    public ViewedProductsDigestService(ProductViewRepository viewRepository, UserRepository userRepository,
            OutboundEmailRepository outboundEmailRepository, CatalogStorefrontReadService storefrontRead,
            EmailQueueService emailQueue, CountryCurrencyService countryCurrencyService,
            MarketingUnsubscribeService unsubscribeService,
            @Value("${nexadrop.storefront.base-url:http://localhost:3003}") String storefrontBaseUrl,
            @Value("${nexadrop.oauth.issuer:http://localhost:18082}") String backendBaseUrl) {
        this.viewRepository = viewRepository;
        this.userRepository = userRepository;
        this.outboundEmailRepository = outboundEmailRepository;
        this.storefrontRead = storefrontRead;
        this.emailQueue = emailQueue;
        this.countryCurrencyService = countryCurrencyService;
        this.unsubscribeService = unsubscribeService;
        this.storefrontBaseUrl = storefrontBaseUrl;
        this.backendBaseUrl = backendBaseUrl;
    }

    /**
     * Recorre los usuarios con visitas en la ventana y encola el recordatorio al que le toque.
     *
     * <p>NO es {@code @Transactional} a propósito, igual que el barrido de la cola de correo: cada correo se
     * escribe en SU propia transacción (la que abre {@code emailQueue.enqueue}). Con una sola transacción
     * para todo el lote, un fallo a mitad —un usuario cuyo modelo no se puede construir, la base que se
     * cae— revertiría también los correos YA encolados, y la siguiente pasada volvería a encolarlos: el
     * usuario recibe dos veces lo mismo. La lista de destinatarios se recorre entera aunque uno falle, por
     * el mismo motivo: un usuario roto no puede dejar sin correo a los que van detrás.
     *
     * @return cuántos correos se han encolado.
     */
    public int sendDigests() {
        Instant desde = Instant.now().minus(VENTANA);
        List<UUID> userIds = viewRepository.findUserIdsWithViewsSince(desde);
        if (userIds.isEmpty()) {
            return 0;
        }
        int enviados = 0;
        for (UUID userId : userIds) {
            try {
                if (enqueueIfDue(userId, desde)) {
                    enviados++;
                }
            } catch (RuntimeException ex) {
                log.warn("Recordatorio de visitas fallido para el usuario {}: {}", userId, ex.getMessage());
            }
        }
        if (enviados > 0) {
            log.info("Recordatorio de visitas: {} correos encolados de {} usuarios con historial reciente", enviados,
                    userIds.size());
        }
        return enviados;
    }

    /**
     * Encola el recordatorio de UN usuario, si le toca. No le toca cuando ya recibió uno dentro de la
     * ventana (el propio correo enviado hace de marca: es lo que convierte un barrido diario en un envío
     * cada tres días por persona), cuando se dio de baja de marketing, cuando su cuenta ya no está activa o
     * cuando no queda nada que enseñarle.
     *
     * @return true si el correo se encoló.
     */
    private boolean enqueueIfDue(UUID userId, Instant desde) {
        Optional<UserEntity> encontrado = userRepository.findById(userId);
        if (encontrado.isEmpty()) {
            return false;
        }
        UserEntity user = encontrado.get();
        String email = user.getEmail();
        if (email == null || email.isBlank() || !user.isActive() || user.isMarketingOptOut()) {
            return false;
        }
        if (outboundEmailRepository.existsByToAddressAndTemplateAndCreatedAtGreaterThanEqual(email, TEMPLATE, desde)) {
            return false;
        }
        List<UUID> visitados = viewRepository.findProductIdsByUserIdSince(userId, desde, Limit.of(MAX_PRODUCTOS));
        if (visitados.isEmpty()) {
            return false;
        }
        String lang = normalizeLang(user.getLanguage());
        // Divisa del país del usuario. El correo se genera fuera de cualquier petición HTTP, así que
        // CurrencyHolder está vacío y los precios saldrían en dólares para todo el mundo: alguien de España
        // recibiría importes que no puede comparar con lo que ve en la tienda.
        String divisa = countryCurrencyService.forCountry(user.getCountry());
        // Se lee por IDs con el mismo pipeline del catálogo, que descarta los que ya no están activos: un
        // producto retirado entre la visita y el envío simplemente no sale en la cuadrícula.
        List<ProductSummaryView> productos = conDivisa(divisa,
                () -> storefrontRead.favorites(visitados, 0, MAX_PRODUCTOS, lang).items());
        if (productos.isEmpty()) {
            return false;
        }
        Map<String, String> imagenesAdjuntas = new LinkedHashMap<>();
        List<List<Map<String, Object>>> filas = buildGrid(productos, imagenesAdjuntas);
        emailQueue.enqueue(email, null, ViewedProductsEmailLabel.SUBJECT.of(lang), TEMPLATE,
                buildVars(filas, lang, userId), imagenesAdjuntas);
        return true;
    }

    /** Variables de la plantilla: cuadrícula, textos del idioma y los dos enlaces (historial y baja). */
    private Map<String, Object> buildVars(List<List<Map<String, Object>>> filas, String lang, UUID userId) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", ViewedProductsEmailLabel.TITLE.of(lang));
        vars.put("intro", ViewedProductsEmailLabel.INTRO.of(lang));
        vars.put("rows", filas);
        vars.put("ctaUrl", storefrontBaseUrl + "/history");
        vars.put("ctaLabel", ViewedProductsEmailLabel.CTA.of(lang));
        vars.put("footerNote", ViewedProductsEmailLabel.FOOTER.of(lang));
        vars.put("unsubscribeUrl", unsubscribeUrl(userId, lang));
        vars.put("unsubscribeLabel", ViewedProductsEmailLabel.UNSUBSCRIBE.of(lang));
        return vars;
    }

    /**
     * Modelo de la cuadrícula, YA REPARTIDO EN FILAS de {@link #COLUMNAS} celdas.
     *
     * <p>El reparto se hace aquí y no en la plantilla porque en un correo la cuadrícula son tablas —los
     * clientes no tienen ni grid ni flex, y los flotados se rompen en Outlook—, y para cerrar y abrir cada
     * fila desde el bucle habría que emitir etiquetas descompensadas, que es justo lo que el parser de
     * plantillas no garantiza. Con las filas ya hechas, la plantilla solo recorre dos listas.
     *
     * <p>Las fotos se referencian como {@code cid:} y su URL se anota en {@code imagenesAdjuntas} para que
     * el despacho las descargue del bucket y las meta DENTRO del mensaje. Por URL no se ven: en local la
     * del storage es {@code localhost} —inalcanzable para el proxy de Gmail— y Outlook y Apple Mail
     * bloquean las imágenes remotas de serie. Un correo cuya gracia es la cuadrícula de fotos no puede
     * depender de que el cliente decida cargarlas.
     */
    private List<List<Map<String, Object>>> buildGrid(List<ProductSummaryView> productos,
            Map<String, String> imagenesAdjuntas) {
        List<List<Map<String, Object>>> filas = new ArrayList<>();
        List<Map<String, Object>> fila = new ArrayList<>();
        int indice = 0;
        for (ProductSummaryView p : productos) {
            Map<String, Object> celda = new HashMap<>();
            celda.put("title", p.title());
            celda.put("price", p.displayFormatted());
            celda.put("url", storefrontBaseUrl + "/catalog/" + p.slug());
            String imagen = p.mainImage();
            if (imagen != null && !imagen.isBlank()) {
                String cid = CID_PREFIJO + indice;
                imagenesAdjuntas.put(cid, imagen);
                celda.put("image", "cid:" + cid);
            }
            fila.add(celda);
            indice++;
            if (fila.size() == COLUMNAS) {
                filas.add(fila);
                fila = new ArrayList<>();
            }
        }
        if (!fila.isEmpty()) {
            filas.add(fila);
        }
        return filas;
    }

    /** URL de baja de un clic para un usuario, en su idioma. */
    private String unsubscribeUrl(UUID userId, String lang) {
        return backendBaseUrl + "/api/campaigns/unsubscribe?lang=" + lang + "&token="
                + URLEncoder.encode(unsubscribeService.tokenFor(userId), StandardCharsets.UTF_8);
    }

    /**
     * Ejecuta la construcción del modelo con la divisa dada, y deja el hilo como estaba. Se restaura en un
     * {@code finally} porque el hilo se reutiliza: dejarlo apuntando a la divisa del último usuario
     * contaminaría el correo del siguiente.
     */
    private <T> T conDivisa(String divisa, Supplier<T> accion) {
        String anterior = CurrencyHolder.get();
        try {
            CurrencyHolder.set(divisa);
            return accion.get();
        } finally {
            CurrencyHolder.set(anterior);
        }
    }

    private String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return "es";
        }
        return lang.trim().toLowerCase();
    }
}
