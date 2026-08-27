package com.nexaplatform.dropshipping.infrastructure.integration.shop;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Anti-SSRF para los conectores de tienda: el host de destino lo controla el usuario (shopHandle), así que
 * antes de hacer la petición saliente hay que asegurarse de que NO apunta a la red interna. Rechaza hosts
 * que resuelven a direcciones loopback, privadas, link-local (incluida la metadata cloud 169.254.169.254),
 * multicast o reservadas. Sin esto, un usuario autenticado podía usar el conector para escanear/alcanzar
 * servicios internos y filtrar sus respuestas.
 */
final class ShopHostGuard {

    private ShopHostGuard() {
    }

    /** {@code true} solo si TODAS las IPs a las que resuelve el host son públicas y enrutables. */
    static boolean isPublicHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            if (addrs.length == 0) {
                return false;
            }
            for (InetAddress addr : addrs) {
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
                    return false;
                }
                byte[] b = addr.getAddress();
                if (b.length == 4) {
                    int o0 = b[0] & 0xff;
                    int o1 = b[1] & 0xff;
                    // Rangos IPv4 reservados/privados que isSiteLocalAddress no cubre:
                    // 0.0.0.0/8, 127/8 (loopback), 100.64/10 (CGNAT), 192.0.0/24, 198.18/15 (benchmark).
                    if (o0 == 0 || o0 == 127) {
                        return false;
                    }
                    if (o0 == 100 && o1 >= 64 && o1 <= 127) {
                        return false;
                    }
                    if (o0 == 192 && o1 == 0) {
                        return false;
                    }
                    if (o0 == 198 && (o1 == 18 || o1 == 19)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
