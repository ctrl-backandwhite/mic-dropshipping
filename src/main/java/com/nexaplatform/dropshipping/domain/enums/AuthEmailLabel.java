package com.nexaplatform.dropshipping.domain.enums;

/**
 * Textos de los emails de autenticación (confirmar cuenta, restablecer contraseña, invitación) en los 8
 * idiomas soportados. Se envían en el idioma del usuario. El cuerpo lleva el marcador {@code {name}}.
 * Mismo patrón que {@link OrderEmailLabel}.
 */
public enum AuthEmailLabel {

    CONFIRM_SUBJECT("Confirma tu cuenta · NX036", "Confirm your account · NX036", "Confirme a sua conta · NX036",
            "确认您的账户 · NX036", "Confirmez votre compte · NX036", "Bestätige dein Konto · NX036",
            "Conferma il tuo account · NX036", "Bevestig je account · NX036"),
    CONFIRM_TITLE("Confirma tu cuenta", "Confirm your account", "Confirme a sua conta", "确认您的账户",
            "Confirmez votre compte", "Bestätige dein Konto", "Conferma il tuo account", "Bevestig je account"),
    CONFIRM_BODY("Hola {name}, te damos la bienvenida a NX036. Confirma tu cuenta para empezar.",
            "Hi {name}, welcome to NX036. Confirm your account to get started.",
            "Olá {name}, bem-vindo à NX036. Confirme a sua conta para começar.",
            "您好 {name},欢迎来到 NX036。请确认您的账户以开始使用。",
            "Bonjour {name}, bienvenue chez NX036. Confirmez votre compte pour commencer.",
            "Hallo {name}, willkommen bei NX036. Bestätige dein Konto, um loszulegen.",
            "Ciao {name}, benvenuto in NX036. Conferma il tuo account per iniziare.",
            "Hallo {name}, welkom bij NX036. Bevestig je account om te beginnen."),
    CONFIRM_CTA("Confirmar cuenta", "Confirm account", "Confirmar conta", "确认账户", "Confirmer le compte",
            "Konto bestätigen", "Conferma account", "Account bevestigen"),

    RESET_SUBJECT("Restablece tu contraseña · NX036", "Reset your password · NX036",
            "Redefina a sua palavra-passe · NX036", "重置您的密码 · NX036", "Réinitialisez votre mot de passe · NX036",
            "Setze dein Passwort zurück · NX036", "Reimposta la password · NX036",
            "Stel je wachtwoord opnieuw in · NX036"),
    RESET_TITLE("Restablece tu contraseña", "Reset your password", "Redefina a sua palavra-passe", "重置您的密码",
            "Réinitialisez votre mot de passe", "Setze dein Passwort zurück", "Reimposta la password",
            "Stel je wachtwoord opnieuw in"),
    RESET_BODY(
            "Hola {name}, hemos recibido una solicitud para restablecer tu contraseña. Pulsa el botón para crear una nueva. Si no fuiste tú, ignora este correo.",
            "Hi {name}, we received a request to reset your password. Click the button to create a new one. If this wasn't you, ignore this email.",
            "Olá {name}, recebemos um pedido para redefinir a sua palavra-passe. Clique no botão para criar uma nova. Se não foi você, ignore este e-mail.",
            "您好 {name},我们收到了重置您密码的请求。点击按钮创建新密码。如果这不是您本人操作,请忽略此邮件。",
            "Bonjour {name}, nous avons reçu une demande de réinitialisation de votre mot de passe. Cliquez sur le bouton pour en créer un nouveau. Si vous n'êtes pas à l'origine de cette demande, ignorez cet e-mail.",
            "Hallo {name}, wir haben eine Anfrage zum Zurücksetzen deines Passworts erhalten. Klicke auf die Schaltfläche, um ein neues zu erstellen. Falls du das nicht warst, ignoriere diese E-Mail.",
            "Ciao {name}, abbiamo ricevuto una richiesta di reimpostazione della password. Fai clic sul pulsante per crearne una nuova. Se non sei stato tu, ignora questa email.",
            "Hallo {name}, we hebben een verzoek ontvangen om je wachtwoord opnieuw in te stellen. Klik op de knop om een nieuw aan te maken. Was jij dit niet, negeer dan deze e-mail."),
    RESET_CTA("Restablecer contraseña", "Reset password", "Redefinir palavra-passe", "重置密码",
            "Réinitialiser le mot de passe", "Passwort zurücksetzen", "Reimposta password",
            "Wachtwoord opnieuw instellen"),

    INVITE_SUBJECT("Te han invitado a NX036", "You've been invited to NX036", "Você foi convidado para a NX036",
            "您受邀加入 NX036", "Vous êtes invité sur NX036", "Du wurdest zu NX036 eingeladen",
            "Sei stato invitato su NX036", "Je bent uitgenodigd voor NX036"),
    INVITE_TITLE("Te han invitado a NX036", "You've been invited to NX036", "Você foi convidado para a NX036",
            "您受邀加入 NX036", "Vous êtes invité sur NX036", "Du wurdest zu NX036 eingeladen",
            "Sei stato invitato su NX036", "Je bent uitgenodigd voor NX036"),
    INVITE_BODY("Has sido invitado a NX036 Dropshipping. Activa tu cuenta para empezar.",
            "You've been invited to NX036 Dropshipping. Activate your account to get started.",
            "Você foi convidado para a NX036 Dropshipping. Ative a sua conta para começar.",
            "您受邀加入 NX036 Dropshipping。激活您的账户以开始使用。",
            "Vous avez été invité sur NX036 Dropshipping. Activez votre compte pour commencer.",
            "Du wurdest zu NX036 Dropshipping eingeladen. Aktiviere dein Konto, um loszulegen.",
            "Sei stato invitato su NX036 Dropshipping. Attiva il tuo account per iniziare.",
            "Je bent uitgenodigd voor NX036 Dropshipping. Activeer je account om te beginnen."),
    INVITE_CTA("Activar cuenta", "Activate account", "Ativar conta", "激活账户", "Activer le compte",
            "Konto aktivieren", "Attiva account", "Account activeren");

    private final String es;
    private final String en;
    private final String pt;
    private final String zh;
    private final String fr;
    private final String de;
    private final String it;
    private final String nl;

    AuthEmailLabel(String es, String en, String pt, String zh, String fr, String de, String it, String nl) {
        this.es = es;
        this.en = en;
        this.pt = pt;
        this.zh = zh;
        this.fr = fr;
        this.de = de;
        this.it = it;
        this.nl = nl;
    }

    public String of(String lang) {
        switch (lang == null ? "es" : lang) {
            case "en":
                return en;
            case "pt":
                return pt;
            case "zh":
                return zh;
            case "fr":
                return fr;
            case "de":
                return de;
            case "it":
                return it;
            case "nl":
                return nl;
            default:
                return es;
        }
    }

    /** Texto con el nombre sustituido en {@code {name}} (vacío → se limpia el saludo sobrante). */
    public String of(String lang, String name) {
        String n = name != null ? name.trim() : "";
        return of(lang).replace("{name}", n).replace("  ", " ").replace(" ,", ",");
    }
}
