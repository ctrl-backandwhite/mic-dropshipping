package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.in.AdminCategoryUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminCategoryDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminCategoryMapper;
import com.nexaplatform.dropshipping.application.service.AdminCategoryService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCategoryServiceTest {

    @Mock CategoryRepository categoryRepository;
    @Mock AdminCategoryMapper adminCategoryMapper;

    @InjectMocks AdminCategoryService service;

    @Test
    void toggle_flipsActiveAndDelegatesToMapper() {
        UUID id = UUID.randomUUID();
        var category = CategoryEntity.builder().active(false).build();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(category));
        when(adminCategoryMapper.toView(eq(category), anyLong()))
                .thenReturn(AdminCategoryDtoOut.builder().id(id).active(true).build());

        AdminCategoryDtoOut result = service.toggle(id);

        assertThat(category.isActive()).isTrue();
        assertThat(result.isActive()).isTrue();
        verify(categoryRepository).save(category);
    }

    @Test
    void update_throwsWhenSlugAlreadyTakenByAnotherCategory() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        var current = CategoryEntity.builder().slug("old").build();
        current.setId(id);
        var other = CategoryEntity.builder().slug("new").build();
        other.setId(otherId);
        when(categoryRepository.findById(id)).thenReturn(Optional.of(current));
        when(categoryRepository.findBySlug("new")).thenReturn(Optional.of(other));

        var dto = new AdminCategoryUpsertDtoIn("new", "新", null, null, null, null, null);

        assertThatThrownBy(() -> service.update(id, dto))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void toggle_throwsWhenCategoryMissing() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggle(id))
                .isInstanceOf(NotFoundException.class);
    }
}
