package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.PricingChannelHolder;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PricingChannelHolderTest {

    @AfterEach
    void cleanThreadLocal() {
        PricingChannelHolder.clear();
    }

    @Test
    void get_defaultsToStorefront() {
        assertThat(PricingChannelHolder.get()).isEqualTo(PriceRuleChannel.STOREFRONT);
    }

    @Test
    void set_changesCurrentChannel() {
        PricingChannelHolder.set(PriceRuleChannel.INTEGRATION);
        assertThat(PricingChannelHolder.get()).isEqualTo(PriceRuleChannel.INTEGRATION);
    }

    @Test
    void setNull_fallsBackToStorefront() {
        PricingChannelHolder.set(PriceRuleChannel.INTEGRATION);
        PricingChannelHolder.set(null);
        assertThat(PricingChannelHolder.get()).isEqualTo(PriceRuleChannel.STOREFRONT);
    }

    @Test
    void clear_restoresDefaultAfterMutation() {
        PricingChannelHolder.set(PriceRuleChannel.INTEGRATION);
        PricingChannelHolder.clear();
        assertThat(PricingChannelHolder.get()).isEqualTo(PriceRuleChannel.STOREFRONT);
    }
}
