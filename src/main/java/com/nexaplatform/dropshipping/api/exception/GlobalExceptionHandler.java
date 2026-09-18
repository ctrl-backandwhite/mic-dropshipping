package com.nexaplatform.dropshipping.api.exception;

import com.nexaplatform.dropshipping.api.dto.ApiResponseDtoOut;
import com.nexaplatform.dropshipping.infrastructure.integration.locale.LocaleHolder;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Global exception handler. Mirrors the core's contract: each exception type
 * maps to an HTTP status and a uniform {@link ApiResponseDtoOut} body carrying
 * {@code code}, {@code message}, {@code details} and {@code timestamp}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static ApiResponseDtoOut<?> body(String code, String message, List<String> details) {
        // Fuente ÚNICA de i18n de errores: si el código está catalogado en ErrorCode, devolvemos el
        // mensaje en el idioma de la petición (LocaleHolder); si no, el mensaje original de la excepción.
        String localized = ErrorCode.localize(code, LocaleHolder.get());
        return ApiResponseDtoOut.builder().code(code).message(localized != null ? localized : message)
                .details(details).timestamp(ZonedDateTime.now(ZoneOffset.UTC)).build();
    }

    // ---------------- Domain hierarchy ----------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleNotFound(NotFoundException ex) {
        log.warn("Entity not found: {} - Code: {}", ex.getMessage(), ex.getCode());
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ArgumentException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleArgument(ArgumentException ex) {
        log.warn("Argument exception: {} - Code: {}", ex.getMessage(), ex.getCode());
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()), HttpStatus.BAD_REQUEST);
    }

    /** Covers {@link DomainException} and its subclass {@link ConflictException}. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleDomain(DomainException ex) {
        log.warn("Domain exception: {} - Code: {}", ex.getMessage(), ex.getCode());
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleBusiness(BusinessException ex) {
        log.warn("Business exception: {} - Code: {}", ex.getMessage(), ex.getCode());
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleRateLimit(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {} - Code: {}", ex.getMessage(), ex.getCode());
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()), HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Catch-all for any other {@link BaseException} → 500. */
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleBase(BaseException ex) {
        log.error("Base exception: {} - Code: {}", ex.getMessage(), ex.getCode(), ex);
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ---------------- Framework / validation ----------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        // DROP-682: el mensaje indica el/los campo(s) que fallan, no un genérico "Validation error".
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage()).toList();
        String msg = details.isEmpty() ? "Datos inválidos" : "Datos inválidos — " + String.join("; ", details);
        return new ResponseEntity<>(body("VE001", msg, details), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> details = ex.getConstraintViolations().stream().map(ConstraintViolation::getMessage).toList();
        String msg = details.isEmpty() ? "Datos inválidos" : "Datos inválidos — " + String.join("; ", details);
        return new ResponseEntity<>(body("VE001", msg, details), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleMissingParam(MissingServletRequestParameterException ex) {
        return new ResponseEntity<>(body("VE002", "Missing required parameter", List.of(ex.getMessage())),
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return new ResponseEntity<>(body("VE003", "Invalid parameter type", List.of(ex.getMessage())),
                HttpStatus.BAD_REQUEST);
    }

    // Cabecera requerida ausente (p. ej. el webhook inbound sin X-NX-Signature): 400, no un 500 genérico.
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleMissingHeader(
            org.springframework.web.bind.MissingRequestHeaderException ex) {
        return new ResponseEntity<>(body("VE001", "Cabecera requerida ausente: " + ex.getHeaderName(),
                List.of(ex.getHeaderName())), HttpStatus.BAD_REQUEST);
    }

    // Content-Type no soportado (p. ej. text/plain en un endpoint JSON): 415, no un 500 genérico.
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleMediaType(
            org.springframework.web.HttpMediaTypeNotSupportedException ex) {
        return new ResponseEntity<>(body("VE006", "Content-Type no soportado", List.of(ex.getMessage())),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleNotReadable(HttpMessageNotReadableException ex) {
        // El cliente solo recibe "JSON inválido" (no se le filtra el detalle interno), pero sin dejar
        // rastro en el log un cuerpo que Jackson no sabe leer es indiagnosticable desde fuera.
        log.warn("::> [API] Cuerpo de petición ilegible: {}", ex.getMostSpecificCause().getMessage());
        return new ResponseEntity<>(body("VE004", "El contenido enviado no es un JSON válido. Revisa el formato.",
                List.of()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Nunca exponer el SQL crudo: se traduce a un mensaje claro y accionable para el usuario.
        String human = ErrorMessages.humanize(ex);
        log.warn("Data integrity violation ({}): {}", human, ex.getMostSpecificCause().getMessage());
        return new ResponseEntity<>(body("DB001", human, List.of(human)), HttpStatus.CONFLICT);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiResponseDtoOut<?>> handleForbidden(RuntimeException ex) {
        return new ResponseEntity<>(body("SE001", "Access denied", List.of(ex.getMessage())), HttpStatus.FORBIDDEN);
    }

    /**
     * 2FA: la contraseña era correcta pero falta el segundo factor. Se distingue del 401 genérico con un
     * código propio para que el front sepa que debe pedir el OTP. No abre oráculo de enumeración: solo se
     * llega aquí tras validar la contraseña.
     */
    @ExceptionHandler(TwoFactorRequiredException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleTwoFactorRequired(TwoFactorRequiredException ex) {
        return new ResponseEntity<>(body("MFA_REQUIRED", "Two-factor authentication code required",
                List.of(ex.getMessage())), HttpStatus.UNAUTHORIZED);
    }

    /** 2FA: contraseña correcta pero el código TOTP / de recuperación es inválido. */
    @ExceptionHandler(TwoFactorInvalidException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleTwoFactorInvalid(TwoFactorInvalidException ex) {
        return new ResponseEntity<>(body("MFA_INVALID", "Invalid two-factor authentication code",
                List.of(ex.getMessage())), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleUnauthorized(AuthenticationException ex) {
        // Respuesta UNIFORME: nunca se expone el motivo real (credenciales malas, cuenta no
        // activada, bloqueada, usuario inexistente, token caído…) para no permitir enumeración
        // de cuentas ni dar pistas a un atacante. El detalle se queda en logs, no en la respuesta.
        log.debug("Authentication failed: {}", ex.getMessage());
        return new ResponseEntity<>(body("SE002", "Unauthorized", List.of("Invalid credentials")),
                HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleNoRoute(NoResourceFoundException ex) {
        return new ResponseEntity<>(body("ENF002", "Route not found: " + ex.getResourcePath(), List.of()),
                HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return new ResponseEntity<>(body("ME001", ex.getMessage(), List.of()), HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Parámetros que la aplicación rechaza por inválidos (p.ej. {@code size=-1} o {@code page=-1}, que
     * hacen fallar a {@code PageRequest}). Es culpa de la PETICIÓN, no del servidor: devolverlos como 500
     * ensuciaba los logs de errores reales y daba a cualquiera una forma trivial de provocar fallos.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("::> [API] Parámetro inválido: {}", ex.getMessage());
        return new ResponseEntity<>(body("VE005", "Parámetros de la petición inválidos.",
                List.of(ex.getMessage() == null ? "" : ex.getMessage())), HttpStatus.BAD_REQUEST);
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleGlobal(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return new ResponseEntity<>(body("IS001", "Ocurrió un error inesperado. Inténtalo de nuevo en unos minutos.",
                List.of()), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
