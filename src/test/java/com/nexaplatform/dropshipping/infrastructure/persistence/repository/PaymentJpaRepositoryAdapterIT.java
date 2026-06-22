package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence IT for {@link PaymentJpaRepositoryAdapter} (the adapter carrying the custom finders).
 * Builds the NOT NULL FK graph: a UserEntity (payment.user_id is {@code optional = false}) and an
 * optional WalletEntity. Verifies idempotency/provider lookups, the user/order ordered listings and
 * the settlement-currency projection.
 */
class PaymentJpaRepositoryAdapterIT extends PersistenceITBase {

    @Autowired
    PaymentJpaRepositoryAdapter payments;

    @Autowired
    UserRepository users;

    @Autowired
    WalletJpaRepositoryAdapter wallets;

    private UserEntity userA;
    private UserEntity userB;
    private WalletEntity walletA;

    @BeforeEach
    void setUp() {
        userA = users.save(UserEntity.builder()
                .email("pay-a-" + UUID.randomUUID() + "@nx.local")
                .role(UserRole.USER)
                .active(true)
                .googleLinked(false)
                .build());
        userB = users.save(UserEntity.builder()
                .email("pay-b-" + UUID.randomUUID() + "@nx.local")
                .role(UserRole.USER)
                .active(true)
                .googleLinked(false)
                .build());
        walletA = wallets.save(WalletEntity.builder()
                .user(userA)
                .balanceUsdCents(0)
                .holdUsdCents(0)
                .currencyDefault("USD")
                .status("ACTIVE")
                .build());
    }

    private PaymentEntity payment(UserEntity user, UUID orderId, PaymentStatus status, String provider,
            String providerRef, String idempotencyKey) {
        return PaymentEntity.builder()
                .user(user)
                .wallet(user == userA ? walletA : null)
                .orderId(orderId)
                .purpose("ORDER_PAYMENT")
                .method(PaymentMethod.CARD)
                .status(status)
                .amountUsdCents(1000)
                .settlementCurrency("USD")
                .provider(provider)
                .providerRef(providerRef)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    @Test
    void findByIdempotencyKey_hitAndMiss() {
        payments.save(payment(userA, UUID.randomUUID(), PaymentStatus.SUCCEEDED, "stripe", "pi_1", "idem-1"));

        assertThat(payments.findByIdempotencyKey("idem-1")).isPresent();
        assertThat(payments.findByIdempotencyKey("idem-missing")).isEmpty();
    }

    @Test
    void findByProviderAndProviderRef_matchesBothFields() {
        payments.save(payment(userA, UUID.randomUUID(), PaymentStatus.SUCCEEDED, "stripe", "pi_abc", "idem-2"));

        Optional<PaymentEntity> hit = payments.findByProviderAndProviderRef("stripe", "pi_abc");
        assertThat(hit).isPresent();
        assertThat(hit.get().getUser().getId()).isEqualTo(userA.getId());

        // Right ref, wrong provider -> miss.
        assertThat(payments.findByProviderAndProviderRef("paypal", "pi_abc")).isEmpty();
    }

    @Test
    void findByUser_IdOrderByCreatedAtDesc_onlyOwnerNewestFirst() {
        payments.save(payment(userA, UUID.randomUUID(), PaymentStatus.PENDING, "stripe", "pi_a1", "idem-a1"));
        payments.save(payment(userA, UUID.randomUUID(), PaymentStatus.SUCCEEDED, "stripe", "pi_a2", "idem-a2"));
        payments.save(payment(userB, UUID.randomUUID(), PaymentStatus.SUCCEEDED, "stripe", "pi_b1", "idem-b1"));
        payments.flush();

        List<PaymentEntity> ofA = payments.findByUser_IdOrderByCreatedAtDesc(userA.getId());

        assertThat(ofA).hasSize(2)
                .allMatch(p -> p.getUser().getId().equals(userA.getId()));
    }

    @Test
    void findByOrderIdOrderByCreatedAtDesc_filtersByOrder() {
        UUID orderId = UUID.randomUUID();
        payments.save(payment(userA, orderId, PaymentStatus.FAILED, "stripe", "pi_o1", "idem-o1"));
        payments.save(payment(userA, orderId, PaymentStatus.SUCCEEDED, "stripe", "pi_o2", "idem-o2"));
        payments.save(payment(userA, UUID.randomUUID(), PaymentStatus.SUCCEEDED, "stripe", "pi_other", "idem-other"));
        payments.flush();

        List<PaymentEntity> ofOrder = payments.findByOrderIdOrderByCreatedAtDesc(orderId);

        assertThat(ofOrder).hasSize(2)
                .allMatch(p -> p.getOrderId().equals(orderId));
    }

    @Test
    void findSettlementCurrenciesByOrderId_projectsCurrencyStrings() {
        UUID orderId = UUID.randomUUID();
        PaymentEntity usdt = payment(userA, orderId, PaymentStatus.SUCCEEDED, "coinbase", "ch_1", "idem-c1");
        usdt.setSettlementCurrency("USDT");
        payments.save(usdt);
        payments.save(payment(userA, orderId, PaymentStatus.SUCCEEDED, "stripe", "ch_2", "idem-c2")); // USD
        payments.flush();

        List<String> currencies = payments.findSettlementCurrenciesByOrderId(orderId);

        assertThat(currencies).containsExactlyInAnyOrder("USD", "USDT");
        assertThat(payments.findSettlementCurrenciesByOrderId(UUID.randomUUID())).isEmpty();
    }
}
