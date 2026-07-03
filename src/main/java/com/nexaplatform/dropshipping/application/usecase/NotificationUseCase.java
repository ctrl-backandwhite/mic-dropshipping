package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.UnreadCount;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the notification aggregate (DROP-11). Operates on the
 * {@link PlatformNotification} domain model; operations are scoped to the owning
 * {@code userId}.
 */
public interface NotificationUseCase {

    /** Carpeta del buzón. INBOX = ni archivada ni en papelera; ARCHIVED = archivada; TRASH = papelera. */
    enum Folder { INBOX, ARCHIVED, TRASH }

    /** Flujo de gestión tipo ticket: NEW → RECEIVED → IN_PROGRESS ⇄ WAITING → RESOLVED. */
    enum Status { NEW, RECEIVED, IN_PROGRESS, WAITING, RESOLVED }

    /** Cambia el estado de gestión de una notificación (transición manual del gestor). */
    void setStatus(UUID id, Status status);

    /** Lists the user's notifications in a folder, newest first. */
    List<PlatformNotification> myNotifications(UUID userId, Folder folder);

    /** Archiva (saca de Recibidos) una notificación. */
    void archive(UUID id);

    /** Devuelve una notificación archivada a Recibidos. */
    void unarchive(UUID id);

    /** Envía una notificación a la papelera (borrado lógico). */
    void moveToTrash(UUID id);

    /** Restaura una notificación desde la papelera. */
    void restore(UUID id);

    /** Elimina definitivamente una notificación (solo desde la papelera). */
    void deletePermanently(UUID id);

    /** Returns the user's unread-notification count. */
    UnreadCount unreadCount(UUID userId);

    /** Marks a single notification as read (no-op if already read or missing). */
    void markRead(UUID id);

    /** Marks all of the user's notifications as read. */
    void markAllRead(UUID userId);

    /**
     * Admin: sends a notification. {@code target} = "all" (or blank) broadcasts to every
     * user; otherwise it is treated as a recipient email. Returns the number created.
     */
    int sendAdminNotification(String target, String title, String body);
}
