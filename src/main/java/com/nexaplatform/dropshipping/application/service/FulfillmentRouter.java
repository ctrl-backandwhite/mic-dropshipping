package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Una sola lista de formas de envío, preguntando a todos los transportistas que puedan llevar el pedido.
 *
 * <p>Hasta el 18-ago-2026 solo había YunExpress y bastaba con inyectarlo donde hiciera falta. Con dos
 * transportistas la pregunta deja de ser «cuánto cuesta» y pasa a ser «quién puede llevarlo y por
 * cuánto», que no es lo mismo y no se puede resolver en el sitio donde se cotiza.
 *
 * <p>El cliente ve <b>todas las opciones mezcladas y ordenadas por precio</b>, sin saber de quién es
 * cada una (decisión del dueño). Lo que este enrutador aporta no es fundir dos listas, sino los cuatro
 * casos en que fundirlas sale mal:
 *
 * <ol>
 *   <li><b>Mercancía que una línea no admite.</b> La de ropa de YunExpress es solo textil: ofrecerla
 *       para una taza acaba en guía rechazada en el almacén con el pedido ya cobrado.</li>
 *   <li><b>Un transportista caído.</b> Si su fallo se propaga, se pierde la venta por un problema ajeno
 *       que además tenía alternativa. Cada uno se pregunta por separado y su fallo solo le quita a él.</li>
 *   <li><b>La misma línea dos veces.</b> CJ revende YunExpress: entre sus opciones para España la más
 *       barata se llama «YunExpress Ordinary». Enseñarla junto a la nuestra, a precios distintos, parece
 *       un fallo de la tienda cuando en realidad es el mismo camión.</li>
 *   <li><b>Un destino que uno no cubre.</b> Preguntárselo igualmente es gastar una llamada —CJ limita a
 *       una por segundo— en el camino del checkout.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentRouter {

    private final List<FulfillmentProvider> transportistas;
    private final CarrierEligibilityService elegibilidad;

    /**
     * Lo que se le puede ofrecer al cliente para este pedido y este destino.
     *
     * <p>La cotización devuelta conserva la cabecera del transportista más barato —importe, plazo y
     * zona— porque es lo que el resto del checkout ya usaba cuando solo había uno; lo nuevo son las
     * {@code options}, que es de donde el cliente elige.
     */
    public ShippingQuote cotizar(String pais, FulfillmentProvider.ParcelSpec bulto,
            List<ProductEntity> productos) {
        List<ShippingOption> todas = new ArrayList<>();
        ShippingQuote cabecera = null;
        for (FulfillmentProvider transportista : transportistas) {
            ShippingQuote suya = cotizarCon(transportista, pais, bulto, productos);
            if (suya == null) {
                continue;
            }
            if (cabecera == null || (suya.amountUsdCents() > 0
                    && suya.amountUsdCents() < cabecera.amountUsdCents())) {
                cabecera = suya;
            }
            todas.addAll(suya.options());
        }
        List<ShippingOption> ofrecibles = ordenar(sinLineasRepetidas(todas));
        if (cabecera == null) {
            return new ShippingQuote(false, pais, 0, null, null, 0, 0, null, ofrecibles);
        }
        return new ShippingQuote(cabecera.supported(), pais, cabecera.amountUsdCents(),
                cabecera.carrier(), cabecera.serviceName(), cabecera.etaMinDays(), cabecera.etaMaxDays(),
                cabecera.zone(), ofrecibles);
    }

    /**
     * Le pregunta a un transportista, quitando de su respuesta las líneas que no admiten la mercancía.
     *
     * <p>Devuelve {@code null} si no puede o no debe usarse, y <b>nunca propaga su fallo</b>: perder las
     * opciones de un transportista es una molestia; perder también las del otro es perder la venta.
     */
    private ShippingQuote cotizarCon(FulfillmentProvider transportista, String pais,
            FulfillmentProvider.ParcelSpec bulto, List<ProductEntity> productos) {
        try {
            if (!transportista.isSupported(pais)) {
                return null;
            }
            ShippingQuote suya = transportista.quote(pais, bulto);
            if (suya == null || !suya.supported()) {
                return null;
            }
            List<ShippingOption> admitidas = new ArrayList<>();
            for (ShippingOption opcion : suya.options()) {
                if (elegibilidad.admiteCanal(opcion.code(), productos)) {
                    // Se sella aquí: el transportista construye sus opciones sin saber con qué nombre
                    // se le conoce dentro de la plataforma, y sin ese sello no se sabría a quién pedirle
                    // la guía cuando el cliente elija.
                    admitidas.add(opcion.con(transportista.nombre()));
                }
            }
            return new ShippingQuote(suya.supported(), suya.countryCode(), suya.amountUsdCents(),
                    suya.carrier(), suya.serviceName(), suya.etaMinDays(), suya.etaMaxDays(),
                    suya.zone(), admitidas);
        } catch (RuntimeException e) {
            log.warn("El transportista {} no pudo cotizar a {}: {}",
                    transportista.nombre(), pais, e.getMessage());
            return null;
        }
    }

    /**
     * Deja una sola línea por nombre comercial, la más barata.
     *
     * <p>Se compara por NOMBRE y no por código porque el mismo servicio tiene identificadores distintos
     * en cada transportista que lo revende: es el nombre lo único que delata que son el mismo camión.
     * Dos líneas con nombres distintos no se funden aunque cuesten igual, porque no hay razón para creer
     * que sean la misma.
     */
    private static List<ShippingOption> sinLineasRepetidas(List<ShippingOption> opciones) {
        LinkedHashMap<String, ShippingOption> porNombre = new LinkedHashMap<>();
        for (ShippingOption opcion : opciones) {
            String clave = opcion.name() == null ? "" : opcion.name().trim().toLowerCase(Locale.ROOT);
            ShippingOption anterior = porNombre.get(clave);
            if (anterior == null || opcion.amountUsdCents() < anterior.amountUsdCents()) {
                porNombre.put(clave, opcion);
            }
        }
        return new ArrayList<>(porNombre.values());
    }

    /**
     * De más barata a más cara y, a igual precio, la que llega antes.
     *
     * <p>El desempate por plazo no es un adorno: sin él, dos opciones al mismo precio salen en el orden
     * en que respondieron los transportistas, que cambia entre peticiones, y el cliente ve la lista
     * bailar al recargar.
     */
    private static List<ShippingOption> ordenar(List<ShippingOption> opciones) {
        List<ShippingOption> ordenadas = new ArrayList<>(opciones);
        ordenadas.sort(Comparator.comparingInt(ShippingOption::amountUsdCents)
                .thenComparingInt(ShippingOption::etaMaxDays)
                .thenComparingInt(ShippingOption::etaMinDays));
        return ordenadas;
    }
}
