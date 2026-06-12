package com.nexaplatform.dropshipping.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Structured audit logger for security-sensitive events.
 *
 * Never log password, token, secret, or full Authorization headers. The format is JSON-ish so
 * a downstream collector (Loki/Elasticsearch) can index by event/principal.
 */
@Slf4j
@Component
public class AuditLogger {

    public void log(String event, String principal, Map<String, Object> attrs) {
        String tail = attrs == null
                ? ""
                : " " + attrs.entrySet().stream().filter(e -> !isSensitive(e.getKey()))
                        .map(e -> e.getKey() + "=" + sanitize(e.getValue())).collect(Collectors.joining(" "));
        log.info("AUDIT ts={} event={} principal={}{}", Instant.now(), event, sanitize(principal), tail);
    }

    private boolean isSensitive(String key) {
        if (key == null)
            return false;
        String k = key.toLowerCase();
        return k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("authorization");
    }

    private Object sanitize(Object v) {
        if (v == null)
            return null;
        String s = v.toString();
        if (s.length() > 200)
            return s.substring(0, 200) + "…";
        // drop newlines to prevent log injection
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
