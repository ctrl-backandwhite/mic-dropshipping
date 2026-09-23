package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.TotpService;
import com.nexaplatform.dropshipping.application.usecase.impl.TotpUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.TotpSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TotpUseCaseImpl}. The use case is a thin orchestrator over
 * {@link TotpService}: it only maps the service result records into domain values and
 * delegates the rest verbatim. The RFC-6238 crypto, secret encryption and backup-code
 * hashing live in {@code TotpService} and are covered by {@code TotpServiceTest}; here we
 * only assert correct delegation (arguments) and result propagation.
 */
@ExtendWith(MockitoExtension.class)
class TotpUseCaseImplTest {

    @Mock
    TotpService totpService;

    @InjectMocks
    TotpUseCaseImpl useCase;

    @Test
    void setup_delegatesAndMapsSetupResultIntoDomainValue() {
        UUID userId = UUID.randomUUID();
        when(totpService.setup(userId)).thenReturn(
                new TotpService.SetupResult("JBSWY3DPEHPK3PXP", "otpauth://totp/NX036:u@x?secret=JBSWY3DPEHPK3PXP"));

        TotpSetup result = useCase.setup(userId);

        assertThat(result.getBase32Secret()).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(result.getOtpauthUrl()).isEqualTo("otpauth://totp/NX036:u@x?secret=JBSWY3DPEHPK3PXP");
        verify(totpService).setup(userId);
    }

    @Test
    void verifyAndEnable_delegatesWithOtpAndReturnsBackupCodes() {
        UUID userId = UUID.randomUUID();
        List<String> codes = List.of("AAAAA-BBBBB", "CCCCC-DDDDD");
        when(totpService.verifyAndEnable(userId, "123456")).thenReturn(new TotpService.EnableResult(codes));

        List<String> result = useCase.verifyAndEnable(userId, "123456");

        assertThat(result).isEqualTo(codes);
        verify(totpService).verifyAndEnable(userId, "123456");
    }

    @Test
    void disableWithPassword_delegatesWithRawPassword() {
        UUID userId = UUID.randomUUID();

        useCase.disableWithPassword(userId, "s3cret");

        verify(totpService).disableWithPassword(userId, "s3cret");
    }

    @Test
    void regenerateBackupCodes_delegatesAndPropagatesCodes() {
        UUID userId = UUID.randomUUID();
        List<String> codes = List.of("11111-22222", "33333-44444");
        when(totpService.regenerateBackupCodes(userId)).thenReturn(codes);

        List<String> result = useCase.regenerateBackupCodes(userId);

        assertThat(result).isSameAs(codes);
        verify(totpService).regenerateBackupCodes(userId);
    }

    @Test
    void isEnabled_propagatesServiceFlag() {
        UUID userId = UUID.randomUUID();
        when(totpService.isEnabled(userId)).thenReturn(true);

        assertThat(useCase.isEnabled(userId)).isTrue();
        verify(totpService).isEnabled(userId);
    }
}
