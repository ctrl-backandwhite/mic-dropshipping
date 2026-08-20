package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

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
public class FulfillmentRouter {

    private final List<FulfillmentProvider> transportistas;
    private final CarrierEligibilityService elegibilidad;
    /** Cuántas formas de envío se le llegan a ofrecer al cliente. Cero o menos = todas. */
    private final int maxOpciones;

    public FulfillmentRouter(List<FulfillmentProvider> transportistas, CarrierEligibilityService elegibilidad,
            @Value("${nexadrop.fulfillment.max-shipping-options:5}") int maxOpciones) {
        this.transportistas = transportistas;
        this.elegibilidad = elegibilidad;
        this.maxOpciones = maxOpciones;
    }

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
        List<ShippingOption> ofrecibles = lasMasBaratas(ordenar(sinLineasRepetidas(todas)));
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
     * Deja una sola opción por PLAZO, la más barata.
     *
     * <p>Al cliente le da igual quién lleve el paquete: mira cuánto cuesta y cuándo llega. Si dos
     * opciones prometen exactamente lo mismo, la cara no le aporta nada — solo le obliga a elegir entre
     * dos cosas que para él son idénticas y deja la tienda con pinta de estar cobrando de más.
     *
     * <p><b>Y NO se compara por nombre, aunque sea tentador.</b> Se midió con tarifas reales el
     * 19-ago-2026: lo que CJ revende como «YunExpress Ordinary» tarda 8-15 días, mientras que la línea
     * FZZXR contratada directamente con YunExpress tarda 5-8. Los dos nombres mencionan a YunExpress y
     * NO son el mismo servicio: CJ sale más barato porque vende uno más lento. Fundirlas por el nombre
     * habría borrado la opción rápida, que además es la más barata de su tramo (10,79 $ frente a los
     * 11,45 $ que pide CJ por un 4-8 días).
     *
     * <p>Gana la barata venga de quien venga: la regla es el precio, no el transportista.
     */
    private static List<ShippingOption> sinLineasRepetidas(List<ShippingOption> opciones) {
        LinkedHashMap<String, ShippingOption> porPlazo = new LinkedHashMap<>();
        for (ShippingOption opcion : opciones) {
            String clave = opcion.etaMinDays() + "-" + opcion.etaMaxDays();
            ShippingOption anterior = porPlazo.get(clave);
            if (anterior == null || opcion.amountUsdCents() < anterior.amountUsdCents()) {
                porPlazo.put(clave, opcion);
            }
        }
        return new ArrayList<>(porPlazo.values());
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

    /**
     * Se queda con el principio de la lista ya ordenada: las más baratas.
     *
     * <p>CJ devuelve quince formas de envío para España y YunExpress añade las suyas. Una lista así no
     * es más elección, es un catálogo en mitad del pago: el cliente que tenía que decidir entre dos
     * cosas acaba comparando quince y se va. Se le ofrece un abanico corto, y el criterio del corte es
     * el precio porque es lo que se le está pidiendo que compare.
     *
     * <p><b>Va después de ordenar, nunca antes.</b> Recortar la lista cruda dejaría fuera opciones
     * baratas solo porque su transportista contestó el segundo.
     *
     * <p>Un tope de cero o menos no recorta nada: es la vía de escape para depurar una cotización
     * completa sin tocar el código.
     */
    private List<ShippingOption> lasMasBaratas(List<ShippingOption> ordenadas) {
        if (maxOpciones <= 0 || ordenadas.size() <= maxOpciones) {
            return ordenadas;
        }
        return new ArrayList<>(ordenadas.subList(0, maxOpciones));
    }
}
