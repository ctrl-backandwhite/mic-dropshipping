package com.nexaplatform.dropshipping.application.service;

import java.math.BigInteger;

/** Validación de IBAN: normaliza, comprueba longitud básica y el resto mod-97 (ISO 13616). */
public final class IbanValidator {

    private IbanValidator() {
    }

    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        String iban = raw.replaceAll("\\s", "").toUpperCase();
        if (iban.length() < 15 || iban.length() > 34 || !iban.matches("[A-Z]{2}\\d{2}[A-Z0-9]+")) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            numeric.append(Character.isLetter(c) ? Integer.toString(c - 'A' + 10) : c);
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
