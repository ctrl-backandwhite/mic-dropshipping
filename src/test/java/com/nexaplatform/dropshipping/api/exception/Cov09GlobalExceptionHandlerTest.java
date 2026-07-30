package com.nexaplatform.dropshipping.api.exception;

import com.nexaplatform.dropshipping.api.dto.ApiResponseDtoOut;
import com.nexaplatform.dropshipping.infrastructure.integration.locale.LocaleHolder;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Traducción de excepciones a respuesta HTTP: el contrato que ve TODO cliente de la API.
 *
 * <p>Lo que se fija aquí, más allá del código de estado: el cuerpo nunca filtra detalles internos (ni el
 * SQL de una violación de integridad, ni el motivo real de un login fallido —eso permitiría enumerar
 * cuentas—) y el mensaje sale en el idioma de la petición cuando el código está catalogado.
 */
class Cov09GlobalExceptionHandlerTest {

    private GlobalExceptionHandler subject;

    @BeforeEach
    void buildSubject() {
        subject = new GlobalExceptionHandler();
    }

    @AfterEach
    void limpiarIdioma() {
        LocaleHolder.clear();
    }

    // ---------------------------------------------------------------- jerarquía de dominio

    @Test
    void cadaTipoDeExcepcionDeNegocioTieneSuEstadoHttp() {
        assertThat(subject.handleNotFound(new NotFoundException("User")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(subject.handleArgument(new ArgumentException("mal")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(subject.handleDomain(new DomainException("conflicto")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        // ConflictException es subclase de DomainException: el mismo 409, con su propio código.
        assertThat(subject.handleDomain(new ConflictException("ya existe")).getBody().getCode())
                .isEqualTo("CF001");
        assertThat(subject.handleBusiness(new BusinessException("no se puede")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(subject.handleRateLimit(new RateLimitExceededException("demasiadas")).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Cualquier otra BaseException no clasificada es un fallo nuestro: 500.
        assertThat(subject.handleBase(new BaseException("XX999", "raro")).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void elMensajeSaleEnElIdiomaDeLaPeticionCuandoElCodigoEstaCatalogado() {
        // El usuario nunca debe leer el literal interno ("User"), sino el texto catalogado en su idioma.
        LocaleHolder.set("en");

        ApiResponseDtoOut<?> body = subject.handleNotFound(new NotFoundException("User")).getBody();

        assertThat(body.getCode()).isEqualTo("ENF001");
        assertThat(body.getMessage()).isEqualTo("The requested item was not found.");
    }

    @Test
    void unCodigoSinCatalogarConservaElMensajeDeLaExcepcion() {
        // Si no, un error específico (p.ej. "confirma en la pasarela real") se perdería tras un genérico.
        ApiResponseDtoOut<?> body = subject
                .handleBusiness(new BusinessException("PAYMENT_REQUIRES_REAL_CONFIRMATION",
                        "Esta recarga debe completarse en la pasarela de pago real"))
                .getBody();

        assertThat(body.getMessage()).isEqualTo("Esta recarga debe completarse en la pasarela de pago real");
    }

    @Test
    void todaRespuestaDeErrorLlevaCodigoYMarcaDeTiempo() {
        ApiResponseDtoOut<?> body = subject.handleBusiness(new BusinessException("no se puede")).getBody();

        assertThat(body.getCode()).isEqualTo("BR001");
        assertThat(body.getTimestamp()).isNotNull();
    }

    // ---------------------------------------------------------------- validación

    @Test
    void unaValidacionFallidaDiceQueCampoFalla() {
        // DROP-682: un "Validation error" genérico obligaba a adivinar qué campo estaba mal.
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult binding = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(binding);
        when(binding.getFieldErrors()).thenReturn(List.of(new FieldError("req", "email", "no debe estar vacío")));

        ResponseEntity<ApiResponseDtoOut<?>> res = subject.handleMethodArgumentNotValid(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getMessage()).contains("email: no debe estar vacío");
        assertThat(res.getBody().getDetails()).containsExactly("email: no debe estar vacío");
    }

    @Test
    void unaValidacionSinCamposConcretosCaeAlMensajeGenerico() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult binding = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(binding);
        when(binding.getFieldErrors()).thenReturn(List.of());

        assertThat(subject.handleMethodArgumentNotValid(ex).getBody().getMessage()).isEqualTo("Datos inválidos");
    }

    @Test
    void unaViolacionDeRestriccionListaTodosLosMensajes() {
        ConstraintViolation<?> v = mock(ConstraintViolation.class);
        when(v.getMessage()).thenReturn("la cantidad debe ser positiva");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(v));

        ResponseEntity<ApiResponseDtoOut<?>> res = subject.handleConstraintViolation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getDetails()).containsExactly("la cantidad debe ser positiva");
    }

    @Test
    void faltarUnParametroObligatorioEsCulpaDeLaPeticion() {
        ResponseEntity<ApiResponseDtoOut<?>> res = subject
                .handleMissingParam(new MissingServletRequestParameterException("page", "int"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("VE002");
    }

    @Test
    void unParametroDelTipoEquivocadoEsCulpaDeLaPeticion() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getMessage()).thenReturn("no se puede convertir 'abc' a UUID");

        ResponseEntity<ApiResponseDtoOut<?>> res = subject.handleTypeMismatch(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("VE003");
    }

    @Test
    void unParametroInvalidoNoSeDevuelveComoErrorDelServidor() {
        // size=-1 hacía fallar a PageRequest y salía como 500: ensuciaba los logs y era trivial de provocar.
        ResponseEntity<ApiResponseDtoOut<?>> res = subject
                .handleIllegalArgument(new IllegalArgumentException("Page size must not be less than one"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("VE005");
    }

    @Test
    void unParametroInvalidoSinMensajeNoRompeElCuerpoDeLaRespuesta() {
        ResponseEntity<ApiResponseDtoOut<?>> res = subject.handleIllegalArgument(new IllegalArgumentException());

        assertThat(res.getBody().getDetails()).containsExactly("");
    }

    @Test
    void unJsonIlegibleSeExplicaSinFiltrarElDetalleInterno() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        when(ex.getMostSpecificCause())
                .thenReturn(new IllegalStateException("Unexpected character ('}' (code 125)) at [Source: ...]"));

        ResponseEntity<ApiResponseDtoOut<?>> res = subject.handleNotReadable(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("VE004");
        assertThat(res.getBody().getMessage()).doesNotContain("Source:");
        assertThat(res.getBody().getDetails()).isEmpty();
    }

    // ---------------------------------------------------------------- base de datos

    @Test
    void unaViolacionDeIntegridadSeTraduceYNuncaDevuelveSqlCrudo() {
        String sql = "ERROR: duplicate key value violates unique index; Key (email)=(ada@example.com) already exists.";
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new IllegalStateException(sql));

        ResponseEntity<ApiResponseDtoOut<?>> res = subject.handleDataIntegrity(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().getCode()).isEqualTo("DB001");
        assertThat(res.getBody().getMessage()).contains("email = ada@example.com").doesNotContain("duplicate key");
    }

    // ---------------------------------------------------------------- seguridad

    @Test
    void unAccesoDenegadoNoRevelaMasDeLaCuenta() {
        ResponseEntity<ApiResponseDtoOut<?>> res = subject.handleForbidden(new AccessDeniedException("Access Denied"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getCode()).isEqualTo("SE001");
    }

    @Test
    void unLoginFallidoRespondeSiempreLoMismoSeaCualSeaElMotivo() {
        // Distinguir "no existe" de "contraseña incorrecta" permitiría enumerar cuentas de la plataforma.
        ResponseEntity<ApiResponseDtoOut<?>> res = subject
                .handleUnauthorized(new BadCredentialsException("La cuenta ada@example.com está bloqueada"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().getCode()).isEqualTo("SE002");
        assertThat(res.getBody().getDetails()).containsExactly("Invalid credentials");
        assertThat(res.getBody().getMessage()).doesNotContain("ada@example.com");
    }

    // ---------------------------------------------------------------- rutas y catch-all

    @Test
    void unaRutaInexistenteEsUnCuatrocientosCuatroConLaRutaPedida() {
        ResponseEntity<ApiResponseDtoOut<?>> res = subject
                .handleNoRoute(new NoResourceFoundException(HttpMethod.GET, "/api/no-existe", "/api/no-existe"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().getCode()).isEqualTo("ENF002");
        assertThat(res.getBody().getMessage()).contains("/api/no-existe");
    }

    @Test
    void unMetodoNoPermitidoDevuelveCuatrocientosCinco() {
        ResponseEntity<ApiResponseDtoOut<?>> res = subject
                .handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("PUT"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.getBody().getCode()).isEqualTo("ME001");
    }

    @Test
    void unFalloInesperadoNoFiltraNadaHaciaFuera() {
        ResponseEntity<ApiResponseDtoOut<?>> res = subject
                .handleGlobal(new NullPointerException("Cannot invoke getUser() because user is null"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().getCode()).isEqualTo("IS001");
        assertThat(res.getBody().getMessage()).doesNotContain("getUser()");
        assertThat(res.getBody().getDetails()).isEmpty();
    }
}
