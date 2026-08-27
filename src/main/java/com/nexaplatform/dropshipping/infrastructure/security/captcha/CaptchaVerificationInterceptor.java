package com.nexaplatform.dropshipping.infrastructure.security.captcha;

import com.nexaplatform.dropshipping.api.exception.ArgumentException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Exige un CAPTCHA (proof-of-work ALTCHA) resuelto en los formularios públicos que pueden dispararse de
 * forma masiva por bots (alta de usuarios, contacto, reset de contraseña, alta de newsletter). La
 * solución llega en la cabecera {@code X-Altcha}. Se aplica solo a las rutas registradas en
 * {@code WebMvcConfig} y solo al método POST; una verificación fallida corta con 400 {@code CAPTCHA_FAILED}.
 */
@RequiredArgsConstructor
public class CaptchaVerificationInterceptor implements HandlerInterceptor {

    private static final String HEADER = "X-Altcha";
    private final CaptchaService captchaService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Los preflight CORS (OPTIONS) no llevan cabeceras del formulario: no se les exige captcha.
        if (!HttpMethod.POST.matches(request.getMethod()) || !captchaService.isEnabled()) {
            return true;
        }
        String solution = request.getHeader(HEADER);
        if (!captchaService.verify(solution)) {
            throw new ArgumentException("CAPTCHA_FAILED",
                    "Verificación anti-robot fallida o caducada. Vuelve a intentarlo.");
        }
        return true;
    }
}
