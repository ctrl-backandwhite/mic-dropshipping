package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorProfileEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.MentorProfileJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * DROP-692: gestión admin (CRUD) de mentores. Un mentor está asociado a un usuario (FK); en el alta se
 * indica el email del usuario y el resto del perfil. El storefront solo leía mentores activos.
 */
@RestController
@RequestMapping("/api/admin/mentors")
@RequiredArgsConstructor
public class AdminMentorsController {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String USEREMAIL = "userEmail";
    private static final String TIMEZONE = "timezone";
    private static final String HEADLINE = "headline";
    private static final String ACTIVE = "active";

    private final MentorProfileJpaRepositoryAdapter repository;
    private final UserRepository userRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return repository.findAll().stream().map(this::toMap).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String email = body.get(USEREMAIL) != null ? body.get(USEREMAIL).toString().trim() : null;
        if (email == null || email.isEmpty()) {
            throw new BusinessException("userEmail es obligatorio (el mentor se asocia a un usuario existente).");
        }
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("No existe un usuario con email: " + email));
        MentorProfileEntity e = new MentorProfileEntity();
        e.setUser(user);
        e.setExpertise(new ArrayList<>());
        e.setLanguages(new ArrayList<>());
        apply(e, body);
        if (e.getHeadline() == null || e.getHeadline().isBlank()) {
            throw new BusinessException("headline es obligatorio.");
        }
        return ResponseEntity.ok(toMap(repository.save(e)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        MentorProfileEntity e = repository.findById(id).orElseThrow(() -> new NotFoundException("Mentor not found"));
        apply(e, body);
        return ResponseEntity.ok(toMap(repository.save(e)));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @SuppressWarnings("unchecked")
    private void apply(MentorProfileEntity e, Map<String, Object> b) {
        if (b.get(HEADLINE) != null) {
            e.setHeadline(b.get(HEADLINE).toString());
        }
        if (b.containsKey("bio")) {
            e.setBio(b.get("bio") != null && !b.get("bio").toString().isBlank() ? b.get("bio").toString() : null);
        }
        if (b.containsKey(TIMEZONE)) {
            e.setTimezone(b.get(TIMEZONE) != null && !b.get(TIMEZONE).toString().isBlank()
                    ? b.get(TIMEZONE).toString()
                    : null);
        }
        if (b.get("hourlyRateUsd") instanceof Number n) {
            e.setHourlyRateUsdCents((int) Math.round(n.doubleValue() * 100));
        } else if (b.get("hourlyRateUsdCents") instanceof Number n) {
            e.setHourlyRateUsdCents(n.intValue());
        }
        if (b.get("expertise") instanceof List<?> l) {
            e.setExpertise(l.stream().map(Object::toString).map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(ArrayList::new)));
        }
        if (b.get("languages") instanceof List<?> l) {
            e.setLanguages(l.stream().map(Object::toString).map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(ArrayList::new)));
        }
        if (b.get(ACTIVE) != null) {
            e.setActive(Boolean.parseBoolean(b.get(ACTIVE).toString()));
        }
    }

    private Map<String, Object> toMap(MentorProfileEntity e) {
        UserEntity u = e.getUser();
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId());
        m.put(USEREMAIL, u != null ? u.getEmail() : null);
        m.put("name", displayName(u));
        m.put("avatarUrl", u != null ? u.getAvatarUrl() : null);
        m.put(HEADLINE, e.getHeadline() != null ? e.getHeadline() : "");
        m.put("bio", e.getBio() != null ? e.getBio() : "");
        m.put(TIMEZONE, e.getTimezone() != null ? e.getTimezone() : "");
        m.put("hourlyRateUsd", e.getHourlyRateUsdCents() / 100.0);
        m.put("expertise", e.getExpertise() != null ? e.getExpertise() : List.of());
        m.put("languages", e.getLanguages() != null ? e.getLanguages() : List.of());
        m.put(ACTIVE, e.isActive());
        return m;
    }

    /**
     * Nombre visible del mentor. El orden de comprobación importa: primero el nombre elegido por el
     * usuario, si no el email como identificador legible, y cadena vacía si el perfil quedó huérfano
     * (la FK admite nulos históricos), porque el front pinta este campo sin comprobar nulos.
     */
    private String displayName(UserEntity u) {
        if (u == null) {
            return "";
        }
        return u.getDisplayName() != null ? u.getDisplayName() : u.getEmail();
    }
}
