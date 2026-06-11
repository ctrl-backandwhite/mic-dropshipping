package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.api.dto.in.ActivateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ChangePasswordDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.LoginDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetConfirmDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PasswordResetRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RegisterDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.UpdateProfileDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.RegisterDtoOut;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

/**
 * Use case for authentication and the authenticated user's profile. Holds all
 * login/session/SecurityContext and profile logic previously living in the
 * {@code AuthController} and {@code MeController}.
 */
public interface AuthUseCase {

    /** Register a new account. */
    RegisterDtoOut register(RegisterDtoIn req);

    /** Authenticate, persist the session and return the user's profile. */
    MeDtoOut login(LoginDtoIn req, HttpServletRequest httpRequest, HttpServletResponse httpResponse);

    /** Activate an account with an activation code. */
    void activate(ActivateDtoIn req);

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

    /** Upload the authenticated user's avatar and return the refreshed view. */
    MeDtoOut uploadAvatar(Authentication authentication, MultipartFile file);
}
