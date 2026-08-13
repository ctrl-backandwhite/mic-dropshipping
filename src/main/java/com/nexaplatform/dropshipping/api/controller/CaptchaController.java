package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.out.CaptchaChallengeDtoOut;
import com.nexaplatform.dropshipping.infrastructure.security.captcha.CaptchaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emite retos ALTCHA (proof-of-work). Público: cualquiera que vaya a rellenar un formulario protegido
 * (registro, contacto, reset de contraseña, newsletter) pide primero un reto aquí, lo resuelve el
 * navegador y envía la solución en la cabecera {@code X-Altcha} de la petición del formulario.
 */
@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;

    @GetMapping("/challenge")
    public CaptchaChallengeDtoOut challenge() {
        return captchaService.createChallenge();
    }
}
