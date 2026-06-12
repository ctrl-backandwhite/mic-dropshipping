package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.NewsletterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Admin newsletter: compose & send to subscribers, and view history. */
@RestController
@RequestMapping("/api/admin/newsletter")
@RequiredArgsConstructor
public class AdminNewsletterController {

    private final NewsletterService newsletterService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> overview() {
        List<Map<String, Object>> campaigns = newsletterService.recentCampaigns().stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "subject", c.getSubject(), "recipients",
                        c.getRecipients(), "status", c.getStatus(), "createdAt",
                        c.getCreatedAt() != null ? c.getCreatedAt().toString() : null))
                .toList();
        return ResponseEntity.ok(Map.of("subscribers", newsletterService.subscriberCount(), "campaigns", campaigns));
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody Map<String, String> body) {
        var c = newsletterService.send(body.get("subject"), body.get("bodyHtml"));
        return ResponseEntity.ok(Map.of("id", c.getId(), "recipients", c.getRecipients()));
    }
}
