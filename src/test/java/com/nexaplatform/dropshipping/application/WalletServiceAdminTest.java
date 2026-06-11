package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxResultDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminWalletMapper;
import com.nexaplatform.dropshipping.api.mapper.MeWalletDtoMapper;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.WalletService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletTransactionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WalletRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WalletTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceAdminTest {

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository txRepository;
    @Mock UserRepository userRepository;
    @Mock AuditLogger auditLogger;
    @Mock AdminWalletMapper adminWalletMapper;
    @Mock MeWalletDtoMapper meWalletDtoMapper;
    @Mock CurrencyRateService currencyService;

    private WalletService service() {
        return new WalletService(walletRepository, txRepository, userRepository, auditLogger,
                adminWalletMapper, meWalletDtoMapper, currencyService);
    }

    @Test
    void adminTopup_recordsDepositAndMapsResult() {
        UUID userId = UUID.randomUUID();
        WalletEntity wallet = WalletEntity.builder().balanceUsdCents(0L).holdUsdCents(0L).status("ACTIVE").build();
        wallet.setId(UUID.randomUUID());
        when(walletRepository.findByUser_Id(userId)).thenReturn(Optional.of(wallet));
        when(txRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(txRepository.save(any())).thenAnswer(inv -> {
            WalletTransactionEntity t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        when(adminWalletMapper.toResult(any()))
                .thenReturn(AdminWalletTxResultDtoOut.builder().balanceAfter(1000L).amountCents(1000L).build());

        AdminWalletTxResultDtoOut result = service().adminTopup(userId, 1000L, null, "key-1");

        assertThat(result.getAmountCents()).isEqualTo(1000L);
    }

    @Test
    void adminAdjustEntry_requiresDescription() {
        WalletService svc = service();
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> svc.adminAdjustEntry(userId, -500L, "  ", "key-2"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
