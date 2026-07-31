package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.WebhookSubscriptionUpdateMapper;
import com.nexaplatform.dropshipping.domain.model.WebhookSubscription;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La actualización parcial de un webhook NO debe sobreescribir el secreto de firma ni la identidad/
 * auditoría: solo nombre, URL, eventos, estado y descripción.
 */
class WebhookSubscriptionUpdateMapperTest {

    private final WebhookSubscriptionUpdateMapper mapper = Mappers.getMapper(WebhookSubscriptionUpdateMapper.class);

    @Test
    void updateFromModel_copiesEditableFieldsAndPreservesSecretAndIdentity() {
        UUID id = UUID.randomUUID();
        WebhookSubscription target = WebhookSubscription.builder().id(id).secret("whsec_keepme")
                .name("Old").targetUrl("https://old").events(new ArrayList<>(List.of("order.created"))).active(false)
                .description("old").createdBy("creator").build();

        WebhookSubscription source = WebhookSubscription.builder().secret("whsec_attacker")
                .name("New").targetUrl("https://new").events(List.of("order.shipped", "order.delivered"))
                .active(true).description("new").build();

        mapper.updateFromModel(source, target);

        assertThat(target.getName()).isEqualTo("New");
        assertThat(target.getTargetUrl()).isEqualTo("https://new");
        assertThat(target.getEvents()).contains("order.shipped", "order.delivered");
        assertThat(target.isActive()).isTrue();
        assertThat(target.getDescription()).isEqualTo("new");

        // Preservados
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getSecret()).isEqualTo("whsec_keepme");
        assertThat(target.getCreatedBy()).isEqualTo("creator");
    }
}
