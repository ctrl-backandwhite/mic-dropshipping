package com.nexaplatform.dropshipping.domain.enums;

/**
 * Textos de los emails de autenticación (confirmar cuenta, restablecer contraseña, invitación) en los 8
 * idiomas soportados. Se envían en el idioma del usuario. El cuerpo lleva el marcador {@code {name}}.
 * Mismo patrón que {@link OrderEmailLabel}.
 */
public enum AuthEmailLabel {

    CONFIRM_SUBJECT(new Translations("Confirma tu cuenta · NX036", "Confirm your account · NX036",
            "Confirme a sua conta · NX036", "确认您的账户 · NX036", "Confirmez votre compte · NX036",
            "Bestätige dein Konto · NX036", "Conferma il tuo account · NX036", "Bevestig je account · NX036")),
    CONFIRM_TITLE(new Translations("Confirma tu cuenta", "Confirm your account", "Confirme a sua conta", "确认您的账户",
            "Confirmez votre compte", "Bestätige dein Konto", "Conferma il tuo account", "Bevestig je account")),
    CONFIRM_BODY(new Translations("Hola {name}, te damos la bienvenida a NX036. Confirma tu cuenta para empezar.",
            "Hi {name}, welcome to NX036. Confirm your account to get started.",
            "Olá {name}, bem-vindo à NX036. Confirme a sua conta para começar.", "您好 {name},欢迎来到 NX036。请确认您的账户以开始使用。",
            "Bonjour {name}, bienvenue chez NX036. Confirmez votre compte pour commencer.",
            "Hallo {name}, willkommen bei NX036. Bestätige dein Konto, um loszulegen.",
            "Ciao {name}, benvenuto in NX036. Conferma il tuo account per iniziare.",
            "Hallo {name}, welkom bij NX036. Bevestig je account om te beginnen.")),
    CONFIRM_CTA(new Translations("Confirmar cuenta", "Confirm account", "Confirmar conta", "确认账户",
            "Confirmer le compte", "Konto bestätigen", "Conferma account", "Account bevestigen")),

    RESET_SUBJECT(new Translations("Restablece tu contraseña · NX036", "Reset your password · NX036",
            "Redefina a sua palavra-passe · NX036", "重置您的密码 · NX036", "Réinitialisez votre mot de passe · NX036",
            "Setze dein Passwort zurück · NX036", "Reimposta la password · NX036",
            "Stel je wachtwoord opnieuw in · NX036")),
    RESET_TITLE(new Translations("Restablece tu contraseña", "Reset your password", "Redefina a sua palavra-passe",
            "重置您的密码", "Réinitialisez votre mot de passe", "Setze dein Passwort zurück", "Reimposta la password",
            "Stel je wachtwoord opnieuw in")),
    RESET_BODY(new Translations(
            "Hola {name}, hemos recibido una solicitud para restablecer tu contraseña. Pulsa el botón para crear una nueva. Si no fuiste tú, ignora este correo.",
            "Hi {name}, we received a request to reset your password. Click the button to create a new one. If this wasn't you, ignore this email.",
            "Olá {name}, recebemos um pedido para redefinir a sua palavra-passe. Clique no botão para criar uma nova. Se não foi você, ignore este e-mail.",
            "您好 {name},我们收到了重置您密码的请求。点击按钮创建新密码。如果这不是您本人操作,请忽略此邮件。",
            "Bonjour {name}, nous avons reçu une demande de réinitialisation de votre mot de passe. Cliquez sur le bouton pour en créer un nouveau. Si vous n'êtes pas à l'origine de cette demande, ignorez cet e-mail.",
            "Hallo {name}, wir haben eine Anfrage zum Zurücksetzen deines Passworts erhalten. Klicke auf die Schaltfläche, um ein neues zu erstellen. Falls du das nicht warst, ignoriere diese E-Mail.",
            "Ciao {name}, abbiamo ricevuto una richiesta di reimpostazione della password. Fai clic sul pulsante per crearne una nuova. Se non sei stato tu, ignora questa email.",
            "Hallo {name}, we hebben een verzoek ontvangen om je wachtwoord opnieuw in te stellen. Klik op de knop om een nieuw aan te maken. Was jij dit niet, negeer dan deze e-mail.")),
    RESET_CTA(new Translations("Restablecer contraseña", "Reset password", "Redefinir palavra-passe", "重置密码",
            "Réinitialiser le mot de passe", "Passwort zurücksetzen", "Reimposta password",
            "Wachtwoord opnieuw instellen")),

