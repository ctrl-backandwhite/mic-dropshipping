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
        if (b.get("title") != null) {
            e.setTitle(b.get("title").toString());
        }
        String slug = b.get("slug") != null && !b.get("slug").toString().isBlank() ? b.get("slug").toString()
                : (isNew && e.getTitle() != null ? SLUG.slugify(e.getTitle()) : e.getSlug());
        if (slug != null) {
            e.setSlug(slug);
        }
        if (b.containsKey("description")) {
            e.setDescription(str(b.get("description")));
        }
        if (b.containsKey("instructor")) {
            e.setInstructor(str(b.get("instructor")));
        }
        if (b.get("durationMinutes") instanceof Number n) {
            e.setDurationMinutes(n.intValue());
        }
        if (b.containsKey("coverUrl")) {
            e.setCoverUrl(str(b.get("coverUrl")));
        }
        if (b.containsKey("videoUrl")) {
            e.setVideoUrl(str(b.get("videoUrl")));
        }
        if (b.get("locale") != null) {
            e.setLocale(b.get("locale").toString());
        } else if (isNew && e.getLocale() == null) {
            e.setLocale("es");
        }
        if (b.get("level") != null) {
            e.setLevel(b.get("level").toString());
        } else if (isNew && e.getLevel() == null) {
            e.setLevel("BEGINNER");
        }
        if (b.get("published") != null) {
            e.setPublished(Boolean.parseBoolean(b.get("published").toString()));
        }
    }

    private String str(Object o) {
        return o != null && !o.toString().isBlank() ? o.toString() : null;
    }

    private Map<String, Object> toMap(AcademyCourseEntity e) {
        return Map.ofEntries(Map.entry("id", e.getId()), Map.entry("slug", e.getSlug() != null ? e.getSlug() : ""),
                Map.entry("title", e.getTitle() != null ? e.getTitle() : ""),
                Map.entry("description", e.getDescription() != null ? e.getDescription() : ""),
                Map.entry("instructor", e.getInstructor() != null ? e.getInstructor() : ""),
                Map.entry("durationMinutes", e.getDurationMinutes() != null ? e.getDurationMinutes() : 0),
                Map.entry("coverUrl", e.getCoverUrl() != null ? e.getCoverUrl() : ""),
                Map.entry("videoUrl", e.getVideoUrl() != null ? e.getVideoUrl() : ""),
                Map.entry("locale", e.getLocale() != null ? e.getLocale() : "es"),
                Map.entry("level", e.getLevel() != null ? e.getLevel() : "BEGINNER"),
                Map.entry("published", e.isPublished()));
    }
}
