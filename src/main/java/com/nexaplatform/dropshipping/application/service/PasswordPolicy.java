package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Password policy enforcement. Used by registration, password reset, and admin user creation.
 *
 * Requirements:
 *  - min 12, max 128
 *  - >= 1 uppercase
 *  - >= 1 lowercase
 *  - >= 1 digit
 *  - >= 1 symbol
 *  - not in top-N common passwords list (kept small here; production should use a larger seed
 *    or the haveibeenpwned k-anonymity API).
 */
@Component
public class PasswordPolicy {

    private static final Pattern UPPER = Pattern.compile(".*[A-Z].*");
    private static final Pattern LOWER = Pattern.compile(".*[a-z].*");
    private static final Pattern DIGIT = Pattern.compile(".*\\d.*");
    private static final Pattern SYMBOL = Pattern.compile(".*[^A-Za-z0-9].*");

    private static final Set<String> COMMON = Set.of("password", "password1", "password123", "password1234", "passw0rd",
            "qwerty", "qwerty123", "12345678", "123456789", "1234567890", "letmein", "welcome", "admin",
            "administrator", "changeme", "iloveyou", "abc12345", "monkey", "dragon", "trustno1");

    public void validate(String password) {
        if (password == null || password.length() < 12) {
            throw new BusinessException("Password must be at least 12 characters long");
        }
        if (password.length() > 128) {
            throw new BusinessException("Password must be at most 128 characters long");
        }
        if (!UPPER.matcher(password).matches()) {
            throw new BusinessException("Password must contain at least one uppercase letter");
        }
        if (!LOWER.matcher(password).matches()) {
            throw new BusinessException("Password must contain at least one lowercase letter");
        }
        if (!DIGIT.matcher(password).matches()) {
            throw new BusinessException("Password must contain at least one digit");
        }
        if (!SYMBOL.matcher(password).matches()) {
            throw new BusinessException("Password must contain at least one symbol");
        }
        if (COMMON.contains(password.toLowerCase())) {
            throw new BusinessException("Password is too common");
        }
    }

    public boolean isAcceptable(String password) {
        try {
            validate(password);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }
}
