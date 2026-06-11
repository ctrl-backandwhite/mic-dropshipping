package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.AcademyCourseUseCase;
import com.nexaplatform.dropshipping.domain.model.AcademyCourse;
import com.nexaplatform.dropshipping.domain.repository.AcademyCourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Academy-course use case. Operates on the {@link AcademyCourse} model and
 * delegates persistence to the domain port. Holds the storefront logic that used
 * to live in {@code AcademyController}: listing published courses with optional
 * locale/level filtering and slug lookup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcademyCourseUseCaseImpl implements AcademyCourseUseCase {

    private final AcademyCourseRepository academyCourseRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AcademyCourse> listPublished(String locale, String level) {
        return academyCourseRepository.findPublished().stream()
                .filter(c -> locale == null || locale.equalsIgnoreCase(c.getLocale()))
                .filter(c -> level == null || level.equalsIgnoreCase(c.getLevel()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AcademyCourse getBySlug(String slug) {
        return academyCourseRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Course"));
    }
}
