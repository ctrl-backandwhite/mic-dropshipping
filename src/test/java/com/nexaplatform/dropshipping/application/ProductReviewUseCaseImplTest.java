package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.ProductReviewUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.ProductReview;
import com.nexaplatform.dropshipping.domain.model.ProductReviewPage;
import com.nexaplatform.dropshipping.domain.repository.ProductReviewRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductReviewUseCaseImplTest {

    @Mock
    ProductReviewRepository productReviewRepository;
    @Mock
    ProductRepository productRepo;
    @InjectMocks
    ProductReviewUseCaseImpl useCase;

    @Test
    void list_throwsWhenProductMissing() {
        UUID productId = UUID.randomUUID();
        when(productRepo.existsById(productId)).thenReturn(false);

        assertThatThrownBy(() -> useCase.list(productId, 0, 10, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void list_buildsHistogramAndAverageWithoutFilter() {
        UUID productId = UUID.randomUUID();
        ProductReview r = ProductReview.builder().id(UUID.randomUUID()).rating((short) 5).build();
        when(productRepo.existsById(productId)).thenReturn(true);
        when(productReviewRepository.findApprovedByProduct(eq(productId), any()))
                .thenReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 10), 1));
        // 3 reviews: two 5-star, one 2-star -> avg = (5*2 + 2*1) / 3 = 4.0
        when(productReviewRepository.ratingDistribution(productId))
                .thenReturn(new java.util.HashMap<>(Map.of(5, 2L, 2, 1L)));

        ProductReviewPage result = useCase.list(productId, 0, 10, null);

        assertThat(result.getItems()).containsExactly(r);
        assertThat(result.getPage()).isZero();
        assertThat(result.getSize()).isEqualTo(10);
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getDistribution()).containsAllEntriesOf(Map.of(1, 0L, 2, 1L, 3, 0L, 4, 0L, 5, 2L));
        assertThat(result.getAverageRating()).isEqualTo(4.0);
    }

    @Test
    void list_appliesMinRatingFilterWhenProvided() {
        UUID productId = UUID.randomUUID();
        when(productRepo.existsById(productId)).thenReturn(true);
        when(productReviewRepository.findApprovedByProductAndMinRating(eq(productId), eq((short) 4), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        when(productReviewRepository.ratingDistribution(productId)).thenReturn(new java.util.HashMap<>());

        ProductReviewPage result = useCase.list(productId, 0, 10, (short) 4);

        assertThat(result.getAverageRating()).isZero();
        verify(productReviewRepository).findApprovedByProductAndMinRating(eq(productId), eq((short) 4), any());
    }
}
