package com.nexaplatform.dropshipping.domain.enums;

import com.nexaplatform.dropshipping.domain.enums.InvoiceLabel.Translations;

/**
 * Nombres de plan y periodo de facturación traducidos a los 8 idiomas, para que la LÍNEA de la factura del
 * plan se muestre en el idioma del usuario (antes venía en inglés desde Stripe). Reutiliza el record
 * {@link Translations} de {@link InvoiceLabel}. Mismos textos que el i18n del frontend (plans.name.* /
 * plans.period.*).
 */
public enum SubscriptionPlanLabel {

    NAME_FREE(new Translations("Gratis", "Free", "Grátis", "免费版", "Gratuit", "Kostenlos", "Gratis",
            "Gratis")), NAME_STARTER(
                    new Translations("Inicial", "Starter", "Inicial", "入门版", "Initial", "Starter", "Starter",
                            "Starter")), NAME_PRO(
                                    new Translations("Pro", "Pro", "Pro", "专业版", "Pro", "Pro", "Pro",
                                            "Pro")), NAME_ENTERPRISE(
                                                    new Translations("Empresa", "Enterprise", "Empresa", "企业版",
                                                            "Entreprise", "Enterprise", "Enterprise", "Enterprise")),

    PERIOD_MONTHLY(new Translations("Mensual", "Monthly", "Mensal", "按月", "Mensuel", "Monatlich", "Mensile",
            "Maandelijks")), PERIOD_YEARLY(
                    new Translations("Anual", "Yearly", "Anual", "按年", "Annuel", "Jährlich", "Annuale", "Jaarlijks"));

    private final Translations translations;

    SubscriptionPlanLabel(Translations translations) {
        this.translations = translations;
    }

    public String of(String lang) {
        return translations.of(lang);
    }

    /** Nombre del plan traducido a partir de su código (FREE/STARTER/PRO/ENTERPRISE); desconocido → el código. */
    public static String planName(String code, String lang) {
        if (code == null) {
            return "";
        }
        return switch (code.trim().toUpperCase()) {
            case "FREE" -> NAME_FREE.of(lang);
            case "STARTER" -> NAME_STARTER.of(lang);
            case "PRO" -> NAME_PRO.of(lang);
            case "ENTERPRISE" -> NAME_ENTERPRISE.of(lang);
            default -> code;
        };
    }

    /** Periodo traducido (MONTHLY/YEARLY); desconocido → cadena vacía. */
    public static String period(String billingPeriod, String lang) {
        if (billingPeriod == null) {
            return "";
        }
        return switch (billingPeriod.trim().toUpperCase()) {
            case "YEARLY", "YEAR" -> PERIOD_YEARLY.of(lang);
            case "MONTHLY", "MONTH" -> PERIOD_MONTHLY.of(lang);
            default -> "";
        };
    }
}
