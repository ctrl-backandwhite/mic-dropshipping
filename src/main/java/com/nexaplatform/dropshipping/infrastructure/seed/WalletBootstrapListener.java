package com.nexaplatform.dropshipping.infrastructure.seed;

import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Ensures every existing user has a wallet. New registrations also call WalletUseCase directly. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletBootstrapListener {

    private final UserRepository userRepository;
    private final WalletUseCase walletUseCase;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureWallets(ApplicationReadyEvent event) {
        int created = 0;
        for (var u : userRepository.findAll()) {
            try {
                walletUseCase.getOrCreate(u.getId());
                created++;
            } catch (Exception e) {
                log.warn("Could not ensure wallet for {}: {}", u.getEmail(), e.getMessage());
            }
        }
        log.info("Wallet bootstrap: ensured {} wallets", created);
    }
}
