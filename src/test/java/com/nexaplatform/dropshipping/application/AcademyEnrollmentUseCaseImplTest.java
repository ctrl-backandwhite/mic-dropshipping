package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.AcademyEnrollmentUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.AcademyEnrollment;
import com.nexaplatform.dropshipping.domain.repository.AcademyCourseRepository;
import com.nexaplatform.dropshipping.domain.repository.AcademyEnrollmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcademyEnrollmentUseCaseImplTest {

    @Mock
    AcademyEnrollmentRepository academyEnrollmentRepository;
    @Mock
    AcademyCourseRepository academyCourseRepository;
    @InjectMocks
    AcademyEnrollmentUseCaseImpl useCase;

    @Test
    void enroll_returnsExistingEnrollmentWhenAlreadyEnrolled() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        AcademyEnrollment existing = AcademyEnrollment.builder().id(UUID.randomUUID()).build();
        when(academyCourseRepository.existsById(courseId)).thenReturn(true);
        when(academyEnrollmentRepository.findByUserIdAndCourseId(userId, courseId)).thenReturn(Optional.of(existing));

        AcademyEnrollment result = useCase.enroll(userId, courseId);

        assertThat(result).isSameAs(existing);
        verify(academyEnrollmentRepository, never()).save(any());
    }

    @Test
    void enroll_throwsWhenCourseMissing() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        when(academyCourseRepository.existsById(courseId)).thenReturn(false);

        assertThatThrownBy(() -> useCase.enroll(userId, courseId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateProgress_clampsToHundredAndCompletes() {
        UUID id = UUID.randomUUID();
        AcademyEnrollment existing = AcademyEnrollment.builder().id(id).build();
        when(academyEnrollmentRepository.getById(id)).thenReturn(existing);
        when(academyEnrollmentRepository.update(existing)).thenReturn(existing);

        useCase.updateProgress(id, Map.of("progressPct", 150));

        assertThat(existing.getProgressPct()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(existing.getCompletedAt()).isNotNull();
        verify(academyEnrollmentRepository).update(existing);
    }

    @Test
    void updateProgress_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(academyEnrollmentRepository.getById(id)).thenReturn(null);

        Map<String, Number> progreso = Map.of("progressPct", 10);
        assertThatThrownBy(() -> useCase.updateProgress(id, progreso))
                .isInstanceOf(NotFoundException.class);
    }
}
