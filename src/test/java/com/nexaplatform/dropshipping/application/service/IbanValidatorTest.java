package com.nexaplatform.dropshipping.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IbanValidatorTest {
    @Test
    void aceptaIbanValido() {
        assertTrue(IbanValidator.isValid("ES9121000418450200051332"));
        assertTrue(IbanValidator.isValid("es91 2100 0418 4502 0005 1332"));
    }
    @Test
    void rechazaIbanInvalido() {
        assertFalse(IbanValidator.isValid("ES0021000418450200051332"));
        assertFalse(IbanValidator.isValid("XX"));
        assertFalse(IbanValidator.isValid(null));
        assertFalse(IbanValidator.isValid(""));
    }
}
