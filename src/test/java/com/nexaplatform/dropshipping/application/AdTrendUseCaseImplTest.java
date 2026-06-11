package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.usecase.impl.AdTrendUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.AdTrend;
import com.nexaplatform.dropshipping.domain.repository.AdTrendRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdTrendUseCaseImplTest {

    @Mock AdTrendRepository adTrendRepository;
    @InjectMocks AdTrendUseCaseImpl useCase;

    @Test
    void findTrends_withoutSource_usesFullQueryAndCapsAtLimit() {
        List<AdTrend> rows = IntStream.range(0, 5)
                .mapToObj(i -> AdTrend.builder().id(UUID.randomUUID()).build())
                .toList();
        when(adTrendRepository.findAllByScoreDesc()).thenReturn(rows);

        List<AdTrend> result = useCase.findTrends("  ", 3);

        assertThat(result).hasSize(3);
        verify(adTrendRepository).findAllByScoreDesc();
        verifyNoMoreInteractions(adTrendRepository);
    }

    @Test
    void findTrends_withSource_lowercasesAndUsesSourceQuery() {
        when(adTrendRepository.findBySourceByScoreDesc("tiktok"))
                .thenReturn(List.of(AdTrend.builder().id(UUID.randomUUID()).build()));

        List<AdTrend> result = useCase.findTrends("TikTok", 30);

        assertThat(result).hasSize(1);
        verify(adTrendRepository).findBySourceByScoreDesc("tiktok");
    }

    @Test
    void findTrends_hardCapsAtOneHundred() {
        List<AdTrend> rows = IntStream.range(0, 150)
                .mapToObj(i -> AdTrend.builder().id(UUID.randomUUID()).build())
                .toList();
        when(adTrendRepository.findAllByScoreDesc()).thenReturn(rows);

        List<AdTrend> result = useCase.findTrends(null, 9999);

        assertThat(result).hasSize(100);
    }
}
