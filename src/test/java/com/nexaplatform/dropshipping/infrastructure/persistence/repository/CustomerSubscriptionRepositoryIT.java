package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence IT for {@link CustomerSubscriptionRepository}: drives the custom finders against a real
 * Postgres. Builds the NOT NULL FK graph (UserEntity + SubscriptionPlanEntity) before each test, as
 * {@code customer_subscription.user_id} / {@code plan_id} are {@code optional = false}.
 */
class CustomerSubscriptionRepositoryIT extends PersistenceITBase {

    @Autowired
    CustomerSubscriptionRepository subscriptions;

    @Autowired
    UserRepository users;

    @Autowired
    SubscriptionPlanJpaRepositoryAdapter plans;

    private UserEntity userA;
    private UserEntity userB;
    private SubscriptionPlanEntity plan;

    @BeforeEach
    void setUp() {
        userA = users.save(UserEntity.builder().email("a-" + UUID.randomUUID() + "@nx.local").role(UserRole.USER)
                .active(true).googleLinked(false).build());
        userB = users.save(UserEntity.builder().email("b-" + UUID.randomUUID() + "@nx.local").role(UserRole.USER)
                .active(true).googleLinked(false).build());
        plan = plans.save(SubscriptionPlanEntity.builder().code("pro-" + UUID.randomUUID()).name("Pro")
                .priceMonthlyCents(2900).priceYearlyCents(29000).currency("USD").active(true).position(1).build());
    }

    private CustomerSubscriptionEntity sub(UserEntity user, SubscriptionStatus status, String stripeId,
            Instant periodEnd) {
        return CustomerSubscriptionEntity.builder().user(user).plan(plan).status(status).stripeSubscriptionId(stripeId)
                .currentPeriodEnd(periodEnd).build();
    }

    @Test
    void findByUserId_returnsOnlyOwnerRows() {
        subscriptions.save(sub(userA, SubscriptionStatus.ACTIVE, "sub_a1", Instant.parse("2026-02-01T00:00:00Z")));
        subscriptions.save(sub(userA, SubscriptionStatus.CANCELED, "sub_a2", Instant.parse("2026-01-01T00:00:00Z")));
        subscriptions.save(sub(userB, SubscriptionStatus.ACTIVE, "sub_b1", Instant.parse("2026-03-01T00:00:00Z")));

        List<CustomerSubscriptionEntity> ofA = subscriptions.findByUserId(userA.getId());

        assertThat(ofA).hasSize(2).allMatch(s -> s.getUser().getId().equals(userA.getId()));
    }

    @Test
    void findByStripeSubscriptionId_hitAndMiss() {
        subscriptions
                .save(sub(userA, SubscriptionStatus.ACTIVE, "sub_stripe_xyz", Instant.parse("2026-02-01T00:00:00Z")));

        Optional<CustomerSubscriptionEntity> hit = subscriptions.findByStripeSubscriptionId("sub_stripe_xyz");
        Optional<CustomerSubscriptionEntity> miss = subscriptions.findByStripeSubscriptionId("sub_nope");

        assertThat(hit).isPresent();
        assertThat(hit.get().getUser().getId()).isEqualTo(userA.getId());
        assertThat(miss).isEmpty();
    }

    @Test
    void findActiveByUserId_filtersByStatusAndOrdersByPeriodEndDesc() {
        subscriptions.save(sub(userA, SubscriptionStatus.ACTIVE, "sub_active", Instant.parse("2026-02-01T00:00:00Z")));
        subscriptions.save(sub(userA, SubscriptionStatus.TRIALING, "sub_trial", Instant.parse("2026-05-01T00:00:00Z")));
        subscriptions
                .save(sub(userA, SubscriptionStatus.CANCELED, "sub_canceled", Instant.parse("2026-09-01T00:00:00Z")));
        subscriptions
                .save(sub(userA, SubscriptionStatus.PAST_DUE, "sub_pastdue", Instant.parse("2026-12-01T00:00:00Z")));

        List<CustomerSubscriptionEntity> active = subscriptions.findActiveByUserId(userA.getId());

        // Only ACTIVE + TRIALING, newest currentPeriodEnd first.
        assertThat(active).extracting(CustomerSubscriptionEntity::getStripeSubscriptionId).containsExactly("sub_trial",
                "sub_active");
    }
}
