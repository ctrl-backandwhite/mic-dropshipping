package com.nexaplatform.dropshipping.infrastructure.security;

import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginAuditListener {

    private final UserUseCase userUseCase;

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String name = event.getAuthentication().getName();
        if (name != null) {
            userUseCase.recordSuccessfulLogin(name);
        }
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        Object principal = event.getAuthentication().getPrincipal();
        if (principal instanceof String s) {
            userUseCase.recordFailedLogin(s);
        }
    }
}
