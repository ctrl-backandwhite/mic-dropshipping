package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.AcademyCourse;

import java.util.List;
import java.util.UUID;

/** Use-case port for academy courses; operates on the {@link AcademyCourse} domain model. */
public interface AcademyCourseUseCase extends BaseUseCase<AcademyCourse, AcademyCourse, UUID> {

    /** Lists published courses, optionally filtered by locale and/or level. */
    List<AcademyCourse> listPublished(String locale, String level);

    /** Returns the published course identified by its slug, or throws if missing. */
    AcademyCourse getBySlug(String slug);
}
