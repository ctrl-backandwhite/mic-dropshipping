package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * A quién se le pide la guía —y a quién se le pregunta por el seguimiento— de un pedido concreto.
 *
 * <p>Existe porque desde que hay dos transportistas ya no vale con inyectar «el» transportista: el que
 * cobró el porte lo decidió el cliente al elegir su forma de envío, y quedó anotado en el pedido. Pedirle
 * la guía a otro no da ningún error visible —los dos responden—, simplemente se cobra un porte y se paga
 * otro, y el paquete sale por quien nadie eligió.
 *
 * <p>Tres casos, y el tercero es el que justifica que esto devuelva un {@link Optional} en vez de un
 * transportista siempre:
 *
 * <ul>
 *   <li><b>El pedido dice quién es y está disponible</b> → ese, sin más.</li>
 *   <li><b>El pedido no lo dice</b> → el primario. Son los pedidos anteriores a que existiera la columna:
 *       todos eran de YunExpress y hay que poder seguir despachándolos.</li>
 *   <li><b>El pedido lo dice pero no está disponible</b> —el transportista apagado, o un valor que ya no
 *       existe— → <b>ninguno</b>. Sustituirlo por otro sería justo el fallo que esta clase evita, así que
 *       se devuelve vacío y quien llama decide qué hacer: al despachar, un fallo permanente que alguien
 *       tiene que mirar; al seguir, no inventar hitos.</li>
 * </ul>
 *
 * <p>El nombre se compara sin distinguir mayúsculas y quitando espacios porque viaja en una columna de
 * texto, no en un enum.
 */
@Service
public class FulfillmentProviderSelector {

    private final List<FulfillmentProvider> transportistas;
    private final FulfillmentProvider primario;

    public FulfillmentProviderSelector(List<FulfillmentProvider> transportistas, FulfillmentProvider primario) {
        this.transportistas = List.copyOf(transportistas);
        this.primario = primario;
    }

    /** El transportista que lleva este pedido, o vacío si el que lo lleva no está disponible. */
    public Optional<FulfillmentProvider> para(Order pedido) {
        String anotado = pedido == null ? null : pedido.getShippingCarrier();
        if (anotado == null || anotado.isBlank()) {
            return Optional.ofNullable(primario);
        }
        return llamado(anotado);
    }

    /**
     * El transportista de este tipo concreto.
     *
     * <p>Lo pide lo que solo sabe hacer una implementación y no el contrato común: descifrar el webhook
     * de su propio transportista, por ejemplo. Se busca por tipo y no por nombre a propósito —el nombre
     * es un dato suyo, el tipo es lo que garantiza que tiene ese método—.
     */
    public <T extends FulfillmentProvider> Optional<T> deTipo(Class<T> tipo) {
        return transportistas.stream().filter(tipo::isInstance).map(tipo::cast).findFirst();
    }

    /**
     * El transportista que se llama así, sin pedido de por medio.
     *
     * <p>Lo necesita lo que llega del propio transportista y no de un pedido —un webhook, por ejemplo—,
     * donde hay que localizar a quien sabe interpretar ese mensaje. A diferencia de {@link #para(Order)},
     * aquí un nombre vacío no cae en el primario: si nadie ha dicho de quién es, no se elige por él.
     */
    public Optional<FulfillmentProvider> llamado(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return Optional.empty();
        }
        String buscado = nombre.trim();
        return transportistas.stream().filter(transportista -> buscado.equalsIgnoreCase(transportista.nombre()))
                .findFirst();
    }
}
