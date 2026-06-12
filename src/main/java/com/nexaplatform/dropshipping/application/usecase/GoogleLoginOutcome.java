package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.User;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Result of resolving a Google login. Either the login may proceed ({@code user}
 * is set, {@code linkRequired} is false — a new account just created via Google,
 * or an account already linked to a Google identity), or a local account already
 * exists for that email and was NOT linked yet ({@code linkRequired} is true,
 * {@code user} is null): the caller must drive a deliberate password confirmation
 * before merging the Google identity, to avoid account takeover.
 */
@Getter
@AllArgsConstructor
public class GoogleLoginOutcome {

    private final User user;
    private final boolean linkRequired;
    private final String email;
}
