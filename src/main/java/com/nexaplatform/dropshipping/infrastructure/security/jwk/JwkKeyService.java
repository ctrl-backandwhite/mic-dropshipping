package com.nexaplatform.dropshipping.infrastructure.security.jwk;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.JwkKeyEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.JwkKeyRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.TokenCryptoService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
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
    private final TokenCryptoService tokenCryptoService;

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
            // La clave PÚBLICA se guarda en claro (es pública); la PRIVADA se cifra en reposo
            // (AES-256-GCM vía TokenCryptoService / NX_TOKEN_KEKS). Si se filtra la BD, sin la KEK
            // no se puede recuperar la clave de firma → no se pueden forjar tokens.
            String privB64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            JwkKeyEntity entity = JwkKeyEntity.builder().kid(kid)
                    .publicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                    .privateKey(tokenCryptoService.encrypt(privB64)).active(true).build();
            return jwkKeyRepository.save(entity);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate RSA key pair", e);
        }
    }

    public JWKSource<SecurityContext> asJwkSource() {
        return (jwkSelector, context) -> jwkSelector.select(loadJwkSet());
    }

    /**
     * {@code kid} de la clave ACTIVA más reciente, para firmar. El {@link JWKSource} expone TODAS
     * las claves (hay que validar tokens firmados con claves ya rotadas), así que al firmar hay que
     * indicar el {@code kid} explícitamente: si no, con &gt;1 clave el encoder no sabe cuál elegir
     * y falla con "multiple keys for the signing algorithm".
     */
    public String activeKid() {
        return jwkKeyRepository.findAllByActiveTrueOrderByCreatedAtDesc().stream().findFirst()
                .map(JwkKeyEntity::getKid)
                .orElseThrow(() -> new IllegalStateException("No active JWK key available to sign tokens"));
    }

    public JWKSet loadJwkSet() {
        List<RSAKey> keys = jwkKeyRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toRsaKey)
                .collect(Collectors.toList());
        return new JWKSet(keys.stream().map(k -> (com.nimbusds.jose.jwk.JWK) k).toList());
    }

    private RSAKey toRsaKey(JwkKeyEntity entity) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            byte[] pubBytes = Base64.getDecoder().decode(entity.getPublicKey());
            // decrypt() devuelve el Base64 en claro tal cual si la entrada no lleva prefijo
            // `gcm:`/`enc:` → retrocompatibilidad automática con claves antiguas sin cifrar.
            byte[] privBytes = Base64.getDecoder().decode(tokenCryptoService.decrypt(entity.getPrivateKey()));
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
