package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.PodDesignUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.PodAiResult;
import com.nexaplatform.dropshipping.domain.model.PodDesign;
import com.nexaplatform.dropshipping.domain.repository.PodDesignRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PodDesignUseCaseImplTest {

    @Mock PodDesignRepository podDesignRepository;
    @Mock ProductRepository productRepository;
    @InjectMocks PodDesignUseCaseImpl useCase;

    @Test
    void create_assignsOwnerAndRendersMockup() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        PodDesign incoming = PodDesign.builder().productId(productId).name("d").build();
        when(productRepository.existsById(productId)).thenReturn(true);
        when(podDesignRepository.save(incoming)).thenReturn(incoming.withId(UUID.randomUUID()));

        PodDesign saved = useCase.create(userId, incoming);

        ArgumentCaptor<PodDesign> captor = ArgumentCaptor.forClass(PodDesign.class);
        verify(podDesignRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getStatus()).isEqualTo("RENDERED");
        assertThat(captor.getValue().getMockupUrl()).isNotNull();
        assertThat(captor.getValue().getCanvasJson()).isNotNull();
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void create_throwsWhenProductMissing() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        PodDesign incoming = PodDesign.builder().productId(productId).name("d").build();
        when(productRepository.existsById(productId)).thenReturn(false);

        assertThatThrownBy(() -> useCase.create(userId, incoming)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aiGenerate_returnsMockProviderResult() {
        PodAiResult result = useCase.aiGenerate("a cat");

        assertThat(result.getProvider()).isEqualTo("mock");
        assertThat(result.getPrompt()).isEqualTo("a cat");
        assertThat(result.getMockupUrl()).isNotNull();
    }
}
