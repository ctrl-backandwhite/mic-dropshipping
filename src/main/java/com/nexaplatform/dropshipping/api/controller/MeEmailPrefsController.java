package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Customer email preferences: opt out of marketing/affiliate/newsletter emails (DROP-653). */
@RestController
@RequestMapping("/api/me/email-preferences")
@RequiredArgsConstructor
public class MeEmailPrefsController {

    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        boolean optOut = userRepository.findById(userId).map(u -> u.isMarketingOptOut()).orElse(false);
        return ResponseEntity.ok(Map.of("marketingOptOut", optOut));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> update(Authentication auth, @RequestBody Map<String, Boolean> body) {
        UUID userId = UUID.fromString(auth.getName());
        boolean optOut = Boolean.TRUE.equals(body.get("marketingOptOut"));
        userRepository.findById(userId).ifPresent(u -> {
            u.setMarketingOptOut(optOut);
            userRepository.save(u);
        });
        return ResponseEntity.ok(Map.of("marketingOptOut", optOut));
    }
}
