package com.nexaplatform.dropshipping.provider;

import java.time.Instant;

/**
 * Valores fijos de auditoría (createdAt/updatedAt/createdBy/updatedBy) para las fixtures de los
 * tests de mappers: permiten verificar que los mappers IGNORAN o PRESERVAN auditoría según el caso.
 */
public final class AuditProvider {

    public static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    public static final Instant UPDATED_AT = Instant.parse("2026-02-01T00:00:00Z");
    public static final String CREATED_BY = "creator@nx036.local";
    public static final String UPDATED_BY = "editor@nx036.local";

    public static final String[] AUDIT_FIELDS = {"createdAt", "updatedAt", "createdBy", "updatedBy"};

    private AuditProvider() {
    }
}
