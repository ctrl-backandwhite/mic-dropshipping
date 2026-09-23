package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.usecase.impl.WalletUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.domain.repository.WalletRepository;
import com.nexaplatform.dropshipping.domain.repository.WalletTransactionRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
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
class WalletUseCaseImplTest {

    @Mock
    WalletRepository walletRepository;
    @Mock
    WalletTransactionRepository txRepository;
    @Mock
    AuditLogger auditLogger;
    @Mock
    CurrencyRateService currencyService;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.integration.search.WalletIndexer walletIndexer;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.integration.search.WalletSearchService walletSearchService;

    private WalletUseCaseImpl useCase() {
        return new WalletUseCaseImpl(walletRepository, txRepository, auditLogger, currencyService, walletIndexer,
                walletSearchService);
    }

    @Test
    void adminTopup_recordsDepositAndReturnsTransaction() {
        UUID userId = UUID.randomUUID();
        Wallet wallet = Wallet.builder().balanceUsdCents(0L).holdUsdCents(0L).status("ACTIVE").build();
        wallet.setId(UUID.randomUUID());
        // El movimiento de saldo (topup admin) carga la wallet con BLOQUEO de fila (findByUserIdForUpdate),
        // ya no por findByUserId; ese queda lenient por si algún otro camino lo usa.
        org.mockito.Mockito.lenient().when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));

        // El saldo se mueve con un UPDATE atómico en la base (applyBalanceDelta), no leyendo y guardando la
        // entidad: es lo que impide el doble gasto entre checkouts simultáneos. Se reproduce esa semántica
        // sobre la wallet simulada para que la prueba siga comprobando la regla, no el mecanismo.
        org.mockito.Mockito.lenient()
                .when(walletRepository.applyBalanceDelta(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(inv -> {
                    var actual = walletRepository.findByUserId(inv.getArgument(0));
                    if (actual.isEmpty()) {
                        return false;
                    }
                    long nuevo = actual.get().getBalanceUsdCents() + (long) inv.getArgument(1);
                    if (nuevo < 0) {
                        return false;
                    }
                    actual.get().setBalanceUsdCents(nuevo);
                    return true;
                });
        org.mockito.Mockito.lenient().when(walletRepository.currentBalanceCents(any())).thenAnswer(
                inv -> walletRepository.findByUserId(inv.getArgument(0)).map(w -> w.getBalanceUsdCents()).orElse(0L));
        when(txRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        // Ya no se stubbea save(): el saldo se mueve con applyBalanceDelta, no guardando la entidad.
        when(txRepository.save(any())).thenAnswer(inv -> {
            WalletTransaction t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        WalletTransaction result = useCase().adminTopup(userId, 1000L, null, "key-1");

        assertThat(result.getAmountUsdCents()).isEqualTo(1000L);
        assertThat(result.getKind()).isEqualTo("DEPOSIT");
        assertThat(result.getBalanceAfterCents()).isEqualTo(1000L);
    }

    @Test
    void adminAdjustEntry_requiresDescription() {
        WalletUseCaseImpl svc = useCase();
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> svc.adminAdjustEntry(userId, -500L, "  ", "key-2"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
