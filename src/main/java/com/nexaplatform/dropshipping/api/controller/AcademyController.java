package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AcademyApi;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AcademyController implements AcademyApi {

    private final AcademyCourseRepository courseRepo;
    private final AcademyEnrollmentRepository enrolRepo;
    private final MentorProfileRepository mentorRepo;
    private final MentorBookingRepository bookingRepo;
    private final AffiliateRepository affRepo;
    private final UserRepository userRepo;

    public record CourseView(UUID id, String slug, String title, String description, String instructor,
            Integer durationMinutes, String coverUrl, String videoUrl, String locale,
            String level, Instant createdAt) {
    }

    public record EnrollmentView(UUID id, UUID courseId, String courseSlug, String courseTitle,
            BigDecimal progressPct, Instant completedAt) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<CourseView> courses(String locale,
            String level) {
        return courseRepo.findByPublishedTrueOrderByCreatedAtDesc().stream()
                .filter(c -> locale == null || locale.equalsIgnoreCase(c.getLocale()))
                .filter(c -> level == null || level.equalsIgnoreCase(c.getLevel()))
                .map(this::toCourse).toList();
    }

    @Override
    public CourseView course(String slug) {
        return toCourse(courseRepo.findBySlug(slug).orElseThrow(() -> new NotFoundException("Course")));
    }

    @Override
    @Transactional
    public EnrollmentView enroll(Authentication auth, UUID courseId) {
        UUID userId = UUID.fromString(auth.getName());
        AcademyCourseEntity c = courseRepo.findById(courseId).orElseThrow(() -> new NotFoundException("Course"));
        UserEntity u = userRepo.findById(userId).orElseThrow();
        AcademyEnrollmentEntity e = enrolRepo.findByUser_IdAndCourse_Id(userId, courseId)
                .orElseGet(() -> enrolRepo.save(AcademyEnrollmentEntity.builder().user(u).course(c).build()));
        return toEnrol(e);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EnrollmentView> myEnrollments(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return enrolRepo.findByUser_Id(userId).stream().map(this::toEnrol).toList();
    }

    @Override
    @Transactional
    public EnrollmentView updateProgress(UUID id, Map<String, Number> body) {
        AcademyEnrollmentEntity e = enrolRepo.findById(id).orElseThrow(() -> new NotFoundException("Enrollment"));
        Number pct = body.get("progressPct");
        if (pct != null) {
            BigDecimal p = new BigDecimal(pct.toString()).min(new BigDecimal("100"));
            e.setProgressPct(p);
            if (p.compareTo(new BigDecimal("100")) >= 0 && e.getCompletedAt() == null) {
                e.setCompletedAt(Instant.now());
            }
        }
        return toEnrol(enrolRepo.save(e));
    }

    /* ====================== MENTORS ====================== */

    public record MentorView(UUID id, UUID userId, String displayName, String headline, String bio,
            List<String> expertise, List<String> languages,
            int hourlyRateUsdCents, String timezone, boolean active) {
    }

    public record BookingRequest(UUID mentorId, Instant startsAt, Integer durationMin, String topic) {
    }

    public record BookingView(UUID id, UUID mentorId, String mentorName, Instant startsAt,
            int durationMin, String status, String topic) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<MentorView> mentors() {
        // DROP-575: filtrar mentores "fake" sembrados desde las cuentas de
        // sistema (NX036 Admin, NX036 Operator, NX036 Customer, NX036 Partner
        // y empresas partner como "Demo Partner — Sandbox"). No son mentores
        // reales — son seeds que se colaron al popular la tabla. Los
        // ocultamos del listado público hasta que el equipo decida si los
        // borra del dataset o crea mentores reales.
        return mentorRepo.findByActiveTrueOrderByCreatedAtDesc().stream()
                .filter(m -> {
                    String name = (m.getUser() != null && m.getUser().getDisplayName() != null)
                            ? m.getUser().getDisplayName()
                            : "";
                    String email = (m.getUser() != null && m.getUser().getEmail() != null)
                            ? m.getUser().getEmail().toLowerCase()
                            : "";
                    if (name.startsWith("NX036 "))
                        return false;
                    if (email.startsWith("admin@") || email.startsWith("operator@")
                            || email.startsWith("customer@") || email.startsWith("partner@"))
                        return false;
                    if (email.endsWith("@partners.nx036.local"))
                        return false;
                    return true;
                })
                .map(this::toMentor).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MentorView mentorDetail(UUID id) {
        return toMentor(mentorRepo.findById(id).orElseThrow(() -> new NotFoundException("Mentor")));
    }

    @Override
    @Transactional
    public BookingView book(Authentication auth, BookingRequest req) {
        UUID userId = UUID.fromString(auth.getName());
        UserEntity learner = userRepo.findById(userId).orElseThrow();
        MentorProfileEntity mentor = mentorRepo.findById(req.mentorId())
                .orElseThrow(() -> new NotFoundException("Mentor"));
        MentorBookingEntity b = MentorBookingEntity.builder()
                .mentor(mentor).learner(learner)
                .startsAt(req.startsAt()).durationMin(req.durationMin() == null ? 60 : req.durationMin())
                .topic(req.topic()).status("REQUESTED").build();
        return toBooking(bookingRepo.save(b));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingView> myBookings(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return bookingRepo.findByLearner_IdOrderByStartsAtDesc(userId).stream().map(this::toBooking).toList();
    }

    /* ====================== AFFILIATES ====================== */

    public record AffiliateView(UUID id, String code, long earningsUsdCents, long payoutUsdCents,
            int referralsCount, boolean active) {
    }

    @Override
    @Transactional
    public AffiliateView affiliate(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        UserEntity u = userRepo.findById(userId).orElseThrow();
        AffiliateEntity a = affRepo.findByUser_Id(userId).orElseGet(
                () -> affRepo.save(AffiliateEntity.builder().user(u).code(generateCode(u)).active(true).build()));
        return new AffiliateView(a.getId(), a.getCode(), a.getEarningsUsdCents(), a.getPayoutUsdCents(),
                a.getReferralsCount(), a.isActive());
    }

    /* ---------- helpers ---------- */

    private CourseView toCourse(AcademyCourseEntity c) {
        return new CourseView(c.getId(), c.getSlug(), c.getTitle(), c.getDescription(), c.getInstructor(),
                c.getDurationMinutes(), c.getCoverUrl(), c.getVideoUrl(), c.getLocale(), c.getLevel(),
                c.getCreatedAt());
    }

    private EnrollmentView toEnrol(AcademyEnrollmentEntity e) {
        return new EnrollmentView(e.getId(), e.getCourse().getId(), e.getCourse().getSlug(),
                e.getCourse().getTitle(), e.getProgressPct(), e.getCompletedAt());
    }

    private MentorView toMentor(MentorProfileEntity m) {
        UserEntity u = m.getUser();
        return new MentorView(m.getId(), u.getId(),
                u.getDisplayName() != null ? u.getDisplayName() : u.getEmail(),
                m.getHeadline(), m.getBio(), m.getExpertise(), m.getLanguages(),
                m.getHourlyRateUsdCents(), m.getTimezone(), m.isActive());
    }

    private BookingView toBooking(MentorBookingEntity b) {
        UserEntity mu = b.getMentor().getUser();
        return new BookingView(b.getId(), b.getMentor().getId(),
                mu.getDisplayName() != null ? mu.getDisplayName() : mu.getEmail(),
                b.getStartsAt(), b.getDurationMin(), b.getStatus(), b.getTopic());
    }

    private static String generateCode(UserEntity u) {
        String base = (u.getDisplayName() == null ? u.getEmail() : u.getDisplayName())
                .toLowerCase().replaceAll("[^a-z0-9]", "");
        if (base.length() > 8)
            base = base.substring(0, 8);
        return base + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
}
