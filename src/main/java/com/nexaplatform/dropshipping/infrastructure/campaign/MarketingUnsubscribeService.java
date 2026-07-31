package com.nexaplatform.dropshipping.infrastructure.campaign;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.HmacVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Baja/alta de marketing por enlace de un clic (sin login) para los correos de campaña. El enlace lleva un
 * token stateless firmado con HMAC-SHA256: {@code base64url(userId) + "." + hmacHex(userId)}. Verificado el
 * token, se conmuta {@link UserEntity#isMarketingOptOut()} y así el usuario deja de entrar en la audiencia.
 */
@Service
public class MarketingUnsubscribeService {

    private final UserRepository userRepository;
    private final HmacVerifier hmac;
    private final String secret;

    public MarketingUnsubscribeService(UserRepository userRepository, HmacVerifier hmac,
            @Value("${nexadrop.email.unsubscribe-secret:dev-unsubscribe-secret-change-me}") String secret) {
        this.userRepository = userRepository;
        this.hmac = hmac;
        this.secret = secret;
    }

    /** Token firmado para el enlace de baja de un usuario. */
    public String tokenFor(UUID userId) {
        String id = userId.toString();
        String sig = hmac.sign(secret, id.getBytes(StandardCharsets.UTF_8));
        String idPart = Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));
        return idPart + "." + sig;
    }

    /** Aplica la baja (opt-out). Devuelve el usuario si el token es válido. */
    @Transactional
    public Optional<UserEntity> unsubscribe(String token) {
        return apply(token, true);
    }

    /** Revierte la baja (volver a recibir). Devuelve el usuario si el token es válido. */
    @Transactional
    public Optional<UserEntity> resubscribe(String token) {
        return apply(token, false);
    }

    private Optional<UserEntity> apply(String token, boolean optOut) {
        Optional<UUID> userId = verify(token);
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        Optional<UserEntity> user = userRepository.findById(userId.get());
        user.ifPresent(u -> {
            u.setMarketingOptOut(optOut);
            userRepository.save(u);
        });
        return user;
    }

    private Optional<UUID> verify(String token) {
        if (token == null || !token.contains(".")) {
            return Optional.empty();
        }
        int dot = token.lastIndexOf('.');
        String idPart = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        String id;
        try {
            id = new String(Base64.getUrlDecoder().decode(idPart), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (!hmac.verify(secret, id.getBytes(StandardCharsets.UTF_8), sig)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
