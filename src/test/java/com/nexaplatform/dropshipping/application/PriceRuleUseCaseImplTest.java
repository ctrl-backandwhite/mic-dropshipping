package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.PriceRuleUpdateMapper;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.usecase.impl.PriceRuleUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.model.PriceRule;
import com.nexaplatform.dropshipping.domain.repository.PriceRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceRuleUseCaseImplTest {

    @Mock
    PriceRuleRepository priceRuleRepository;
    @Mock
    PriceRuleUpdateMapper priceRuleUpdateMapper;
    @Mock
    MarginService marginService;
    @InjectMocks
    PriceRuleUseCaseImpl useCase;

    @Test
    void save_persistsAndFlushesMarginCache() {
        PriceRule model = PriceRule.builder().marginType(MarginType.PERCENTAGE).build();
        when(priceRuleRepository.save(model)).thenReturn(model.withId(UUID.randomUUID()));

        PriceRule saved = useCase.save(model);

        assertThat(saved.getId()).isNotNull();
        verify(priceRuleRepository).save(model);
        verify(marginService).invalidateCache();
    }

    @Test
    void getById_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(priceRuleRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.getById(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void update_appliesPartialUpdatePersistsAndFlushes() {
        UUID id = UUID.randomUUID();
        PriceRule existing = PriceRule.builder().id(id).position(1).build();
        PriceRule incoming = PriceRule.builder().position(2).build();
        when(priceRuleRepository.getById(id)).thenReturn(existing);
        when(priceRuleRepository.update(existing)).thenReturn(existing);

        useCase.update(incoming, id);

        verify(priceRuleUpdateMapper).updateFromModel(incoming, existing);
        verify(priceRuleRepository).update(existing);
        verify(marginService).invalidateCache();
    }
}
