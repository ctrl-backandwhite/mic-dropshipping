package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserSessionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserSessionRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sesiones/dispositivos conectados del usuario. Cada navegador lleva una cookie {@code nx_device} que
 * identifica el dispositivo; en cada login se registra/actualiza una fila con el dispositivo (parseado
 * del User-Agent), la IP y el last-seen. Permite listar los dispositivos y revocarlos (el filtro de
 * enforcement cierra la sesión del dispositivo revocado en su siguiente petición).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceSessionService {

    public static final String COOKIE = "nx_device";

    private final UserSessionRepository repository;

    /** Vista de un dispositivo conectado (sin exponer el device_token). */
    public record SessionView(UUID id, String device, String ip, Instant createdAt, Instant lastSeenAt,
            boolean current) {
    }

    /** Registra/actualiza el dispositivo en cada login y emite/renueva la cookie nx_device. */
    @Transactional
    public void recordLogin(UUID userId, HttpServletRequest req, HttpServletResponse res) {
        String token = readCookie(req);
        UserSessionEntity row = token != null ? repository.findByDeviceToken(token).orElse(null) : null;
        if (row == null || !userId.equals(row.getUserId())) {
            token = UUID.randomUUID().toString().replace("-", "");
            row = UserSessionEntity.builder().userId(userId).deviceToken(token).createdAt(Instant.now()).build();
        }
        row.setDevice(parseDevice(req.getHeader("User-Agent")));
        row.setUserAgent(trim(req.getHeader("User-Agent")));
        row.setIp(clientIp(req));
        row.setLastSeenAt(Instant.now());
        row.setRevokedAt(null);
        repository.save(row);
        writeCookie(res, token);
    }

    /** Dispositivos conectados (no revocados) del usuario, marcando el actual. */
    @Transactional(readOnly = true)
    public List<SessionView> list(UUID userId, HttpServletRequest req) {
        String current = readCookie(req);
        return repository.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId).stream()
                .map(s -> new SessionView(s.getId(), s.getDevice(), s.getIp(), s.getCreatedAt(), s.getLastSeenAt(),
                        s.getDeviceToken().equals(current)))
                .toList();
    }

    /** Revoca un dispositivo del usuario (valida propiedad). */
    @Transactional
    public void revoke(UUID userId, UUID sessionId) {
        repository.findById(sessionId).filter(s -> userId.equals(s.getUserId())).ifPresent(s -> {
            s.setRevokedAt(Instant.now());
            repository.save(s);
        });
    }

    /** ¿La cookie de dispositivo de esta petición corresponde a una sesión revocada? (enforcement). */
    @Transactional(readOnly = true)
    public boolean isRevoked(HttpServletRequest req) {
        String token = readCookie(req);
        if (token == null) {
            return false;
        }
        return repository.findByDeviceToken(token).map(s -> s.getRevokedAt() != null).orElse(false);
    }

    /* ===== helpers ===== */

    private String readCookie(HttpServletRequest req) {
        if (req.getCookies() == null) {
            return null;
        }
        for (Cookie c : req.getCookies()) {
            if (COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private void writeCookie(HttpServletResponse res, String token) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, token).httpOnly(true).path("/").sameSite("Lax")
                .maxAge(Duration.ofDays(365)).build();
        res.addHeader("Set-Cookie", cookie.toString());
    }

    private String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private String trim(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    /** Etiqueta legible "Navegador · SO" a partir del User-Agent. */
    /**
     * Navegadores, EN ORDEN DE COMPROBACIÓN. El orden no es cosmético: Edge y Opera se anuncian también
     * como Chrome, y Chrome se anuncia además como Safari, así que el más específico va primero.
     */
    private static final List<Map.Entry<String[], String>> BROWSERS = List.of(
            Map.entry(new String[] {"Edg"}, "Edge"),
            Map.entry(new String[] {"OPR", "Opera"}, "Opera"),
            Map.entry(new String[] {"Chrome"}, "Chrome"),
            Map.entry(new String[] {"Firefox"}, "Firefox"),
            Map.entry(new String[] {"Safari"}, "Safari"));

    /**
     * Sistemas operativos, EN ORDEN DE COMPROBACIÓN. El identificador de iPhone y iPad contiene
     * "Mac OS X", así que iOS tiene que mirarse ANTES que macOS o todos los móviles de Apple se
     * etiquetarían como ordenadores.
     */
    private static final List<Map.Entry<String[], String>> OPERATING_SYSTEMS = List.of(
            Map.entry(new String[] {"Windows"}, "Windows"),
            Map.entry(new String[] {"Android"}, "Android"),
            Map.entry(new String[] {"iPhone", "iPad", "iPod"}, "iOS"),
            Map.entry(new String[] {"Macintosh", "Mac OS"}, "macOS"),
            Map.entry(new String[] {"Linux"}, "Linux"));

    /**
     * Nombre legible del dispositivo a partir del identificador que manda el navegador, para que el
     * usuario reconozca sus sesiones abiertas y sepa cuál revocar.
     */
    private String parseDevice(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Dispositivo desconocido";
        }
        String browser = firstMatch(ua, BROWSERS, "Navegador");
        String os = firstMatch(ua, OPERATING_SYSTEMS, "");
        return os.isEmpty() ? browser : browser + " · " + os;
    }

    /** Primera entrada de la tabla cuyo identificador aparezca en el texto; el orden manda. */
    private static String firstMatch(String ua, List<Map.Entry<String[], String>> table, String fallback) {
        for (Map.Entry<String[], String> entry : table) {
            for (String marker : entry.getKey()) {
                if (ua.contains(marker)) {
                    return entry.getValue();
                }
            }
        }
        return fallback;
    }
}
