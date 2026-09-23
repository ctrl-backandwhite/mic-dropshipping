package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ErrorCode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Comprueba el enlace a la ficha del proveedor antes de guardarlo.
 *
 * <p>Ese enlace es el que el admin abre para COMPRAR la mercancía, así que un dominio cualquiera
 * convertiría el botón «Comprar en origen» en un destino arbitrario pegado desde fuera. Se admite
 * únicamente el mercado de origen (1688 / Alibaba) y solo por http(s): un {@code javascript:} o un
 * {@code data:} guardado aquí se ejecutaría en el navegador del admin al pulsar el botón.
 */
public final class SupplierSourceUrl {

    /** Tope de la columna {@code product.source_url}: más largo se truncaría al guardar. */
    private static final int MAX_LENGTH = 800;

    /** Mercados de origen admitidos. Se acepta el dominio y cualquier subdominio suyo (detail., m., …). */
    private enum AllowedMarketplace {

        ALIBABA_1688("1688.com"), ALIBABA("alibaba.com");

        private final String domain;

        AllowedMarketplace(String domain) {
            this.domain = domain;
        }

        boolean covers(String host) {
            return host.equals(domain) || host.endsWith("." + domain);
        }
    }

    private SupplierSourceUrl() {
    }

    /**
     * @return el enlace ya recortado, listo para guardar
     * @throws BusinessException si es nulo, vacío, no es http(s) o no pertenece a un mercado admitido
     */
    public static String requireValid(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty() || url.length() > MAX_LENGTH) {
            throw invalid();
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw invalid();
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid();
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        for (AllowedMarketplace marketplace : AllowedMarketplace.values()) {
            if (marketplace.covers(normalized)) {
                return url;
            }
        }
        throw invalid();
    }

    /**
     * Con código catalogado, no con mensaje suelto: el manejador traduce por el código y un texto libre
     * acabaría sustituido por el genérico «no se pudo completar la operación».
     */
    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.PRODUCT_SOURCE_URL_INVALID.name(),
                "El enlace de origen debe ser una dirección http(s) de 1688 o Alibaba.");
    }
}
