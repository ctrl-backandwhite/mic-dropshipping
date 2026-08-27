package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;

import java.util.Locale;

/**
 * Qué forma de envío se cobra: la que eligió el cliente, si es una de las que el transportista cotiza
 * ahora mismo, y si no la más barata.
 *
 * <p>El código de canal llega desde el navegador, así que <b>no se puede usar tal cual</b>. Bastaría
 * con mandar el de un canal más barato para pagar de menos, o el de uno postal para colarse fuera del
 * régimen con IVA prepagado y romper el DDP. Se comprueba contra la cotización recién hecha y el
 * importe que se cobra sale de ella, nunca de lo que venga en la petición.
 *
 * <p>Un código que no está entre las opciones no es un error del cliente que merezca rechazar la
 * compra —la cotización cambia entre que se pinta el checkout y se pulsa pagar—, así que se cae a la
 * más barata en vez de tumbar el pedido.
 */
public final class ShippingOptionResolver {

    private ShippingOptionResolver() {
    }

    /** La opción a cobrar, o {@code null} si la cotización no ofrece ninguna (tarifa de tabla de zonas). */
    public static ShippingOption resolve(ShippingQuote quote, String requestedCode) {
        if (quote == null || quote.options() == null || quote.options().isEmpty()) {
            return null;
        }
        if (requestedCode != null && !requestedCode.isBlank()) {
            String wanted = requestedCode.trim().toUpperCase(Locale.ROOT);
            for (ShippingOption option : quote.options()) {
                if (option.code() != null && option.code().toUpperCase(Locale.ROOT).equals(wanted)) {
                    return option;
                }
            }
        }
        // Las opciones vienen ordenadas de más barata a más cara.
        return quote.options().getFirst();
    }
}
