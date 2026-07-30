package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AcademyApi;
import com.nexaplatform.dropshipping.api.dto.in.BookingDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BookingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CourseDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.EnrollmentDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MentorDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AcademyCourseDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.AcademyEnrollmentDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.AffiliateDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.MentorBookingDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.MentorProfileDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.AcademyCourseUseCase;
import com.nexaplatform.dropshipping.application.usecase.AcademyEnrollmentUseCase;
import com.nexaplatform.dropshipping.application.usecase.AffiliateUseCase;
import com.nexaplatform.dropshipping.application.usecase.MentorBookingUseCase;
import com.nexaplatform.dropshipping.application.usecase.MentorProfileUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Academy, Mentors and Affiliates controller. Pure implementation of
 * {@link AcademyApi}: no routing/documentation annotations here (they live on the
 * interface), no business logic — injects the per-aggregate DtoMappers + use
 * cases and, for each endpoint, maps DtoIn -> domain -> use case -> DtoOut. The
 * me/* endpoints resolve the authenticated user id from the {@link Authentication}.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AcademyController implements AcademyApi {

    private final AcademyCourseDtoMapper courseMapper;
    private final AcademyCourseUseCase courseUseCase;
    private final AcademyEnrollmentDtoMapper enrollmentMapper;
    private final AcademyEnrollmentUseCase enrollmentUseCase;
    private final MentorProfileDtoMapper mentorMapper;
    private final MentorProfileUseCase mentorUseCase;
    private final MentorBookingDtoMapper bookingMapper;
    private final MentorBookingUseCase bookingUseCase;
    private final AffiliateDtoMapper affiliateMapper;
    private final AffiliateUseCase affiliateUseCase;

    @Override
    public List<CourseDtoOut> courses(String locale, String level) {
        return courseMapper.toDtoOutList(courseUseCase.listPublished(locale, level));
    }

    @Override
    public CourseDtoOut course(String slug) {
        return courseMapper.toDtoOut(courseUseCase.getBySlug(slug));
    }

    @Override
    public EnrollmentDtoOut enroll(Authentication auth, UUID courseId) {
        UUID userId = UUID.fromString(auth.getName());
        return enrollmentMapper.toDtoOut(enrollmentUseCase.enroll(userId, courseId));
    }

    @Override
    public List<EnrollmentDtoOut> myEnrollments(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return enrollmentMapper.toDtoOutList(enrollmentUseCase.findByUser(userId));
    }

    @Override
    public EnrollmentDtoOut updateProgress(UUID id, Map<String, Number> body) {
        return enrollmentMapper.toDtoOut(enrollmentUseCase.updateProgress(id, body));
    }

    @Override
    public List<MentorDtoOut> mentors() {
        return mentorMapper.toDtoOutList(mentorUseCase.listActive());
    }

    @Override
    public MentorDtoOut mentorDetail(UUID id) {
        return mentorMapper.toDtoOut(mentorUseCase.getById(id));
    }

    @Override
    public BookingDtoOut book(Authentication auth, BookingDtoIn req) {
        UUID userId = UUID.fromString(auth.getName());
        return bookingMapper.toDtoOut(bookingUseCase.book(userId, bookingMapper.toDomain(req)));
    }

    @Override
    public List<BookingDtoOut> myBookings(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return bookingMapper.toDtoOutList(bookingUseCase.findByLearner(userId));
    }

}
