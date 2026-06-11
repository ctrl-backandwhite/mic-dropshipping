package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.AcademyCourseUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.AcademyCourse;
import com.nexaplatform.dropshipping.domain.repository.AcademyCourseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcademyCourseUseCaseImplTest {

    @Mock AcademyCourseRepository academyCourseRepository;
    @InjectMocks AcademyCourseUseCaseImpl useCase;

    @Test
    void listPublished_filtersByLocaleAndLevel() {
        AcademyCourse es = AcademyCourse.builder().locale("es").level("BEGINNER").build();
        AcademyCourse en = AcademyCourse.builder().locale("en").level("ADVANCED").build();
        when(academyCourseRepository.findPublished()).thenReturn(List.of(es, en));

        List<AcademyCourse> result = useCase.listPublished("ES", "beginner");

        assertThat(result).containsExactly(es);
    }

    @Test
    void getBySlug_throwsWhenMissing() {
        when(academyCourseRepository.findBySlug("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getBySlug("missing")).isInstanceOf(NotFoundException.class);
    }
}
