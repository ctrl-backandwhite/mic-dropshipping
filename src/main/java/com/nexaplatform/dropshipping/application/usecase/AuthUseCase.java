package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.api.dto.in.ActivateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ResendActivationDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ChangePasswordDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.DeleteAccountConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.LoginDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RefreshTokenDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RegisterDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.UpdateProfileDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.LoginDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.RegisterDtoOut;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

/**
 * Use case for authentication and the authenticated user's profile. Holds all
 * login/session/SecurityContext and profile logic previously living in the
 * {@code AuthController} and {@code MeController}.
 */
public interface AuthUseCase {

    /** Register a new account. */
    RegisterDtoOut register(RegisterDtoIn req);

    /** Authenticate and return the Bearer token pair plus the user's profile. */
    LoginDtoOut login(LoginDtoIn req, HttpServletRequest httpRequest, HttpServletResponse httpResponse);

    /** Exchange a valid refresh token for a fresh token pair. */
    LoginDtoOut refresh(RefreshTokenDtoIn req);

    /** Revoke the authenticated user's tokens (logout). No-op when unauthenticated. */
    void logout(Authentication authentication);

    /** Activate an account with an activation code. */
    void activate(ActivateDtoIn req);

    void resendActivation(ResendActivationDtoIn req);

    /** Request a password reset email. */
    void requestReset(PasswordResetRequestDtoIn req);

    /** Confirm a password reset using a token. */
    void confirmReset(PasswordResetConfirmDtoIn req);

    /** Return the authenticated user's profile, or {@code null} when unauthenticated. */
    MeDtoOut me(Authentication authentication);

    /** Change the authenticated user's password after verifying the current one. */
    void changePassword(Authentication authentication, ChangePasswordDtoIn req);

    /** Update the authenticated user's profile and return the refreshed view. */
    MeDtoOut updateProfile(Authentication authentication, UpdateProfileDtoIn req);

    /** Generate + email a confirmation code to soft-delete the authenticated user's own account. */
    void requestAccountDeletion(Authentication authentication);

    /** Confirm the emailed code and soft-delete the authenticated user's own account. */
    void confirmAccountDeletion(Authentication authentication, DeleteAccountConfirmDtoIn req);
}
