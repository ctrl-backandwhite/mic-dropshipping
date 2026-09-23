package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.api.mapper.ProductListFilters;
import com.nexaplatform.dropshipping.application.service.CatalogDutyBadgeService.DutyBadge;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * Lo que se le devuelve al escaparate: qué conviene añadir y cuánto sitio queda en el paquete.
     *
     * @param gramosLibres       lo que todavía cabe sin abrir otro bulto, o {@code null} si no hay
     *                           tope de peso en ese destino
     * @param otroBultoFormatted lo que costaría de aduana abrir ese segundo bulto
     */
    public record Sugerencias(List<Sugerencia> items, Integer gramosLibres, String otroBultoFormatted) {
    }

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
            String shippingExtraFormatted, String shippingAloneFormatted, String motivo) {
    }

    /** Por qué merece la pena añadirlo. Decide QUÉ se le promete a quien compra. */
    public static final String POR_ARANCEL = "DUTY";
    public static final String POR_ENVIO = "SHIPPING";

    /**
     * Margen que se deja bajo el umbral de minimis. Sugerir algo que lo cruce sería el peor consejo
     * posible: por encima de ese valor el pedido deja de pagar la tasa fija por línea y pasa a
     * arancel completo sobre todo lo que lleva. Un euro de más ahí cuesta muchísimo más de un euro.
     */
    private static final int COLCHON_MINIMIS_CENTS = 500;

    /** Tres. Más no caben en un globo ni se leen antes de seguir comprando. */
    private static final int MAX_SUGERENCIAS = 3;
    /** Se piden más candidatos de los que se enseñan: algunos caen al descartar los ya comprados. */
    private static final int CANDIDATOS = 12;

    private final CatalogDutyBadgeService dutyBadges;
    private final CatalogStorefrontReadService storefrontRead;
    private final CustomsValuationService customsValuation;
    private final ShippingQuoteService shippingQuotes;
    private final CurrencyRateService currencyService;
    private final CheckoutPreviewService checkoutPreview;
    private final CarrierChannelLimitService channelLimits;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public Sugerencias para(List<Linea> carrito, String lang) {
        if (carrito == null || carrito.isEmpty()) {
            return new Sugerencias(List.of(), null, null);
        }
        String pais = PricingCountryHolder.get();
        // Donde no se cobra derecho por artículo no hay nada que agrupar y la promesa no significa
        // nada. Fuera de la Unión esta sugerencia no se hace, en vez de hacerse en falso.
        if (customsValuation.perArticleFeeUsdCents(pais) <= 0) {
            return new Sugerencias(List.of(), null, null);
        }
        List<UUID> enCarrito = carrito.stream().map(Linea::productId).filter(java.util.Objects::nonNull).toList();
        List<ProductListFilters.DutyLine> lineas = dutyBadges.lineasDe(enCarrito).stream()
                .map(l -> new ProductListFilters.DutyLine(l.grupoId(), l.originCountry())).toList();
        if (lineas.isEmpty()) {
            // Ningún grupo aprobado en el carrito: cualquier producto abriría línea nueva, así que
            // no hay nada honesto que sugerir.
            return new Sugerencias(List.of(), null, null);
        }

        Integer libres = huecoDelPaquete(carrito, pais);
        String otroBulto = formateado(customsValuation.perArticleFeeUsdCents(pais));

        List<ProductSummaryView> candidatos = queAprovechenElHueco(
                bajoElUmbral(candidatos(lineas, enCarrito, lang), carrito, pais), libres);
        if (candidatos.isEmpty()) {
            return new Sugerencias(List.of(), libres, otroBulto);
        }
        Map<UUID, DutyBadge> badges = dutyBadges.badgesFor(enCarrito,
                candidatos.stream().map(ProductSummaryView::id).toList(), pais);

        List<ProductSummaryView> sinArancel = candidatos.stream().filter(p -> sinArancelExtra(badges.get(p.id())))
                .limit(MAX_SUGERENCIAS).toList();
        if (!sinArancel.isEmpty()) {
            return new Sugerencias(conEnvio(sinArancel, carrito, pais, POR_ARANCEL), libres, otroBulto);
        }
        // Que todo sume arancel no significa que no haya nada que ahorrar: lo que cabe en el bulto
        // que ya se paga viaja casi gratis, y ese ahorro es igual de real. Antes, en cuanto la
        // aduana dejaba de dar cero, el asistente se callaba y perdía la mitad de su utilidad.
        return new Sugerencias(conEnvio(candidatos.stream().limit(MAX_SUGERENCIAS).toList(), carrito, pais, POR_ENVIO),
                libres, otroBulto);
    }

    private List<ProductSummaryView> candidatos(List<ProductListFilters.DutyLine> lineas, List<UUID> enCarrito,
            String lang) {
        ProductListFilters filtros = new ProductListFilters(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, lineas);
        PageResponse<ProductSummaryView> pagina = storefrontRead.productListFull(0, CANDIDATOS, lang, filtros, null);
        Set<UUID> ya = new HashSet<>(enCarrito);
        return pagina.items().stream().filter(p -> !ya.contains(p.id())).toList();
    }

    /**
     * Descarta lo que haría cruzar el umbral de minimis del destino.
     *
     * <p>Es la regla que convierte esto en una compra inteligente y no solo en una compra mayor:
     * hasta el umbral, añadir sale casi gratis —misma línea de aduana, mismo bulto—; un céntimo por
     * encima, el pedido entero pasa a pagar arancel sobre su valor. Sugerir el producto que cruza
     * esa raya sería empujar a alguien a un cargo que no espera.
     *
     * <p>Se deja un colchón por debajo del umbral: el valor declarado se calcula con la tasa del día
     * y no conviene rozarlo.
     */
    private List<ProductSummaryView> bajoElUmbral(List<ProductSummaryView> candidatos, List<Linea> carrito,
            String pais) {
        int umbralUsdCents = customsValuation.deMinimisUsdCentsFor(pais);
        if (umbralUsdCents <= 0 || candidatos.isEmpty()) {
            // Sin umbral en ese destino no hay raya que cruzar.
            return candidatos;
        }
        // La comparación se hace en la MONEDA QUE SE MUESTRA, porque es la única en la que el
        // listado trae el precio: `priceUsd` viaja a nulo y solo hay `displayPrice`. Convertir el
        // umbral una vez es más fiable que inventar el dólar de cada producto.
        BigDecimal umbral = enDisplay(umbralUsdCents);
        BigDecimal colchon = enDisplay(COLCHON_MINIMIS_CENTS);
        BigDecimal disponible = umbral.subtract(colchon).subtract(enDisplay(valorDe(carrito)));
        if (disponible.signum() <= 0) {
            // Ya no cabe nada sin cruzar: mejor callarse que empujar a un cargo inesperado.
            return List.of();
        }
        return candidatos.stream().filter(p -> p.displayPrice() != null && p.displayPrice().signum() > 0
                && p.displayPrice().compareTo(disponible) <= 0).toList();
    }

    /**
     * Valor del carrito en céntimos USD, que es la moneda en la que se mide el umbral.
     *
     * <p>Lo calcula el MISMO servicio que el checkout. Sumar aquí los precios por mi cuenta sería
     * abrir una segunda contabilidad: el día que una promoción o un cupón cambien el subtotal, el
     * aviso del umbral hablaría de un carrito que no existe.
     */
    private int valorDe(List<Linea> carrito) {
        try {
            return checkoutPreview.compute(PricingCountryHolder.get(), null, carrito.stream()
                    .map(l -> new CheckoutPreviewService.Line(l.productId(), l.variantId(), l.quantity())).toList(),
                    null).subtotalUsdCents();
        } catch (RuntimeException e) {
            log.warn("No se pudo valorar el carrito para el umbral: {}", e.getMessage());
            // Sin valor no se puede garantizar que no se cruce la raya: se prefiere no sugerir.
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Pone delante lo que CABE en el hueco que queda.
     *
     * <p>Es la diferencia entre una sugerencia útil y una cara: un producto que entra en el paquete
     * que ya se paga no cuesta arancel nuevo ni casi envío; el mismo producto 50 g más pesado abre
     * un segundo bulto y se lleva 3 EUR por delante. Cuando se conoce el hueco, lo que cabe va
     * primero y lo que no cabe solo aparece si no hay nada mejor.
     */
    private List<ProductSummaryView> queAprovechenElHueco(List<ProductSummaryView> candidatos, Integer libres) {
        if (libres == null || libres <= 0 || candidatos.isEmpty()) {
            return candidatos;
        }
        Map<UUID, Integer> pesos = new HashMap<>();
        for (ProductEntity p : productRepository
                .findAllById(candidatos.stream().map(ProductSummaryView::id).toList())) {
            pesos.put(p.getId(), ParcelAggregator.unitWeightGrams(p, null));
        }
        List<ProductSummaryView> caben = new ArrayList<>();
        List<ProductSummaryView> noCaben = new ArrayList<>();
        for (ProductSummaryView p : candidatos) {
            Integer peso = pesos.get(p.id());
            // Sin peso conocido no se puede afirmar que quepa: va al final, nunca se presenta como
            // aprovechamiento del hueco.
            if (peso != null && peso > 0 && peso <= libres) {
                caben.add(p);
            } else {
                noCaben.add(p);
            }
        }
        // Los que caben, del más pesado al más ligero: el que mejor aprovecha el hueco primero.
        caben.sort((a, b) -> Integer.compare(pesos.getOrDefault(b.id(), 0), pesos.getOrDefault(a.id(), 0)));
        List<ProductSummaryView> orden = new ArrayList<>(caben);
        orden.addAll(noCaben);
        return orden;
    }

    /**
     * Cuánto peso cabe todavía sin abrir otro bulto.
     *
     * <p>Se calcula con el MISMO repartidor que usa el cálculo del arancel, no dividiendo el peso
     * entre el tope: el reparto real no llena los bultos en orden, así que la cuenta fácil prometería
     * un hueco que no existe. Y aquí prometer de más cuesta 3 EUR al cliente.
     *
     * @return los gramos libres del bulto con más sitio, o {@code null} si ese destino no parte
     */
    private Integer huecoDelPaquete(List<Linea> carrito, String pais) {
        int tope = channelLimits.resolve(null, pais).maxWeightGrams();
        if (tope <= 0) {
            return null;
        }
        List<ParcelSplitter.Unit> unidades = new ArrayList<>();
        int indice = 0;
        for (ProductEntity p : productRepository
                .findAllById(carrito.stream().map(Linea::productId).filter(java.util.Objects::nonNull).toList())) {
            int cantidad = carrito.stream().filter(l -> p.getId().equals(l.productId())).mapToInt(Linea::quantity)
                    .sum();
            int peso = ParcelAggregator.unitWeightGrams(p, null);
            for (int i = 0; i < Math.max(1, cantidad); i++) {
                unidades.add(new ParcelSplitter.Unit(indice, peso, 0, 0, 0, 0, false));
            }
            indice++;
        }
        if (unidades.isEmpty()) {
            return null;
        }
        List<ParcelSplitter.Bin> bultos = ParcelSplitter.split(unidades, new ParcelSplitter.Limits(tope, 0, 0));
        int masVacio = bultos.stream()
                .mapToInt(b -> b.units().stream().mapToInt(ParcelSplitter.Unit::weightGrams).sum()).min().orElse(0);
        return Math.max(0, tope - masVacio);
    }

    /** Céntimos de dólar llevados a la moneda que ve quien compra. */
    private BigDecimal enDisplay(int usdCents) {
        return currencyService.usdToDisplay(BigDecimal.valueOf(usdCents).movePointLeft(2)).setScale(2,
                RoundingMode.HALF_UP);
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
    private List<Sugerencia> conEnvio(List<ProductSummaryView> elegidos, List<Linea> carrito, String pais,
            String motivo) {
        Integer base = envio(carrito, pais);
        List<Sugerencia> salida = new ArrayList<>(elegidos.size());
        for (ProductSummaryView p : elegidos) {
            String envioExtra = null;
            String envioSuelto = null;
            if (base != null) {
                List<Linea> conElCandidato = new ArrayList<>(carrito);
                conElCandidato.add(new Linea(p.id(), null, 1));
                Integer con = envio(conElCandidato, pais);
                if (con != null) {
                    envioExtra = formateado(Math.max(0, con - base));
                }
                if (POR_ENVIO.equals(motivo)) {
                    // Lo que costaría pedirlo aparte. Sin esa referencia, «suma 0,40 €» no dice nada:
                    // el ahorro solo se entiende comparado con lo que vale el envío suelto.
                    Integer solo = envio(List.of(new Linea(p.id(), null, 1)), pais);
                    if (solo != null) {
                        envioSuelto = formateado(solo);
                    }
                }
            }
            salida.add(new Sugerencia(p.id(), p.slug(), p.title(), p.mainImage(),
                    POR_ARANCEL.equals(motivo) ? formateado(0) : null, envioExtra, envioSuelto, motivo));
        }
        return salida;
    }

    private Integer envio(List<Linea> lineas, String pais) {
        try {
            ShippingQuote quote = shippingQuotes.quote(pais, lineas.stream()
                    .map(l -> new ShippingQuoteService.Line(l.productId(), l.variantId(), l.quantity())).toList());
            return quote != null && quote.supported() ? quote.amountUsdCents() : null;
        } catch (RuntimeException e) {
            log.warn("No se pudo cotizar el envío para la sugerencia: {}", e.getMessage());
            return null;
        }
    }

    /** El importe ya escrito en la moneda del comprador: el front no calcula ni formatea importes. */
    private String formateado(int usdCents) {
        BigDecimal display = currencyService.usdToDisplay(BigDecimal.valueOf(usdCents).movePointLeft(2)).setScale(2,
                RoundingMode.HALF_UP);
        return currencyService.formatDisplay(display, CurrencyHolder.get());
    }
}
