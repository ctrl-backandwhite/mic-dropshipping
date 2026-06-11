package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.controller.AcademyController.AffiliateView;
import com.nexaplatform.dropshipping.api.controller.AcademyController.BookingRequest;
import com.nexaplatform.dropshipping.api.controller.AcademyController.BookingView;
import com.nexaplatform.dropshipping.api.controller.AcademyController.CourseView;
import com.nexaplatform.dropshipping.api.controller.AcademyController.EnrollmentView;
import com.nexaplatform.dropshipping.api.controller.AcademyController.MentorView;
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
 * and Swagger documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Academy, Mentors, Affiliates")
public interface AcademyApi {

    @Operation(summary = "List published academy courses, optionally filtered by locale and level")
    @GetMapping("/storefront/academy/courses")
    List<CourseView> courses(@RequestParam(required = false) String locale,
                             @RequestParam(required = false) String level);

    @Operation(summary = "Get a published academy course by slug")
    @GetMapping("/storefront/academy/courses/{slug}")
    CourseView course(@PathVariable String slug);

    @Operation(summary = "Enroll the current user in a course")
    @PostMapping("/me/academy/enroll/{courseId}")
    EnrollmentView enroll(Authentication auth, @PathVariable UUID courseId);

    @Operation(summary = "List the current user's course enrollments")
    @GetMapping("/me/academy/enrollments")
    List<EnrollmentView> myEnrollments(Authentication auth);

    @Operation(summary = "Update the progress of an enrollment")
    @PutMapping("/me/academy/enrollments/{id}/progress")
    EnrollmentView updateProgress(@PathVariable UUID id, @RequestBody Map<String, Number> body);

    @Operation(summary = "List active mentors")
    @GetMapping("/storefront/mentors")
    List<MentorView> mentors();

    @Operation(summary = "Get a mentor profile by id")
    @GetMapping("/storefront/mentors/{id}")
    MentorView mentorDetail(@PathVariable UUID id);

    @Operation(summary = "Book a session with a mentor")
    @PostMapping("/me/mentors/bookings")
    BookingView book(Authentication auth, @RequestBody BookingRequest req);

    @Operation(summary = "List the current user's mentor bookings")
    @GetMapping("/me/mentors/bookings")
    List<BookingView> myBookings(Authentication auth);

    @Operation(summary = "Get or create the current user's affiliate account")
    @GetMapping("/me/affiliate")
    AffiliateView affiliate(Authentication auth);
}
