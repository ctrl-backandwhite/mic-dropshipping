package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentClientTarget;

/**
 * Portador por petición del cliente que está pagando, poblado por {@link PaymentClientFilter} desde
 * la cabecera {@code X-Client} y limpiado al terminar.
 *
 * <p>Va por petición y no por parámetro porque el dato lo necesita el constructor del pago, al final
 * de una cadena de seis firmas distintas —recarga, pedido de cliente, pedido de socio, vista de
 * administración— que no tienen nada que ver con dónde estaba quien pagó. Es el mismo mecanismo que
 * ya usa la divisa activa en {@code CurrencyHolder}.
 *
 * <p>Sin cabecera, o con una que no se reconoce, queda {@link PaymentClientTarget#WEB}: el
 * comportamiento que había antes de existir la aplicación móvil.
 */
public final class PaymentClientHolder {

    private static final ThreadLocal<PaymentClientTarget> CURRENT =
            ThreadLocal.withInitial(() -> PaymentClientTarget.WEB);

    private PaymentClientHolder() {
    }

    public static PaymentClientTarget get() {
        PaymentClientTarget target = CURRENT.get();
        return target == null ? PaymentClientTarget.WEB : target;
    }

    public static void set(String raw) {
        CURRENT.set(PaymentClientTarget.from(raw));
    }

    public static void clear() {
        CURRENT.remove();
    }
}
