package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.StorefrontCatalogApi;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.api.mapper.ProductListFilters;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mantiene calientes las respuestas que abre TODO el mundo al entrar.
 *
 * <h2>El problema que resuelve</h2>
 *
 * <p>La caché funciona: la portada tarda 3-6 segundos la primera vez y 0,03 la segunda. El problema
 * nunca fue la segunda — es que casi nadie llegaba a ser la segunda. Tres cosas conspiraban:
 *
 * <ul>
 *   <li>La caché es <b>por réplica</b> (Caffeine vive dentro de la JVM), así que cada réplica tiene
 *       que calentarse por su cuenta y un visitante puede caer en la fría.</li>
 *   <li>Caduca a los <b>5 minutos</b>, de modo que cada cinco minutos alguien vuelve a pagar la
 *       primera vez.</li>
 *   <li>La clave incluye idioma, divisa, canal, país y rol, así que hay muchas entradas distintas y
 *       cada una se calienta sola.</li>
 * </ul>
 *
 * <p>Sumado: quien entra a una hora tranquila paga los segundos casi siempre. Es justo lo que se ve al
 * abrir el escaparate y quedarse mirando los recuadros grises.
 *
 * <h2>Cómo lo resuelve</h2>
 *
 * <p>Cada réplica se calienta a sí misma, antes de que caduque, con las combinaciones que de verdad
 * usa la gente. Lo paga una tarea de fondo a la que no espera nadie, en vez del primer visitante.
 *
 * <p>El intervalo va por debajo del tiempo de vida de la caché a propósito: si fuera igual o mayor
 * habría siempre una ventana con la entrada ya caducada y todavía sin rehacer, que es exactamente el
 * hueco que se quiere cerrar.
 *
 * <p>Se calienta la entrada <b>pública</b>, no la de administrador: la clave incluye el rol y esta
 * tarea no se autentica. Es lo correcto — el escaparate lo abren visitantes, y el panel lo abren unas
 * pocas personas que además navegan seguido y se calientan la suya solas.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrecalentadorDeCatalogo {

    private static final ProductListFilters SIN_FILTROS =
            new ProductListFilters(null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);

    private final StorefrontCatalogApi catalogo;
    private final CatalogStorefrontReadService lectura;

    @Value("${nexadrop.catalogo.precalentar:true}")
    private boolean activo;

    /**
     * Combinaciones de idioma y divisa que se mantienen calientes.
     *
     * <p>No son los ocho idiomas por las nueve divisas: eso serían setenta y dos entradas rehechas
     * cada pocos minutos para que la inmensa mayoría no las mirara nadie. Son las que concentran las
     * visitas; el resto sigue calentándose sola con el primer visitante, como hasta ahora.
     */
    @Value("${nexadrop.catalogo.precalentar-combinaciones:es:EUR,en:USD,es:USD,en:EUR}")
    private List<String> combinaciones;

    @Value("${nexadrop.catalogo.precalentar-secciones:6}")
    private int porSeccion;

    /**
     * Cada cuatro minutos, por debajo de los cinco de vida de la caché.
     *
     * <p>{@code fixedDelay} y no {@code fixedRate}: cuenta desde que TERMINA la pasada anterior, así
     * que si una tarda más de lo normal no se solapa consigo misma.
     */
    @Scheduled(initialDelay = 20_000, fixedDelay = 240_000)
    public void precalienta() {
        if (!activo) {
            return;
        }
        long inicio = System.nanoTime();
        int hechas = 0;
        for (String combinacion : combinaciones) {
            String[] partes = combinacion.split(":");
            if (partes.length != 2) {
                log.warn("::> [PRECALENTADO] Combinación mal escrita, se ignora: '{}' (se espera idioma:DIVISA)",
                        combinacion);
                continue;
            }
            if (calienta(partes[0].trim(), partes[1].trim())) {
                hechas++;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("::> [PRECALENTADO] {} de {} combinaciones en {} ms", hechas, combinaciones.size(),
                    (System.nanoTime() - inicio) / 1_000_000);
        }
    }

    private boolean calienta(String idioma, String divisa) {
        try {
            // Los mismos valores que pondría el filtro de una petición real: si no coinciden, se
            // calentaría una entrada que luego nadie consulta.
            CurrencyHolder.set(divisa);
            PricingChannelHolder.set(PriceRuleChannel.STOREFRONT);
            PricingCountryHolder.clear();

            catalogo.homeSections(idioma, porSeccion);
            catalogo.categoriesFlat(idioma);
            lectura.categoriesTree(idioma);
            lectura.productListFull(0, 24, idioma, SIN_FILTROS, null);
            return true;
        } catch (RuntimeException e) {
            // Que falle el precalentado NO puede tumbar nada: es una comodidad, no un requisito. Si la
            // base de datos está ocupada o el buscador no responde, se avisa y se reintenta en la
            // siguiente vuelta; el visitante sigue teniendo el camino de siempre.
            log.warn("::> [PRECALENTADO] No se pudo calentar {}/{}: {}", idioma, divisa, e.toString());
            return false;
        } finally {
            CurrencyHolder.clear();
            PricingChannelHolder.clear();
            PricingCountryHolder.clear();
        }
    }
}
