package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Lee la cabecera {@code X-Client} y puebla {@link PaymentClientHolder}.
 *
 * <p>Lo que viaja es un IDENTIFICADOR («mobile»), nunca una dirección de vuelta. El servidor traduce
 * ese identificador a una dirección que él mismo tiene configurada; aceptar la dirección del cliente
 * convertiría el cobro en un redirector abierto hacia donde quisiera quien atacara. Falsificar la
 * cabecera no da nada: como mucho se devuelve a quien la falsifica al esquema de una aplicación que
 * no tiene abierta.
 *
 * <p>Va con el mismo orden que el filtro de la divisa: después de las cabeceras de seguridad y del
 * límite de peticiones.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 31)
public class PaymentClientFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Client";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            PaymentClientHolder.set(req.getHeader(HEADER));
            chain.doFilter(req, res);
        } finally {
            PaymentClientHolder.clear();
        }
    }
}
