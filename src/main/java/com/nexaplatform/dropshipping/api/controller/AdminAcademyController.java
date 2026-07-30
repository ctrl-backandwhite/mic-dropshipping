package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AcademyCourseEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AcademyCourseJpaRepositoryAdapter;
import com.github.slugify.Slugify;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DROP-691: gestión admin de cursos de Academia (CRUD). El storefront solo leía cursos publicados;
 * esto permite al operador crear/editar/eliminar cursos desde el panel.
 */
@RestController
@RequestMapping("/api/admin/academy/courses")
@RequiredArgsConstructor
public class AdminAcademyController {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String DESCRIPTION = "description";
    private static final String INSTRUCTOR = "instructor";
    private static final String PUBLISHED = "published";
    private static final String VIDEOURL = "videoUrl";
    private static final String COVERURL = "coverUrl";
    private static final String LOCALE = "locale";
    private static final String LEVEL = "level";
    private static final String TITLE = "title";

    private static final Slugify SLUG = Slugify.builder().lowerCase(true).build();

    private final AcademyCourseJpaRepositoryAdapter repository;

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return repository.findAll().stream().map(this::toMap).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        AcademyCourseEntity e = new AcademyCourseEntity();
        apply(e, body, true);
        return ResponseEntity.ok(toMap(repository.save(e)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        AcademyCourseEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("Course not found"));
        apply(e, body, false);
        return ResponseEntity.ok(toMap(repository.save(e)));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void apply(AcademyCourseEntity e, Map<String, Object> b, boolean isNew) {
        if (b.get(TITLE) != null) {
            e.setTitle(b.get(TITLE).toString());
        }
        String slug = b.get("slug") != null && !b.get("slug").toString().isBlank() ? b.get("slug").toString()
                : (isNew && e.getTitle() != null ? SLUG.slugify(e.getTitle()) : e.getSlug());
        if (slug != null) {
            e.setSlug(slug);
        }
        if (b.containsKey(DESCRIPTION)) {
            e.setDescription(str(b.get(DESCRIPTION)));
        }
        if (b.containsKey(INSTRUCTOR)) {
            e.setInstructor(str(b.get(INSTRUCTOR)));
        }
        if (b.get("durationMinutes") instanceof Number n) {
            e.setDurationMinutes(n.intValue());
        }
        if (b.containsKey(COVERURL)) {
            e.setCoverUrl(str(b.get(COVERURL)));
        }
        if (b.containsKey(VIDEOURL)) {
            e.setVideoUrl(str(b.get(VIDEOURL)));
        }
        if (b.get(LOCALE) != null) {
            e.setLocale(b.get(LOCALE).toString());
        } else if (isNew && e.getLocale() == null) {
            e.setLocale("es");
        }
        if (b.get(LEVEL) != null) {
            e.setLevel(b.get(LEVEL).toString());
        } else if (isNew && e.getLevel() == null) {
            e.setLevel("BEGINNER");
        }
        if (b.get(PUBLISHED) != null) {
            e.setPublished(Boolean.parseBoolean(b.get(PUBLISHED).toString()));
        }
    }

    private String str(Object o) {
        return o != null && !o.toString().isBlank() ? o.toString() : null;
    }

    private Map<String, Object> toMap(AcademyCourseEntity e) {
        return Map.ofEntries(Map.entry("id", e.getId()), Map.entry("slug", e.getSlug() != null ? e.getSlug() : ""),
                Map.entry(TITLE, e.getTitle() != null ? e.getTitle() : ""),
                Map.entry(DESCRIPTION, e.getDescription() != null ? e.getDescription() : ""),
                Map.entry(INSTRUCTOR, e.getInstructor() != null ? e.getInstructor() : ""),
                Map.entry("durationMinutes", e.getDurationMinutes() != null ? e.getDurationMinutes() : 0),
                Map.entry(COVERURL, e.getCoverUrl() != null ? e.getCoverUrl() : ""),
                Map.entry(VIDEOURL, e.getVideoUrl() != null ? e.getVideoUrl() : ""),
                Map.entry(LOCALE, e.getLocale() != null ? e.getLocale() : "es"),
                Map.entry(LEVEL, e.getLevel() != null ? e.getLevel() : "BEGINNER"),
                Map.entry(PUBLISHED, e.isPublished()));
    }
}
