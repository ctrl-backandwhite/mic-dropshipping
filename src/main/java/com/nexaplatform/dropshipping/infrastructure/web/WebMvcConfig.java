package com.nexaplatform.dropshipping.infrastructure.web;

import com.nexaplatform.dropshipping.infrastructure.security.captcha.CaptchaService;
import com.nexaplatform.dropshipping.infrastructure.security.captcha.CaptchaVerificationInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra el interceptor de CAPTCHA solo en los formularios públicos que generan correos/altas y que,
 * sin protección, un bot podría disparar de forma masiva. El resto de la API no lo lleva (los endpoints
 * autenticados ya exigen sesión; el catálogo es de solo lectura).
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CaptchaService captchaService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CaptchaVerificationInterceptor(captchaService)).addPathPatterns(
                "/api/auth/register", "/api/auth/password-reset/request", "/api/auth/activate/resend",
                "/api/newsletter/subscribe", "/api/contact");
    }
}
