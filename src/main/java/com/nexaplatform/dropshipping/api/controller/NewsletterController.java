package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.NewsletterService;
import com.nexaplatform.dropshipping.application.service.NewsletterService.SubscribeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Public newsletter subscribe / unsubscribe. */
@RestController
@RequestMapping("/api/newsletter")
@RequiredArgsConstructor
public class NewsletterController {

    private final NewsletterService newsletterService;

    @PostMapping("/subscribe")
    public ResponseEntity<Map<String, Object>> subscribe(@RequestBody Map<String, String> body, Authentication auth) {
        UUID userId = auth != null && auth.getName() != null ? safeUuid(auth.getName()) : null;
        SubscribeResult result = newsletterService.subscribe(body.get("email"), userId, "storefront");
        return ResponseEntity.ok(Map.of("status", result.status(), "alreadySubscribed", result.alreadySubscribed()));
    }

    /** El enlace del correo. Va por GET porque se abre desde el propio mensaje, con un clic. */
    @GetMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestParam String token) {
        return ResponseEntity.ok(Map.of("confirmed", newsletterService.confirm(token)));
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<Map<String, Object>> unsubscribe(@RequestBody Map<String, String> body) {
        boolean ok = newsletterService.unsubscribe(body.get("token"));
        return ResponseEntity.ok(Map.of("unsubscribed", ok));
    }

    private static UUID safeUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
