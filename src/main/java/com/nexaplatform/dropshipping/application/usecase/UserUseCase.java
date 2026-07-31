package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.User;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the {@link User} aggregate root. Operates on the domain
 * model and holds all the logic that used to live in the shared
 * {@code AuthService} and {@code AdminUserQueryService}: registration/activation,
 * login-failure tracking, password reset/change, admin user creation and the
 * admin-panel listing/lock/unlock/role/edit/activate mutations.
 */
public interface UserUseCase extends BaseUseCase<User, User, UUID> {

    /* ============ Registration / activation ============ */

    /** Register a new (inactive) account; enqueues the welcome email. */
    User register(User user, String rawPassword);

    /** Activate an account with a (non-expired) activation code. */
    User activate(String code);

    /* ============ Login-failure tracking ============ */

    /** Increment the failed-login counter and lock the account past the threshold. */
    void recordFailedLogin(String email);

    /** Reset the failed-login counter and stamp the last successful login. */
    void recordSuccessfulLogin(String email);

    /** Envía al usuario un aviso de seguridad de "inicio de sesión detectado" (solo en logins interactivos). */
    void notifyLoginDetected(String email);

    /* ============ Password reset / change ============ */

    /** Issue a password-reset token + email (no-op response shape for unknown emails). */
    void requestPasswordReset(String email);

    /** Confirm a password reset using a raw token and a new password. */
    void confirmPasswordReset(String token, String newPassword);

    /** Replace a user's password after the caller has verified the current one. */
    void changePassword(User user, String newPassword);

    /* ============ Account deletion (soft delete) ============ */

    /** Generate + email a confirmation code and store it (30 min TTL) for a self-service account deletion. */
    void requestAccountDeletion(UUID userId);

    /** Validate the emailed code and, if valid, soft-delete the account (marks deletedAt + deactivates). */
    void confirmAccountDeletion(UUID userId, String code);

    /* ============ Lookups ============ */

    /** Find a user by email or throw {@code NotFoundException}. */
    User findByEmail(String email);

    /** Find a user by id or throw {@code NotFoundException}. */
    User findById(UUID id);

    /** Persist mutations on an already-loaded user (profile/avatar updates). */
    User updateUser(User user);

    /* ============ Admin user management ============ */

    /** Create an active admin/operator/user account with the given role. */
    User createAdminUser(User user, String rawPassword, String role);

    /** List users with optional role/query/country filters, paginated (newest first). */
    List<User> listUsers(String role, String q, String country, int page, int size);

    /** Total number of users matching the same filters as {@link #listUsers}. */
    int countUsers(String role, String q, String country);

    /** Change a user's role. */
    User changeRole(UUID id, String role);

    /** Inline-edit a user's basic fields (only the present, non-null ones change). */
    User editUser(UUID id, User patch, Boolean active);

    /** Lock a user for the given number of minutes. */
    User lock(UUID id, int minutes);

    /** Unlock a user and reset its failed-login counter. */
    User unlock(UUID id);

    /** Force-activate a user, clearing any pending activation code. */
    User forceActivate(UUID id);

    /** Admin-triggered password reset: emails a reset link to the user. */
    void adminResetPassword(UUID id);

    /** Permanently delete a user account (admins cannot be deleted). */
    void deleteUser(UUID id);

    /** Invite a new user: creates an inactive account with the given role and emails an activation link. */
    User inviteUser(String email, String role);

    /**
     * Resolves a (Google-verified) login. Creates a new linked account when no
     * user owns the email, signs in when the existing account is already linked,
     * or signals that a deliberate password confirmation is required when a local
     * (unlinked) account already exists for that email.
     */
    GoogleLoginOutcome resolveGoogleLogin(String email, String firstName, String lastName);

    /** Confirms the Google link on an existing account (after a successful password login). */
    void linkGoogleAccount(UUID id);

}
