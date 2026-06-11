package com.nexaplatform.dropshipping.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setup() {
        logger = (Logger) LoggerFactory.getLogger(AuditLogger.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void writes_event_and_sanitized_attrs() {
        AuditLogger al = new AuditLogger();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", "abc");
        attrs.put("password", "S3cret!");   // must be dropped
        attrs.put("Authorization", "Bearer xyz"); // must be dropped (case-insensitive)
        attrs.put("note", "ok\nwith\nnewlines");
        al.log("auth.login_ok", "alice@example.com", attrs);

        assertThat(appender.list).hasSize(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("event=auth.login_ok");
        assertThat(msg).contains("principal=alice@example.com");
        assertThat(msg).contains("userId=abc");
        assertThat(msg).doesNotContain("S3cret!");
        assertThat(msg).doesNotContain("Bearer xyz");
        // newlines replaced with spaces
        assertThat(msg).contains("note=ok with with newlines".substring(0, 12));
    }

    @Test
    void truncates_long_values() {
        AuditLogger al = new AuditLogger();
        String huge = "x".repeat(500);
        al.log("evt", huge, Map.of("payload", huge));
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("…");
    }
}
