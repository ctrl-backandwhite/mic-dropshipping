package com.nexaplatform.dropshipping.infrastructure.integration.pricing;

import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.security.GeolocalizacionDelCdn;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puebla {@link PricingCountryHolder} con el país efectivo del comprador, que es el que decide el margen,
 * el IVA y el derecho de aduana.
 *
 * <h2>Por qué el orden importa</h2>
 *
 * <p>Hasta el 26-ago-2026 este filtro se fiaba de la cabecera {@code X-Country} tal cual llegara. La pone
 * el navegador, así que cualquiera podía cambiarla: el mismo usuario —con ES grabado en su ficha— obtenía
 * el producto a <b>13,72 EUR</b> con {@code X-Country: ES} y a <b>12,07 EUR</b> con {@code US}, sin los
 * 3 EUR de arancel y sin el 21 % de IVA, y el pedido se enviaba igualmente a Zaragoza. Un 12 % de
 * descuento escribiendo una línea en la consola del navegador.
 *
 * <p>La regla ahora, de más fiable a menos:
 * <ol>
 *   <li><b>El país de registro del usuario identificado.</b> Vive en el servidor y el cliente no lo toca.</li>
 *   <li><b>La cabecera de geolocalización del CDN</b> ({@code CF-IPCountry} y equivalentes). La pone la
 *       infraestructura por IP; el navegador no puede falsearla porque el CDN la sobrescribe.</li>
 *   <li><b>{@code X-Country}</b>, solo para invitados y solo si no hubo CDN. Es lo único que queda en
 *       desarrollo local o detrás de un proxy que no rellena la geolocalización, y ahí el riesgo es el
 *       mismo que el de cualquier visitante anónimo: no tiene cuenta ni historial que proteger.</li>
 * </ol>
 *
 * <p>Corre <b>después</b> de la cadena de Spring Security, que es lo que permite leer quién pide. Antes
 * iba en {@code HIGHEST_PRECEDENCE + 31}, muy por delante de la autenticación, y ahí el contexto de
 * seguridad todavía está vacío: por eso solo podía mirar cabeceras.
 *
 * <p>Vacío = sin país → {@code MarginService} usa las reglas sin país. Se limpia al terminar para no
 * contaminar el hilo del pool.
 */
@Slf4j
@Component
// Spring Security registra su cadena en el orden -100. Cualquier valor por encima corre DESPUÉS de la
// autenticación, que es lo que hace falta para saber quién pide; 0 lo deja además antes del dispatcher.
@Order(0)
@RequiredArgsConstructor
public class PricingCountryFilter extends OncePerRequestFilter {

    public static final String HEADER_COUNTRY = "X-Country";

    private final UserRepository userRepository;
    private final GeolocalizacionDelCdn cdn;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            PricingCountryHolder.set(resolveCountry(req));
            chain.doFilter(req, res);
        } finally {
            PricingCountryHolder.clear();
        }
    }

    private String resolveCountry(HttpServletRequest req) {
        String delUsuario = paisDelUsuarioIdentificado();
        if (delUsuario != null) {
            return delUsuario;
        }
        String delCdn = cdn.paisDeConfianza(req);
        if (delCdn != null) {
            return delCdn;
        }
        String delCliente = req.getHeader(HEADER_COUNTRY);
        return delCliente != null && !delCliente.isBlank() ? delCliente : null;
    }

    /**
     * El país de la ficha de quien ha iniciado sesión, o {@code null} si no hay sesión, si el usuario no
     * tiene país o si algo falla al leerlo.
     *
     * <p>Nunca propaga la excepción: un fallo consultando el país no puede tumbar la petición. Se degrada a
     * las cabeceras, que es exactamente el comportamiento anterior a este filtro.
     */
    private String paisDelUsuarioIdentificado() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
                return null;
            }
            UUID id = UUID.fromString(auth.getName());
            return userRepository.findById(id).map(UserEntity::getCountry)
                    .filter(c -> c != null && !c.isBlank()).orElse(null);
        } catch (IllegalArgumentException e) {
            // El subject no es un UUID: token de servicio o de cliente OAuth, no una persona con ficha.
            return null;
        } catch (Exception e) {
            log.debug("No se pudo resolver el país del usuario: {}", e.toString());
            return null;
        }
    }

}
