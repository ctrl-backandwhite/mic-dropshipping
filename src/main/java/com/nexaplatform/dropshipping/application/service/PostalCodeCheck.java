package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.domain.enums.PostalCodeFormat;

/**
 * Rechaza una dirección cuyo código postal no es del país elegido.
 *
 * <p>Vive aparte del {@link PostalCodeFormat} —que solo sabe de formatos— porque lo que aquí se decide
 * es qué hacer cuando no encaja: tumbar la operación con un mensaje que diga qué se esperaba. El
 * formato se comprueba en el SERVIDOR y no solo en el formulario, porque quien quiera saltárselo llama
 * a la API directamente; y se comprueba en los dos sitios por los que puede entrar una dirección: el
 * alta o edición desde el perfil, y el pedido con dirección escrita en el propio checkout.
 *
 * <p>Con los países que no están en la tabla no hace nada: ante la duda no se bloquea una compra.
 */
public final class PostalCodeCheck {

    private PostalCodeCheck() {
    }

    /** Lanza {@link BusinessException} si el código postal no vale para ese país. */
    public static void require(String countryCode, String postalCode) {
        if (PostalCodeFormat.isValid(countryCode, postalCode)) {
            return;
        }
        String ejemplo = PostalCodeFormat.exampleFor(countryCode);
        throw new BusinessException("INVALID_POSTAL_CODE",
                "El código postal no es válido para " + countryCode.trim().toUpperCase(java.util.Locale.ROOT)
                        + (ejemplo.isEmpty() ? "." : ". Ejemplo: " + ejemplo));
    }
}
