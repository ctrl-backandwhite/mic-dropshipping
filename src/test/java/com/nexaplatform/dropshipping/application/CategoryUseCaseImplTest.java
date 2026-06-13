package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.CategoryUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.impl.CategoryUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.Category;
import com.nexaplatform.dropshipping.domain.repository.CategoryRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryUseCaseImplTest {

    @Mock
    CategoryRepository categoryRepository;
    @Mock
    CategoryUpdateMapper categoryUpdateMapper;
    @Mock
    EntityManager em;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer categoryIndexer;
    @InjectMocks
    CategoryUseCaseImpl useCase;

    @Test
    void toggle_flipsActiveAndPersists() {
        UUID id = UUID.randomUUID();
        Category existing = Category.builder().id(id).active(false).build();
        when(categoryRepository.getById(id)).thenReturn(existing);
        when(categoryRepository.update(existing)).thenReturn(existing);

        Category result = useCase.toggle(id);

        assertThat(existing.getActive()).isTrue();
        assertThat(result.getProductCount()).isZero();
        verify(categoryRepository).update(existing);
    }

    @Test
    void getById_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.getById(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void update_throwsWhenSlugAlreadyTakenByAnotherCategory() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Category existing = Category.builder().id(id).slug("old").build();
        Category other = Category.builder().id(otherId).slug("new").build();
        Category incoming = Category.builder().slug("new").nameZh("新").build();
        when(categoryRepository.getById(id)).thenReturn(existing);
        when(categoryRepository.findBySlug("new")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> useCase.update(incoming, id)).isInstanceOf(BusinessException.class);
    }
}
