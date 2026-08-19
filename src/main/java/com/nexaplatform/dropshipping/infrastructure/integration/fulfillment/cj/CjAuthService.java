package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CarrierTokenEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CarrierTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * El token de CJ: cuándo se pide, cuándo se reutiliza y cuándo se renueva.
 *
 * <p><b>Por qué no se pide en cada llamada.</b> CJ limita la autenticación a <b>una petición por
 * segundo</b> y, dentro de las siguientes 24 horas, devuelve exactamente el mismo token. Pedirlo cada
 * vez no solo no aporta nada: agota la cuota y deja la tienda sin transportista justo cuando más
 * peticiones hay, que es cuando alguien está pagando.
 *
 * <p><b>Por qué se guarda en la base.</b> Un token en memoria muere con el proceso. Con un despliegue en
 * caliente o dos réplicas, cada una pediría el suyo y competirían por ese límite de una por segundo.
 * La tabla {@code carrier_token} tiene índice único por transportista, así que todas comparten el mismo.
 *
 * <p><b>Cada diez días.</b> La documentación de CJ dice que el token dura quince días; la respuesta real
 * medida el 18-ago-2026 daba ciento ochenta, para el token y para el refresco. Renovar a los diez deja
 * margen con cualquiera de las dos cifras y, sobre todo, hace que un token revocado por CJ se descubra
 * en una renovación rutinaria y no en mitad de un checkout.
 *
 * <p>El orden es siempre el mismo: si el refresco sigue vivo se refresca, y solo si no lo está se manda
 * la clave. La clave es lo único que no se puede rotar sin tocar la configuración de todos los entornos,
 * así que se usa lo menos posible — y <b>nunca aparece en un registro ni en un mensaje de error</b>,
 * porque los dos acaban en sitios que ve más gente de la que debería.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CjAuthService {

    /** Nombre del transportista en {@code carrier_token}. */
    public static final String CARRIER = "CJ";

    private final CarrierTokenRepository repositorio;
    private final CjTokenClient cliente;
    private final Clock reloj;

    @Value("${nexadrop.cj.api-key:}")
    private String apiKey;

    @Value("${nexadrop.cj.token-refresh-days:10}")
    private int diasHastaRenovar;

    /**
     * El token con el que llamar a CJ, renovado si ya toca.
     *
     * <p>Es {@code synchronized} porque dos peticiones simultáneas que encuentren el token caducado
     * pedirían dos tokens a la vez, y eso es exactamente lo que el límite de una por segundo castiga.
     */
    /**
     * <b>Transacción PROPIA ({@code REQUIRES_NEW}), y esto no es un adorno.</b> Quien pide el token suele
     * ser la cotización del checkout, que entra por {@code CheckoutPreviewService.compute} — anotado
     * {@code @Transactional(readOnly = true)}. Con la propagación por defecto, guardar el token se unía a
     * ESA transacción, y Hibernate descarta las escrituras de una transacción de lectura: el token se
     * guardaba en el aire, <b>sin error ninguno</b>, y cada cotización volvía a pedir uno nuevo contra un
     * límite de UNA petición por segundo. Se vio en local el 19-ago-2026: 18 opciones cotizadas y la
     * tabla con cero filas.
     *
     * <p>Con transacción propia el token se persiste aunque la operación que lo pidió sea de lectura, y
     * aunque esa operación falle después: el token seguiría siendo válido igual, así que no hay ninguna
     * razón para deshacerlo con ella.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized String tokenVigente() {
        Optional<CarrierTokenEntity> guardado = repositorio.findByCarrier(CARRIER);
        if (guardado.isPresent() && !tocaRenovar(guardado.get())) {
            return guardado.get().getAccessToken();
        }
        return guardar(pedirTokenNuevo(guardado.orElse(null))).getAccessToken();
    }

    /**
     * Identificador de la cuenta en CJ. Hace falta para verificar la firma de los avisos del webhook, y
     * sale del token ya guardado: comprobar una firma no puede costar una llamada a CJ, porque hay que
     * responderle en menos de tres segundos o desactiva el webhook.
     */
    @Transactional(readOnly = true)
    public String openId() {
        return repositorio.findByCarrier(CARRIER)
                .map(CarrierTokenEntity::getOpenId)
                .orElse(null);
    }

    /** ¿Han pasado ya los días de rigor desde que se obtuvo? */
    private boolean tocaRenovar(CarrierTokenEntity token) {
        if (token.getObtainedAt() == null) {
            return true;
        }
        Duration edad = Duration.between(token.getObtainedAt(), Instant.now(reloj));
        return edad.compareTo(Duration.ofDays(diasHastaRenovar)) >= 0;
    }

    /**
     * Refresca si se puede; si no, se autentica con la clave.
     *
     * <p>Un refresco rechazado no puede dejar la tienda sin transportista: CJ puede invalidarlo por su
     * cuenta —cambio de contraseña, revocación— y en ese caso la clave sigue siendo válida. Por eso el
     * fallo del refresco no se propaga, se cae a la vía de la clave.
     */
    private CjToken pedirTokenNuevo(CarrierTokenEntity anterior) {
        if (refrescoUtilizable(anterior)) {
            try {
                return cliente.refrescar(anterior.getRefreshToken());
            } catch (RuntimeException e) {
                log.warn("El refresco del token de CJ falló ({}); se vuelve a autenticar con la clave",
                        e.getMessage());
            }
        }
        return autenticarConLaClave();
    }

    private boolean refrescoUtilizable(CarrierTokenEntity anterior) {
        return anterior != null
                && anterior.getRefreshToken() != null && !anterior.getRefreshToken().isBlank()
                && (anterior.getRefreshExpiresAt() == null
                    || anterior.getRefreshExpiresAt().isAfter(Instant.now(reloj)));
    }

    private CjToken autenticarConLaClave() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Falta la clave de CJ: define CJ_API_KEY en el entorno para poder autenticarse.");
        }
        try {
            return cliente.obtenerConApiKey(apiKey);
        } catch (RuntimeException e) {
            // El mensaje original puede traer la petición entera, con la clave dentro. Se sustituye por
            // uno propio: estos textos acaban en el registro y en las alertas.
            throw new IllegalStateException("No se pudo autenticar contra CJ con la clave configurada.", e);
        }
    }

    private CarrierTokenEntity guardar(CjToken nuevo) {
        CarrierTokenEntity fila = repositorio.findByCarrier(CARRIER)
                .orElseGet(() -> CarrierTokenEntity.builder().carrier(CARRIER).build());
        fila.setAccessToken(nuevo.accessToken());
        fila.setRefreshToken(nuevo.refreshToken());
        fila.setOpenId(nuevo.openId());
        fila.setObtainedAt(Instant.now(reloj));
        fila.setRefreshExpiresAt(nuevo.refreshExpiraEn());
        repositorio.save(fila);
        return fila;
    }
}
