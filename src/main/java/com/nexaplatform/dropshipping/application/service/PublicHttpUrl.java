package com.nexaplatform.dropshipping.application.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Comprueba que una URL apunta de verdad a Internet antes de que el servidor la llame.
 *
 * <p>Sin esto, cualquiera que pueda registrar una dirección —el partner que da de alta su webhook, el
 * proveedor cuya imagen se espeja— consigue que el servidor haga peticiones desde DENTRO de la red: a
 * {@code 169.254.169.254}, que en la nube devuelve las credenciales de la instancia; a la base de
 * datos; o a la propia API interna. Y como la respuesta se guarda en el registro de entregas, el
 * atacante puede además leerla. Es la forma habitual de convertir un webhook en una puerta trasera.
 *
 * <p>Se rechaza todo lo que no sea http(s) y todo host que resuelva a una dirección no enrutable en
 * Internet: bucle local, enlace local (incluido el rango de metadatos), redes privadas, multidifusión y
 * el rango CGNAT, que {@code isSiteLocalAddress} no cubre.
 *
 * <p>Quien la use debe además NO seguir redirecciones automáticamente: un destino permitido puede
 * responder con un 302 hacia una dirección interna y saltarse la comprobación. Se valida cada salto.
 */
public final class PublicHttpUrl {

    private PublicHttpUrl() {
    }

    /**
     * Permite destinos internos. Sólo debe activarse donde se levanta un servidor de pruebas en
     * {@code 127.0.0.1}: los tests de los despachadores necesitan un destino real al que llamar. Vive
     * como interruptor explícito, y no como excepción escondida en la comprobación, para que activarlo
     * en un entorno de verdad sea una decisión visible.
     */
    private static volatile boolean allowPrivateTargets = false;

    /** @param allow true sólo en pruebas: deja llamar a direcciones internas. */
    public static void allowPrivateTargets(boolean allow) {
        allowPrivateTargets = allow;
    }

    /**
     * @throws SecurityException si el esquema no es http(s) o el host resuelve a una dirección interna
     * @throws UnknownHostException si el host no se puede resolver
     */
    public static void assertPublic(URI uri) throws UnknownHostException {
        String scheme = uri.getScheme();
        if (allowPrivateTargets) {
            // Sólo en pruebas. El esquema se sigue comprobando: ni siquiera ahí tiene sentido un file://
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new SecurityException("Esquema no permitido: " + scheme);
            }
            return;
        }
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SecurityException("Esquema no permitido: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("URL sin host");
        }
        for (InetAddress addr : InetAddress.getAllByName(host)) {
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
                throw new SecurityException(
                        "El host resuelve a una dirección interna: " + host + " → " + addr.getHostAddress());
            }
            byte[] b = addr.getAddress();
            if (b.length == 4) {
                int first = b[0] & 0xff;
                int second = b[1] & 0xff;
                if (first == 100 && second >= 64 && second <= 127) { // CGNAT 100.64.0.0/10
                    throw new SecurityException("El host está en el rango CGNAT: " + addr.getHostAddress());
                }
            }
        }
    }

    /** Igual que {@link #assertPublic}, pero devolviendo un booleano para quien no quiera capturar. */
    public static boolean isPublic(String url) {
        try {
            assertPublic(URI.create(url));
            return true;
        } catch (SecurityException | UnknownHostException | IllegalArgumentException e) {
            return false;
        }
    }
}
