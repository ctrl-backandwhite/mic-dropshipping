package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CainiaoLinkClientTest {

    private static final String APP_SECRET = "test-app-secret";

    private CainiaoLinkClient client;

    @BeforeEach
    void setUp() {
        client = new CainiaoLinkClient();
        ReflectionTestUtils.setField(client, "appKey", "test-app-key");
        ReflectionTestUtils.setField(client, "appSecret", APP_SECRET);
    }

    @Test
    void sign_matchesIndependentBase64Md5() throws Exception {
        String logisticsInterface = "{\"tradeOrderId\":\"42\"}";

        assertThat(client.sign(logisticsInterface))
                .isEqualTo(base64Md5(logisticsInterface + APP_SECRET));
    }

    @Test
    void sign_differsForDifferentPayloads() {
        assertThat(client.sign("{\"a\":1}")).isNotEqualTo(client.sign("{\"a\":2}"));
    }

    @Test
    void verify_acceptsCorrectDigest() {
        String logisticsInterface = "{\"event\":\"ACCEPT\"}";
        String digest = client.sign(logisticsInterface);

        assertThat(client.verify(logisticsInterface, digest)).isTrue();
    }

    @Test
    void verify_rejectsTamperedPayload() {
        String digest = client.sign("{\"event\":\"ACCEPT\"}");

        assertThat(client.verify("{\"event\":\"REJECT\"}", digest)).isFalse();
    }

    @Test
    void verify_rejectsWrongDigest() {
        assertThat(client.verify("{\"event\":\"ACCEPT\"}", "not-the-digest")).isFalse();
    }

    @Test
    void verify_failsClosedOnNullOrBlankInputs() {
        String payload = "{\"x\":1}";
        String digest = client.sign(payload);

        assertThat(client.verify(null, digest)).isFalse();
        assertThat(client.verify(payload, null)).isFalse();
        assertThat(client.verify(payload, "")).isFalse();
        assertThat(client.verify(payload, "   ")).isFalse();
    }

    @Test
    void verify_failsClosedWhenAppSecretMissing() {
        CainiaoLinkClient noSecret = new CainiaoLinkClient();
        ReflectionTestUtils.setField(noSecret, "appSecret", "");
        String payload = "{\"x\":1}";

        assertThat(noSecret.verify(payload, "anything")).isFalse();
    }

    private static String base64Md5(String input) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] hash = md5.digest(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}
