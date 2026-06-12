package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.BookingDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AffiliateDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.BookingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CourseDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.EnrollmentDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MentorDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Academy, Mentors and Affiliates
 * endpoints (DROP-10). The controller only implements these methods; all routing
 * and Swagger documentation live here (springdoc "API interface" pattern). The
 * transport types are the {@code api.dto.in}/{@code api.dto.out} DTO classes that
 * replaced the old inline {@code *View} records.
 */
@Tag(name = "Academy, Mentors, Affiliates")
public interface AcademyApi {

    @Operation(summary = "List published academy courses, optionally filtered by locale and level")
    @GetMapping("/storefront/academy/courses")
    List<CourseDtoOut> courses(@RequestParam(required = false) String locale,
            @RequestParam(required = false) String level);

    @Operation(summary = "Get a published academy course by slug")
    @GetMapping("/storefront/academy/courses/{slug}")
    CourseDtoOut course(@PathVariable String slug);

    @Operation(summary = "Enroll the current user in a course")
    @PostMapping("/me/academy/enroll/{courseId}")
    EnrollmentDtoOut enroll(Authentication auth, @PathVariable UUID courseId);

    @Operation(summary = "List the current user's course enrollments")
    @GetMapping("/me/academy/enrollments")
    List<EnrollmentDtoOut> myEnrollments(Authentication auth);

    @Operation(summary = "Update the progress of an enrollment")
    @PutMapping("/me/academy/enrollments/{id}/progress")
    EnrollmentDtoOut updateProgress(@PathVariable UUID id, @RequestBody Map<String, Number> body);

    @Operation(summary = "List active mentors")
    @GetMapping("/storefront/mentors")
    List<MentorDtoOut> mentors();

    @Operation(summary = "Get a mentor profile by id")
    @GetMapping("/storefront/mentors/{id}")
    MentorDtoOut mentorDetail(@PathVariable UUID id);

    @Operation(summary = "Book a session with a mentor")
    @PostMapping("/me/mentors/bookings")
    BookingDtoOut book(Authentication auth, @RequestBody BookingDtoIn req);

    @Operation(summary = "List the current user's mentor bookings")
    @GetMapping("/me/mentors/bookings")
    List<BookingDtoOut> myBookings(Authentication auth);

    // DROP-643..649: the affiliate endpoints moved to MeAffiliateController / AdminAffiliateController
    // (full referral program). The old /me/affiliate stub was removed to avoid an ambiguous mapping.
}
