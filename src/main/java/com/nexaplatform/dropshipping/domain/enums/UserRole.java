package com.nexaplatform.dropshipping.domain.enums;

public enum UserRole {
    USER,
    PARTNER,
    OPERATOR,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
