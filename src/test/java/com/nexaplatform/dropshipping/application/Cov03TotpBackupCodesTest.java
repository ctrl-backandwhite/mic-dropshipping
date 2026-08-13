package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.TotpService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.TotpSecretEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.TotpSecretRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.TokenCryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Segundo factor: activación y códigos de respaldo.
 *
 * <p>Los códigos de respaldo son la única puerta de entrada cuando el usuario pierde el móvil. Que se
 * guarden cifrados, que cada uno valga UNA vez y que no se pueda activar el 2FA sin demostrar antes que
 * la aplicación autenticadora funciona es lo que impide dejar a alguien fuera de su propia cuenta.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov03TotpBackupCodesTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXP"; // vector base32 conocido

    @Mock
    TotpSecretRepository repo;
    @Mock
    UserRepository userRepo;
    @Mock
    TokenCryptoService crypto;
    @Mock
    ObjectMapper mapper;
    @Mock
    PasswordEncoder passwordEncoder;

    @InjectMocks
    TotpService service;

    private final UUID userId = UUID.randomUUID();

    private static TotpSecretEntity registro(boolean activo) {
        return TotpSecretEntity.builder().userId(UUID.randomUUID()).secretEnc("enc").enabled(activo).build();
    }

    /* ==================== activación ==================== */

    @Test
    void activarElSegundoFactorExigeUnCodigoValidoYEntregaDiezCodigosDeRespaldo() throws Exception {
        TotpSecretEntity rec = registro(false);
        when(repo.findById(userId)).thenReturn(Optional.of(rec));
        when(crypto.decrypt("enc")).thenReturn(SECRET);
        when(mapper.writeValueAsString(any())).thenReturn("[]");

        TotpService.EnableResult res = service.verifyAndEnable(userId, totp(SECRET, System.currentTimeMillis() / 1000));

        assertThat(res.backupCodes()).hasSize(10).doesNotHaveDuplicates()
                .allMatch(c -> c.matches("[A-Z2-7]{5}-[A-Z2-7]{5}"));
        assertThat(rec.isEnabled()).isTrue();
        verify(repo).save(rec);
    }

    @Test
    void losCodigosDeRespaldoNuncaSeGuardanEnClaro() throws Exception {
        // Si se guardaran tal cual, quien leyera la tabla entraría en cualquier cuenta con 2FA.
        TotpSecretEntity rec = registro(false);
        when(repo.findById(userId)).thenReturn(Optional.of(rec));
        when(crypto.decrypt("enc")).thenReturn(SECRET);
        String otp = totp(SECRET, System.currentTimeMillis() / 1000);

        TotpService.EnableResult res = service.verifyAndEnable(userId, otp);

        ArgumentCaptor<List<String>> hashes = ArgumentCaptor.captor();
        verify(mapper).writeValueAsString(hashes.capture());
        assertThat(hashes.getValue()).hasSize(10).noneMatch(res.backupCodes()::contains);
        assertThat(hashes.getValue()).allMatch(h -> h.startsWith("$2"));
    }

    @Test
    void noSePuedeActivarDosVecesElSegundoFactor() {
        when(repo.findById(userId)).thenReturn(Optional.of(registro(true)));

        assertThatThrownBy(() -> service.verifyAndEnable(userId, "123456")).isInstanceOf(BusinessException.class);
    }

    @Test
    void noSePuedeActivarSinHaberPedidoAntesElCodigoQr() {
        when(repo.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyAndEnable(userId, "123456")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void repetirLaPreparacionReutilizaLaMismaFilaYDejaElSegundoFactorApagado() {
        // Crear una fila nueva por cada QR dejaría secretos huérfanos y podría activar el que no es.
        TotpSecretEntity previo = registro(false);
        when(userRepo.findById(userId)).thenReturn(Optional.of(UserEntity.builder().email("u@x.com").build()));
        when(repo.findById(userId)).thenReturn(Optional.of(previo));
        when(crypto.encrypt(anyString())).thenReturn("enc2");

        TotpService.SetupResult res = service.setup(userId);

        assertThat(res.otpauthUrl()).contains("digits=6").contains("period=30").contains("u%40x.com");
        assertThat(previo.getSecretEnc()).isEqualTo("enc2");
        assertThat(previo.isEnabled()).isFalse();
        verify(repo).save(previo);
    }

    /* ==================== códigos de respaldo ==================== */

    @Test
    void unCodigoDeRespaldoSoloSirveUnaVez() throws Exception {
        String codigo = "ABCDE-FGHIJ";
        String hash = new BCryptPasswordEncoder(10).encode(codigo);
        TotpSecretEntity rec = registro(true);
        rec.setRecoveryCodesHash("[\"" + hash + "\"]");
        when(repo.findByIdForUpdate(userId)).thenReturn(Optional.of(rec));
        List<String> guardados = new ArrayList<>(List.of(hash));
        when(mapper.readValue(anyString(), eq(List.class))).thenReturn(guardados);
        when(mapper.writeValueAsString(any())).thenReturn("[null]");

        assertThat(service.consumeBackupCode(userId, codigo)).isTrue();
        assertThat(guardados).containsExactly((String) null); // el hash se anula al gastarse
        assertThat(service.consumeBackupCode(userId, codigo)).isFalse();
    }

    @Test
    void sinSegundoFactorActivoLosCodigosDeRespaldoNoValen() {
        when(repo.findById(userId)).thenReturn(Optional.empty());
        assertThat(service.consumeBackupCode(userId, "ABCDE-FGHIJ")).isFalse();

        when(repo.findById(userId)).thenReturn(Optional.of(registro(false)));
        assertThat(service.consumeBackupCode(userId, "ABCDE-FGHIJ")).isFalse();
    }

    @Test
    void sinCodigosGeneradosTodaviaNoSePuedeConsumirNinguno() {
        TotpSecretEntity rec = registro(true);
        rec.setRecoveryCodesHash(null);
        when(repo.findById(userId)).thenReturn(Optional.of(rec));

        assertThat(service.consumeBackupCode(userId, "ABCDE-FGHIJ")).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    void unaListaDeCodigosCorruptaSeRechazaSinTumbarElLogin() throws Exception {
        // Es la puerta de entrada de alguien que ya no tiene el móvil: un JSON roto no puede propagarse
        // como error 500 del login.
        TotpSecretEntity rec = registro(true);
        rec.setRecoveryCodesHash("{no es json}");
        when(repo.findById(userId)).thenReturn(Optional.of(rec));
        when(mapper.readValue(anyString(), eq(List.class))).thenThrow(new IllegalArgumentException("roto"));

        assertThat(service.consumeBackupCode(userId, "ABCDE-FGHIJ")).isFalse();
    }

    @Test
    void regenerarLosCodigosInvalidaLosAnterioresYEntregaDiezNuevos() throws Exception {
        TotpSecretEntity rec = registro(true);
        rec.setRecoveryCodesHash("[\"viejo\"]");
        when(repo.findById(userId)).thenReturn(Optional.of(rec));
        when(mapper.writeValueAsString(any())).thenReturn("[\"nuevo\"]");

        List<String> codigos = service.regenerateBackupCodes(userId);

        assertThat(codigos).hasSize(10).doesNotHaveDuplicates();
        assertThat(rec.getRecoveryCodesHash()).isEqualTo("[\"nuevo\"]");
        verify(repo).save(rec);
    }

    @Test
    void noSePuedenRegenerarCodigosSiElSegundoFactorNoEstaActivo() {
        when(repo.findById(userId)).thenReturn(Optional.of(registro(false)));
        assertThatThrownBy(() -> service.regenerateBackupCodes(userId)).isInstanceOf(BusinessException.class);

        when(repo.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.regenerateBackupCodes(userId)).isInstanceOf(NotFoundException.class);
    }

    /* ==================== estado y desactivación ==================== */

    @Test
    void elEstadoDelSegundoFactorSeLeeDelRegistroYNoSeSuponeActivo() {
        when(repo.findById(userId)).thenReturn(Optional.empty());
        assertThat(service.isEnabled(userId)).isFalse();

        when(repo.findById(userId)).thenReturn(Optional.of(registro(false)));
        assertThat(service.isEnabled(userId)).isFalse();

        when(repo.findById(userId)).thenReturn(Optional.of(registro(true)));
        assertThat(service.isEnabled(userId)).isTrue();
    }

    @Test
    void desactivarBorraElSecretoParaQueNoQuedeUtilizable() {
        service.disable(userId);

        verify(repo).deleteById(userId);
    }

    /* ===== implementación RFC 6238 de referencia, independiente del código probado ===== */

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static String totp(String base32Secret, long epochSeconds) {
        try {
            byte[] key = base32Decode(base32Secret);
            long counter = epochSeconds / 30;
            byte[] data = new byte[8];
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (counter & 0xFF);
                counter >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int code = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%06d", code % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] base32Decode(String s) {
        String limpio = s.toUpperCase().replaceAll("[^A-Z2-7]", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bits = 0;
        int value = 0;
        for (char c : limpio.toCharArray()) {
            int idx = BASE32.indexOf(c);
            if (idx < 0) {
                continue;
            }
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((value >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
