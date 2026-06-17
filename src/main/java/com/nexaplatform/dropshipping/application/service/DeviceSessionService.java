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
    private String parseDevice(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Dispositivo desconocido";
        }
        String browser = ua.contains("Edg") ? "Edge"
                : ua.contains("OPR") || ua.contains("Opera") ? "Opera"
                        : ua.contains("Chrome") ? "Chrome"
                                : ua.contains("Firefox") ? "Firefox"
                                        : ua.contains("Safari") ? "Safari" : "Navegador";
        // OJO: el UA de iPhone/iPad contiene "Mac OS X", así que iOS se comprueba ANTES que macOS.
        String os = ua.contains("Windows") ? "Windows"
                : ua.contains("Android") ? "Android"
                        : (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) ? "iOS"
                                : (ua.contains("Macintosh") || ua.contains("Mac OS")) ? "macOS"
                                        : ua.contains("Linux") ? "Linux" : "";
        return os.isEmpty() ? browser : browser + " · " + os;
    }
}
