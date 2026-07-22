package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.domain.enums.UnsubscribeLabel;
import com.nexaplatform.dropshipping.infrastructure.campaign.MarketingUnsubscribeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Baja/alta de un clic de los correos de campaña (sin login). El enlace del email trae un token HMAC; aquí se
 * conmuta la preferencia del usuario y se devuelve una página de confirmación localizada.
 */
@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class CampaignUnsubscribeController {

    private final MarketingUnsubscribeService unsubscribeService;
    private final TemplateEngine templateEngine;

    @Value("${nexadrop.oauth.issuer:http://localhost:18082}")
    private String backendBaseUrl;

    @Value("${nexadrop.storefront.base-url:http://localhost:3003}")
    private String storefrontBaseUrl;

    @GetMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(@RequestParam String token,
            @RequestParam(required = false, defaultValue = "es") String lang) {
        String language = normalizeLang(lang);
        Optional<UserEntity> user = unsubscribeService.unsubscribe(token);
        if (user.isEmpty()) {
            return html(invalidPage(language));
        }
        String resubUrl = backendBaseUrl + "/api/campaigns/resubscribe?lang=" + language + "&token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return html(page(language, UnsubscribeLabel.OFF_TITLE.of(language), UnsubscribeLabel.OFF_MESSAGE.of(language),
                resubUrl, UnsubscribeLabel.RESUBSCRIBE_LABEL.of(language)));
    }

    @GetMapping(value = "/resubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> resubscribe(@RequestParam String token,
            @RequestParam(required = false, defaultValue = "es") String lang) {
        String language = normalizeLang(lang);
        Optional<UserEntity> user = unsubscribeService.resubscribe(token);
        if (user.isEmpty()) {
            return html(invalidPage(language));
        }
        return html(page(language, UnsubscribeLabel.ON_TITLE.of(language), UnsubscribeLabel.ON_MESSAGE.of(language),
                storefrontBaseUrl, UnsubscribeLabel.HOME_LABEL.of(language)));
    }

    private String invalidPage(String language) {
        return page(language, UnsubscribeLabel.INVALID_TITLE.of(language),
                UnsubscribeLabel.INVALID_MESSAGE.of(language), storefrontBaseUrl,
                UnsubscribeLabel.HOME_LABEL.of(language));
    }

    private String page(String language, String title, String message, String actionUrl, String actionLabel) {
        Context ctx = new Context();
        ctx.setVariable("lang", language);
        ctx.setVariable("title", title);
        ctx.setVariable("message", message);
        ctx.setVariable("actionUrl", actionUrl);
        ctx.setVariable("actionLabel", actionLabel);
        return templateEngine.process("pages/unsubscribe-result", ctx);
    }

    private ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }

    private String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return "es";
        }
        return lang.trim().toLowerCase();
    }
}
