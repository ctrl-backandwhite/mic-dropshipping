package com.nexaplatform.dropshipping.infrastructure.security.jwk;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.JwkKeyEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.JwkKeyRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwkKeyService {

    private final JwkKeyRepository jwkKeyRepository;

    @PostConstruct
    void init() {
        if (jwkKeyRepository.findAllByActiveTrueOrderByCreatedAtDesc().isEmpty()) {
            log.info("No active JWK key found — generating initial key");
            generateAndStore();
        }
    }

    public synchronized JwkKeyEntity generateAndStore() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            String kid = UUID.randomUUID().toString();
            JwkKeyEntity entity = JwkKeyEntity.builder()
                    .kid(kid)
                    .publicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                    .privateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()))
                    .active(true)
                    .build();
            return jwkKeyRepository.save(entity);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate RSA key pair", e);
        }
    }

    public JWKSource<SecurityContext> asJwkSource() {
        return (jwkSelector, context) -> jwkSelector.select(loadJwkSet());
    }

    public JWKSet loadJwkSet() {
        List<RSAKey> keys = jwkKeyRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toRsaKey)
                .collect(Collectors.toList());
        return new JWKSet(keys.stream().map(k -> (com.nimbusds.jose.jwk.JWK) k).toList());
    }

    private RSAKey toRsaKey(JwkKeyEntity entity) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            byte[] pubBytes = Base64.getDecoder().decode(entity.getPublicKey());
            byte[] privBytes = Base64.getDecoder().decode(entity.getPrivateKey());
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(pubBytes));
            RSAPrivateKey priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            return new RSAKey.Builder(pub).privateKey(priv).keyID(entity.getKid()).build();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decode JWK " + entity.getKid(), e);
        }
    }

    /** Rotate keys every 30 days; keep older ones to validate still-active tokens. */
    @Scheduled(cron = "0 0 4 * * *")
    public void rotateIfNeeded() {
        List<JwkKeyEntity> active = jwkKeyRepository.findAllByActiveTrueOrderByCreatedAtDesc();
        if (active.isEmpty()) {
            generateAndStore();
            return;
        }
        JwkKeyEntity newest = active.get(0);
        Instant rotateAt = newest.getCreatedAt().plus(30, ChronoUnit.DAYS);
        if (Instant.now().isAfter(rotateAt)) {
            log.info("Rotating JWK key (newest is older than 30 days)");
            active.forEach(k -> {
                k.setActive(false);
                k.setRotatedAt(Instant.now());
            });
            jwkKeyRepository.saveAll(active);
            generateAndStore();
        }
    }
}
