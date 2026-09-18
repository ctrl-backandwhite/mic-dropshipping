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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TotpServiceTest {

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

    @Test
    void setup_throwsNotFoundWhenUserMissing() {
        when(userRepo.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setup(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void setup_throwsWhenAlreadyEnabled() {
        when(userRepo.findById(userId)).thenReturn(Optional.of(UserEntity.builder().email("u@x").build()));
        when(repo.findById(userId)).thenReturn(Optional.of(enabledRec()));
        assertThatThrownBy(() -> service.setup(userId)).isInstanceOf(BusinessException.class);
    }

    @Test
    void setup_generatesSecretAndOtpauthUrlAndSavesDisabled() {
        when(userRepo.findById(userId)).thenReturn(Optional.of(UserEntity.builder().email("u@x").build()));
        when(repo.findById(userId)).thenReturn(Optional.empty());
        when(crypto.encrypt(anyString())).thenReturn("enc");

        TotpService.SetupResult result = service.setup(userId);

        assertThat(result.base32Secret()).isNotBlank();
        assertThat(result.otpauthUrl()).contains("issuer=NX036").contains("secret=" + result.base32Secret());
        ArgumentCaptor<TotpSecretEntity> captor = ArgumentCaptor.forClass(TotpSecretEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().isEnabled()).isFalse();
        assertThat(captor.getValue().getSecretEnc()).isEqualTo("enc");
    }

    @Test
    void verifyOtp_falseWhenNoRecordOrDisabled() {
        when(repo.findByIdForUpdate(userId)).thenReturn(Optional.empty());
        assertThat(service.verifyOtp(userId, "123456")).isFalse();

        when(repo.findByIdForUpdate(userId)).thenReturn(Optional.of(disabledRec()));
        assertThat(service.verifyOtp(userId, "123456")).isFalse();
    }

    @Test
    void verifyOtp_falseForWrongLength() {
        when(repo.findByIdForUpdate(userId)).thenReturn(Optional.of(enabledRec()));
        when(crypto.decrypt("enc")).thenReturn(SECRET);
        assertThat(service.verifyOtp(userId, "123")).isFalse();
    }

    @Test
    void verifyOtp_trueForValidCodeAndUpdatesLastUsed() {
        when(repo.findByIdForUpdate(userId)).thenReturn(Optional.of(enabledRec()));
        when(crypto.decrypt("enc")).thenReturn(SECRET);
        String valid = totp(SECRET, System.currentTimeMillis() / 1000);

        assertThat(service.verifyOtp(userId, valid)).isTrue();
        verify(repo).save(any(TotpSecretEntity.class)); // lastUsedAt actualizado
    }

    @Test
    void verifyAndEnable_invalidOtpThrows() {
        when(repo.findById(userId)).thenReturn(Optional.of(disabledRec()));
        when(crypto.decrypt("enc")).thenReturn(SECRET);
        String valid = totp(SECRET, System.currentTimeMillis() / 1000);
        String wrong = valid.equals("000000") ? "111111" : "000000";
        assertThatThrownBy(() -> service.verifyAndEnable(userId, wrong)).isInstanceOf(BusinessException.class);
    }

    @Test
    void consumeBackupCode_acceptsMatchingCodeOnceAndInvalidatesIt() throws Exception {
        String code = "ABCDE-FGHIJ";
        String hash = new BCryptPasswordEncoder(10).encode(code);
        TotpSecretEntity rec = enabledRec();
        rec.setRecoveryCodesHash("[\"" + hash + "\"]");
        when(repo.findByIdForUpdate(userId)).thenReturn(Optional.of(rec));
        when(mapper.readValue(anyString(), eq(List.class))).thenReturn(new java.util.ArrayList<>(List.of(hash)));
        when(mapper.writeValueAsString(any())).thenReturn("[null]");

        assertThat(service.consumeBackupCode(userId, code)).isTrue();
        assertThat(service.consumeBackupCode(userId, "WRONG-CODES")).isFalse();
    }

    @Test
    void disableWithPassword_wrongPasswordThrowsAndDoesNotDelete() {
        when(userRepo.findById(userId))
                .thenReturn(Optional.of(UserEntity.builder().email("u@x").passwordHash("h").build()));
        when(passwordEncoder.matches("bad", "h")).thenReturn(false);
        assertThatThrownBy(() -> service.disableWithPassword(userId, "bad")).isInstanceOf(BusinessException.class);
        verify(repo, never()).deleteById(any());
    }

    @Test
    void disableWithPassword_correctPasswordDeletesRecord() {
        when(userRepo.findById(userId))
                .thenReturn(Optional.of(UserEntity.builder().email("u@x").passwordHash("h").build()));
        when(passwordEncoder.matches("good", "h")).thenReturn(true);
        service.disableWithPassword(userId, "good");
        verify(repo).deleteById(userId);
    }

    /* ===== fixtures ===== */

    private static TotpSecretEntity enabledRec() {
        return TotpSecretEntity.builder().userId(UUID.randomUUID()).secretEnc("enc").enabled(true).build();
    }

    private static TotpSecretEntity disabledRec() {
        return TotpSecretEntity.builder().userId(UUID.randomUUID()).secretEnc("enc").enabled(false).build();
    }

    /* ===== RFC6238 de referencia para el test (cross-check independiente del SUT) ===== */

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
            code = code % 1_000_000;
            return String.format("%06d", code);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] base32Decode(String s) {
        s = s.toUpperCase().replaceAll("[^A-Z2-7]", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bits = 0, value = 0;
        for (char c : s.toCharArray()) {
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