    INVITE_SUBJECT(new Translations("Te han invitado a NX036", "You've been invited to NX036",
            "Você foi convidado para a NX036", "您受邀加入 NX036", "Vous êtes invité sur NX036",
            "Du wurdest zu NX036 eingeladen", "Sei stato invitato su NX036", "Je bent uitgenodigd voor NX036")),
    INVITE_TITLE(new Translations("Te han invitado a NX036", "You've been invited to NX036",
            "Você foi convidado para a NX036", "您受邀加入 NX036", "Vous êtes invité sur NX036",
            "Du wurdest zu NX036 eingeladen", "Sei stato invitato su NX036", "Je bent uitgenodigd voor NX036")),
    INVITE_BODY(new Translations("Has sido invitado a NX036 Dropshipping. Activa tu cuenta para empezar.",
            "You've been invited to NX036 Dropshipping. Activate your account to get started.",
            "Você foi convidado para a NX036 Dropshipping. Ative a sua conta para começar.",
            "您受邀加入 NX036 Dropshipping。激活您的账户以开始使用。",
            "Vous avez été invité sur NX036 Dropshipping. Activez votre compte pour commencer.",
            "Du wurdest zu NX036 Dropshipping eingeladen. Aktiviere dein Konto, um loszulegen.",
            "Sei stato invitato su NX036 Dropshipping. Attiva il tuo account per iniziare.",
            "Je bent uitgenodigd voor NX036 Dropshipping. Activeer je account om te beginnen.")),
    INVITE_CTA(new Translations("Activar cuenta", "Activate account", "Ativar conta", "激活账户", "Activer le compte",
            "Konto aktivieren", "Attiva account", "Account activeren")),

    LOGIN_SUBJECT(new Translations("Nuevo inicio de sesión en tu cuenta · NX036", "New sign-in to your account · NX036",
            "Novo início de sessão na sua conta · NX036", "您的账户有新的登录 · NX036",
            "Nouvelle connexion à votre compte · NX036", "Neue Anmeldung bei deinem Konto · NX036",
            "Nuovo accesso al tuo account · NX036", "Nieuwe aanmelding bij je account · NX036")),
    LOGIN_TITLE(new Translations("Inicio de sesión detectado", "Sign-in detected", "Início de sessão detetado", "检测到登录",
            "Connexion détectée", "Anmeldung erkannt", "Accesso rilevato", "Aanmelding gedetecteerd")),
    LOGIN_BODY(new Translations(
            "Hola {name}, hemos detectado un inicio de sesión en tu cuenta de NX036 el {date}. Si fuiste tú, no tienes que hacer nada. Si no reconoces esta actividad, protege tu cuenta cambiando la contraseña cuanto antes.",
            "Hi {name}, we detected a sign-in to your NX036 account on {date}. If this was you, no action is needed. If you don't recognize this activity, secure your account by changing your password right away.",
            "Olá {name}, detetámos um início de sessão na sua conta NX036 em {date}. Se foi você, não precisa de fazer nada. Se não reconhece esta atividade, proteja a sua conta alterando a palavra-passe o quanto antes.",
            "您好 {name},我们检测到您的 NX036 账户于 {date} 登录。如果是您本人操作,无需处理。如果您不认识此活动,请立即修改密码以保护您的账户。",
            "Bonjour {name}, nous avons détecté une connexion à votre compte NX036 le {date}. Si c'était vous, aucune action n'est requise. Si vous ne reconnaissez pas cette activité, sécurisez votre compte en changeant votre mot de passe au plus vite.",
            "Hallo {name}, wir haben eine Anmeldung bei deinem NX036-Konto am {date} festgestellt. Warst du das, ist nichts zu tun. Erkennst du diese Aktivität nicht, sichere dein Konto, indem du umgehend dein Passwort änderst.",
            "Ciao {name}, abbiamo rilevato un accesso al tuo account NX036 il {date}. Se sei stato tu, non devi fare nulla. Se non riconosci questa attività, proteggi il tuo account cambiando subito la password.",
            "Hallo {name}, we hebben een aanmelding bij je NX036-account gedetecteerd op {date}. Was jij dit, dan hoef je niets te doen. Herken je deze activiteit niet, beveilig dan je account door direct je wachtwoord te wijzigen.")),
    LOGIN_CTA(new Translations("Proteger mi cuenta", "Secure my account", "Proteger a minha conta", "保护我的账户",
            "Sécuriser mon compte", "Konto sichern", "Proteggi il mio account", "Mijn account beveiligen"));

    /** Las 8 traducciones de una etiqueta, agrupadas para no arrastrar 8 parámetros sueltos (java:S107). */
    private record Translations(String es, String en, String pt, String zh, String fr, String de, String it,
            String nl) {

        String of(String lang) {
            return switch (lang == null ? "es" : lang) {
                case "en" -> en;
                case "pt" -> pt;
                case "zh" -> zh;
                case "fr" -> fr;
                case "de" -> de;
                case "it" -> it;
                case "nl" -> nl;
                default -> es;
            };
        }
    }

    private final Translations translations;

    AuthEmailLabel(Translations translations) {
        this.translations = translations;
    }

    public String of(String lang) {
        return translations.of(lang);
    }

    /** Texto con el nombre sustituido en {@code {name}} (vacío → se limpia el saludo sobrante). */
    public String of(String lang, String name) {
        String n = name != null ? name.trim() : "";
        return of(lang).replace("{name}", n).replace("  ", " ").replace(" ,", ",");
    }
}
