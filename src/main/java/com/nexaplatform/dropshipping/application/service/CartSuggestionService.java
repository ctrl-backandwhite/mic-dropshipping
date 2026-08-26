package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.service.CatalogDutyBadgeService.DutyBadge;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.api.mapper.ProductListFilters;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Qué puede añadir el comprador SIN pagar más aduana y casi sin pagar más envío.
 *
 * <p>
 * No hay modelo de lenguaje aquí, y es deliberado: esto es aritmética sobre datos propios —la
 * partida arancelaria de cada producto y el peso del bulto—, así que la respuesta es exacta,
 * repetible y gratis de calcular. Una sugerencia sobre dinero que un modelo pudiera inventarse no
 * vale nada: quien está a punto de pagar comprueba la cifra.
 *
 * <p>
 * El criterio es el régimen de la Unión: el derecho se cobra <b>por línea de declaración</b>, no por
 * producto. Dos artículos de la misma terna —partida, material y uso— viajan en una sola línea, así
 * que el segundo entra sin sumar un céntimo de arancel. Es una promesa que se puede hacer con cifras
 * porque la calcula el mismo servicio que ya pinta el distintivo del catálogo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartSuggestionService {

    /** Una línea del carrito de quien pregunta. */
    public record Linea(UUID productId, UUID variantId, int quantity) {
    }

    /**
     * Un producto que conviene añadir, con el ahorro YA calculado y formateado.
     *
     * @param dutyExtraFormatted     lo que sube el arancel por llevárselo. Siempre cero por
     *                               construcción: si subiera, no sería una sugerencia
     * @param shippingExtraFormatted lo que sube el envío por meterlo en el bulto que ya se paga
     */
    public record Sugerencia(UUID id, String slug, String title, String image, String dutyExtraFormatted,
            String shippingExtraFormatted) {
    }

    /** Tres. Más no caben en un globo ni se leen antes de seguir comprando. */
    private static final int MAX_SUGERENCIAS = 3;
    /** Se piden más candidatos de los que se enseñan: algunos caen al descartar los ya comprados. */
    private static final int CANDIDATOS = 12;

    private final CatalogDutyBadgeService dutyBadges;
    private final CatalogStorefrontReadService storefrontRead;
    private final CustomsValuationService customsValuation;
    private final ShippingQuoteService shippingQuotes;
    private final CurrencyRateService currencyService;

    @Transactional(readOnly = true)
    public List<Sugerencia> para(List<Linea> carrito, String lang) {
        if (carrito == null || carrito.isEmpty()) {
            return List.of();
        }
        String pais = PricingCountryHolder.get();
        // Donde no se cobra derecho por artículo no hay nada que agrupar y la promesa no significa
        // nada. Fuera de la Unión esta sugerencia no se hace, en vez de hacerse en falso.
        if (customsValuation.perArticleFeeUsdCents(pais) <= 0) {
            return List.of();
        }
        List<UUID> enCarrito = carrito.stream().map(Linea::productId).filter(java.util.Objects::nonNull).toList();
        List<ProductListFilters.DutyLine> lineas = dutyBadges.lineasDe(enCarrito).stream()
                .map(l -> new ProductListFilters.DutyLine(l.grupoId(), l.originCountry()))
                .toList();
        if (lineas.isEmpty()) {
            // Ningún grupo aprobado en el carrito: cualquier producto abriría línea nueva, así que
            // no hay nada honesto que sugerir.
            return List.of();
        }

        List<ProductSummaryView> candidatos = candidatos(lineas, enCarrito, lang);
        if (candidatos.isEmpty()) {
            return List.of();
        }
        Map<UUID, DutyBadge> badges = dutyBadges.badgesFor(enCarrito, candidatos.stream()
                .map(ProductSummaryView::id).toList(), pais);

        List<ProductSummaryView> gratis = candidatos.stream()
                .filter(p -> sinArancelExtra(badges.get(p.id())))
                .limit(MAX_SUGERENCIAS)
                .toList();
        if (gratis.isEmpty()) {
            return List.of();
        }
        return conEnvio(gratis, carrito, pais);
    }

    private List<ProductSummaryView> candidatos(List<ProductListFilters.DutyLine> lineas, List<UUID> enCarrito,
            String lang) {
        ProductListFilters filtros = new ProductListFilters(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, lineas);
        PageResponse<ProductSummaryView> pagina = storefrontRead.productListFull(0, CANDIDATOS, lang, filtros, null);
        Set<UUID> ya = new HashSet<>(enCarrito);
        return pagina.items().stream().filter(p -> !ya.contains(p.id())).toList();
    }

    private boolean sinArancelExtra(DutyBadge badge) {
        return badge != null && badge.extraDutyCents() != null && badge.extraDutyCents() == 0;
    }

    /**
     * Cuánto sube el envío por añadir cada uno. Se cotiza el carrito una vez y luego una vez por
     * candidato: son cuatro consultas al transportista como mucho, y por eso la lista se corta en
     * tres. Si el transportista falla, la sugerencia sale igual sin la cifra de envío —el ahorro de
     * aduana ya es cierto por sí solo— en lugar de perderse entera.
     */
    private List<Sugerencia> conEnvio(List<ProductSummaryView> elegidos, List<Linea> carrito, String pais) {
        Integer base = envio(carrito, pais);
        List<Sugerencia> salida = new ArrayList<>(elegidos.size());
        for (ProductSummaryView p : elegidos) {
            String envioExtra = null;
            if (base != null) {
                List<Linea> conElCandidato = new ArrayList<>(carrito);
                conElCandidato.add(new Linea(p.id(), null, 1));
                Integer con = envio(conElCandidato, pais);
                if (con != null) {
                    envioExtra = formateado(Math.max(0, con - base));
                }
            }
            salida.add(new Sugerencia(p.id(), p.slug(), p.title(), p.mainImage(), formateado(0), envioExtra));
        }
        return salida;
    }

    private Integer envio(List<Linea> lineas, String pais) {
        try {
            ShippingQuote quote = shippingQuotes.quote(pais, lineas.stream()
                    .map(l -> new ShippingQuoteService.Line(l.productId(), l.variantId(), l.quantity()))
                    .toList());
            return quote != null && quote.supported() ? quote.amountUsdCents() : null;
        } catch (RuntimeException e) {
            log.warn("No se pudo cotizar el envío para la sugerencia: {}", e.getMessage());
            return null;
        }
    }

    /** El importe ya escrito en la moneda del comprador: el front no calcula ni formatea importes. */
    private String formateado(int usdCents) {
        BigDecimal display = currencyService.usdToDisplay(BigDecimal.valueOf(usdCents).movePointLeft(2))
                .setScale(2, RoundingMode.HALF_UP);
        return currencyService.formatDisplay(display, CurrencyHolder.get());
    }
}
