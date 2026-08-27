package com.nexaplatform.dropshipping.infrastructure.security.jwk;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.JwkKeyEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.JwkKeyRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.TokenCryptoService;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class JwkKeyServiceTest {

    @Mock
    private JwkKeyRepository jwkKeyRepository;

    // KEKs vacíos → genera un KEK efímero en memoria (válido para tests); encrypt/decrypt round-trip real.
    private final TokenCryptoService tokenCryptoService = newCryptoService();

    private JwkKeyService service;

    @BeforeEach
    void setUp() {
        service = new JwkKeyService(jwkKeyRepository, tokenCryptoService);
    }

    private static TokenCryptoService newCryptoService() {
        TokenCryptoService svc = new TokenCryptoService("");
        // @PostConstruct init() no lo invoca Spring en un test unitario; lo forzamos por reflexión.
        try {
            java.lang.reflect.Method init = TokenCryptoService.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(svc);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return svc;
    }

    /**
     * El par RSA se genera UNA VEZ para toda la clase, no una por entidad construida.
     *
     * <p>Generar una clave de 2048 bits cuesta unos 0,7 s y este método se llama cinco veces, así que
     * se pagaban 3,5 s en generar claves que ninguna prueba compara entre sí: lo único que se afirma
     * es que la pública no viene vacía y que dos entidades tienen `kid` distinto, y el `kid` se
     * sortea aparte. Compartir el material de clave no cambia ninguna aserción.
     */
    private static final KeyPair PAR_RSA = generarParRsa();

    private static KeyPair generarParRsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sin RSA no hay claves que rotar en las pruebas", e);
        }
    }

    /** Construye una entidad con un par RSA real, cifrando la privada igual que producción. */
    private JwkKeyEntity buildKeyEntity(boolean active, Instant createdAt) {
        try {
            KeyPair pair = PAR_RSA;
            String privB64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            JwkKeyEntity entity = JwkKeyEntity.builder()
                    .kid(UUID.randomUUID().toString())
                    .publicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                    .privateKey(tokenCryptoService.encrypt(privB64))
                    .active(active)
                    .build();
            // @Builder no cubre id/createdAt heredados de BaseEntity/AuditableEntity → setters tras build.
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(createdAt);
            return entity;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void activeKid_returnsKidOfMostRecentActiveKey() {
        JwkKeyEntity active = buildKeyEntity(true, Instant.now());
        when(jwkKeyRepository.findAllByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of(active));

        String kid = service.activeKid();

        assertThat(kid).isEqualTo(active.getKid());
    }

    @Test
    void asJwkSource_returnsSourceWithAtLeastOneKey() throws Exception {
        JwkKeyEntity active = buildKeyEntity(true, Instant.now());
        when(jwkKeyRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(active));

        JWKSource<SecurityContext> source = service.asJwkSource();

        // Selector que acepta cualquier clave → debe devolver la que tenemos almacenada.
        JWKSelector selector = new JWKSelector(new JWKMatcher.Builder().build());
        List<JWK> selected = source.get(selector, null);
        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).getKeyID()).isEqualTo(active.getKid());
    }

    @Test
    void init_generatesInitialKeyWhenNoActiveKeyExists() {
        when(jwkKeyRepository.findAllByActiveTrueOrderByCreatedAtDesc()).thenReturn(new ArrayList<>());
        when(jwkKeyRepository.save(any(JwkKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.init();

        ArgumentCaptor<JwkKeyEntity> captor = ArgumentCaptor.forClass(JwkKeyEntity.class);
        verify(jwkKeyRepository).save(captor.capture());
        JwkKeyEntity saved = captor.getValue();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getKid()).isNotBlank();
        assertThat(saved.getPublicKey()).isNotBlank();
        assertThat(saved.getPrivateKey()).isNotBlank();
    }

    @Test
    void rotateIfNeeded_rotatesWhenNewestOlderThan30Days_thenMoreKeysAndActiveKidChanges() {
        // Clave activa creada hace 31 días → debe rotar: desactiva la vieja y crea una nueva activa.
        Instant old = Instant.now().minus(31, ChronoUnit.DAYS);
        JwkKeyEntity oldActive = buildKeyEntity(true, old);
        String oldKid = oldActive.getKid();

        List<JwkKeyEntity> activeList = new ArrayList<>(List.of(oldActive));
        when(jwkKeyRepository.findAllByActiveTrueOrderByCreatedAtDesc()).thenReturn(activeList);
        when(jwkKeyRepository.saveAll(anyList())).thenReturn(activeList);
        List<JwkKeyEntity> saved = new ArrayList<>();
        when(jwkKeyRepository.save(any(JwkKeyEntity.class))).thenAnswer(inv -> {
            JwkKeyEntity e = inv.getArgument(0);
            saved.add(e);
            return e;
        });

        service.rotateIfNeeded();

        // La vieja quedó desactivada y marcada como rotada.
        assertThat(oldActive.isActive()).isFalse();
        assertThat(oldActive.getRotatedAt()).isNotNull();
        // Se generó una clave nueva, activa, con kid distinto al anterior.
        assertThat(saved).hasSize(1);
        JwkKeyEntity newKey = saved.get(0);
        assertThat(newKey.isActive()).isTrue();
        assertThat(newKey.getKid()).isNotEqualTo(oldKid);
    }

    @Test
    void rotateIfNeeded_doesNothingWhenNewestKeyIsRecent() {
        JwkKeyEntity recent = buildKeyEntity(true, Instant.now());
        when(jwkKeyRepository.findAllByActiveTrueOrderByCreatedAtDesc()).thenReturn(new ArrayList<>(List.of(recent)));

        service.rotateIfNeeded();

        // Ni rota (saveAll) ni genera (save): el stub estricto fallaría si los llamáramos.
        assertThat(recent.isActive()).isTrue();
        assertThat(recent.getRotatedAt()).isNull();
    }
}
