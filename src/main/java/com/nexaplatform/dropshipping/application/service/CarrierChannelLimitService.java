package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CarrierChannelLimitEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CarrierChannelLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * ¿Cuánto admite por bulto el canal del transportista para ESE destino?
 *
 * <p>Hasta ahora el peso máximo por bulto era un escalar global
 * ({@code nexadrop.yunexpress.max-parcel-weight-grams}) y el divisor del peso volumétrico otro
 * ({@code volumetric-divisor}). Los dos son falsos. La cotización oficial publica el tope
 * <b>por canal y por país</b> (sección 六、重量要求 de cada hoja): la línea de ropa {@code FZZXR} lleva
 * 30 kg a España pero 15 kg a Dinamarca, 10 kg a Japón y 5 kg a Noruega; la de carga general
 * {@code THPHR} baja a 2 kg en una docena de destinos. Y el volumétrico ni siquiera se aplica en todas:
 * la de ropa factura siempre el peso real («所有国家：包裹实际重量不计材积») mientras que la de carga
 * general divide entre <b>8000</b>, no entre los 6000 del aéreo estándar.
 *
 * <p>Cotizar y repartir con un único número tiene precio en las dos direcciones: si el escalar es más
 * alto que el tope real, el transportista rechaza la guía —con el pedido ya cobrado— o repesa en almacén
 * y factura la diferencia contra el margen; si es más bajo, se parten pedidos que cabían y cada bulto de
 * más es una guía de más que se paga entera.
 *
 * <p><b>Orden de resolución</b>, de lo más específico a lo más general:
 * <ol>
 *   <li>fila exacta {@code (canal, país)} — la excepción publicada para ese destino;</li>
 *   <li>fila {@code (canal, *)} — el valor por defecto del canal;</li>
 *   <li><b>configuración global</b> — el escalar de siempre.</li>
 * </ol>
 *
 * <p>Ese tercer escalón se conserva a propósito y no es deuda: el entorno de pruebas cotiza por el canal
 * {@code BPA}, que no existe en producción y por tanto no está sembrado. Sin él, el sandbox dejaría de
 * partir pedidos y sus guías —que no admiten más de 2 kg— empezarían a rechazarse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierChannelLimitService {

    /** País comodín: el valor por defecto del canal, el que se usa si el destino no es una excepción. */
    public static final String CUALQUIER_PAIS = "*";

    private final CarrierChannelLimitRepository repository;

    /**
     * Peso máximo por bulto de la configuración, usado solo cuando el canal no está en la tabla.
     * Ver el javadoc de la clase: es lo que mantiene vivo el reparto en el sandbox.
     */
    @Value("${nexadrop.yunexpress.max-parcel-weight-grams:0}")
    private int maxParcelWeightGramsGlobal;

    /** Divisor volumétrico de la configuración, mismo papel de último recurso. */
    @Value("${nexadrop.yunexpress.volumetric-divisor:6000}")
    private int volumetricDivisorGlobal;

    /**
     * De dónde salió el límite que se ha resuelto. No es adorno: saber si un pedido se partió por la
     * excepción del país, por el valor del canal o por el escalar de reserva es la diferencia entre
     * poder explicar una factura del transportista y no poder.
     */
    public enum Origen {
        /** Fila exacta {@code (canal, país)}: la excepción publicada para ese destino. */
        EXACTA,
        /** Fila {@code (canal, *)}: el valor por defecto del canal. */
        CANAL,
        /** Configuración global: el canal no está en la tabla (caso del {@code BPA} del sandbox). */
        GLOBAL
    }

    /**
     * Los límites ya resueltos para un envío concreto.
     *
     * @param maxWeightGrams     peso máximo por bulto; 0 = sin límite conocido, no se reparte por peso
     * @param volumetricDivisor  divisor del peso volumétrico; <b>0 = el canal no lo aplica</b>
     * @param minBillableGrams   peso mínimo que factura el canal; 0 = sin mínimo
     * @param singleParcelOnly   un bulto por envío: cada bulto va con su propia guía
     * @param origen             de cuál de los tres escalones salió (ver {@link Origen})
     */
    public record ChannelLimit(String channelCode, String countryCode, int maxWeightGrams, int volumetricDivisor,
            int minBillableGrams, int maxLengthMm, int maxWidthMm, int maxHeightMm, boolean singleParcelOnly,
            Origen origen) {

        /**
         * ¿Este canal factura por peso volumétrico? Un divisor 0 significa que NO, no que se use el
         * valor por defecto: cobrarle al cliente un volumétrico que el transportista no aplica es
         * encarecer el envío por un dato que dice justo lo contrario.
         */
        public boolean aplicaVolumetrico() {
            return volumetricDivisor > 0;
        }
    }

    /**
     * Límite de bulto del canal en ese destino, resuelto con la prioridad descrita en la clase.
     *
     * <p>Con el canal vacío ni se consulta la tabla: hay puntos del flujo —la cotización, antes de que
     * el transportista recomiende nada— donde todavía no se sabe por dónde va a ir el envío.
     */
    @Transactional(readOnly = true)
    public ChannelLimit resolve(String channelCode, String countryCode) {
        String canal = normalize(channelCode);
        String pais = normalize(countryCode);
        if (canal.isEmpty()) {
            return global(canal, pais);
        }
        if (!pais.isEmpty()) {
            Optional<CarrierChannelLimitEntity> exacta = activa(canal, pais);
            if (exacta.isPresent()) {
                return from(exacta.get(), Origen.EXACTA);
            }
        }
        Optional<CarrierChannelLimitEntity> porDefecto = activa(canal, CUALQUIER_PAIS);
        if (porDefecto.isPresent()) {
            return from(porDefecto.get(), Origen.CANAL);
        }
        // Ni excepción ni valor por defecto: el canal no está sembrado. Es el caso del sandbox.
        log.debug("Sin límites sembrados para el canal {} ({}): se usa la configuración global", canal, pais);
        return global(canal, pais);
    }

    /** La fila, si existe y está activa. Desactivarla es volver al escalón siguiente sin perder el dato. */
    private Optional<CarrierChannelLimitEntity> activa(String canal, String pais) {
        return repository.findByChannelCodeIgnoreCaseAndCountryCodeIgnoreCase(canal, pais)
                .filter(CarrierChannelLimitEntity::isActive);
    }

    private static ChannelLimit from(CarrierChannelLimitEntity e, Origen origen) {
        return new ChannelLimit(e.getChannelCode(), e.getCountryCode(), Math.max(0, e.getMaxWeightGrams()),
                Math.max(0, e.getVolumetricDivisor()), Math.max(0, e.getMinBillableGrams()),
                Math.max(0, e.getMaxLengthMm()), Math.max(0, e.getMaxWidthMm()), Math.max(0, e.getMaxHeightMm()),
                e.isSingleParcelOnly(), origen);
    }

    /**
     * El comportamiento anterior a esta tabla: el escalar de configuración. Sin medidas máximas y sin
     * mínimo facturable, porque de esos dos no había dato ninguno y no se inventa.
     */
    private ChannelLimit global(String canal, String pais) {
        return new ChannelLimit(canal, pais, Math.max(0, maxParcelWeightGramsGlobal),
                Math.max(0, volumetricDivisorGlobal), 0, 0, 0, 0, false, Origen.GLOBAL);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /* ============ Admin ============ */

    /** Todos los límites configurados, ordenados por canal y país (lo que pinta el panel). */
    @Transactional(readOnly = true)
    public List<CarrierChannelLimitEntity> listAll() {
        return repository.findAllByOrderByChannelCodeAscCountryCodeAsc();
    }

    /**
     * Alta o edición del límite de un (canal, país).
     *
     * <p><b>Todos los campos NOT NULL se rellenan aquí aunque el formulario no los mande.</b> No es
     * defensivo por gusto: el ORM nombra todas las columnas en el INSERT, así que el valor por defecto
     * de la BASE nunca llega a aplicarse y una fila nueva viajaría con nulos. Es literalmente el fallo
     * que costó una certificación en {@code CustomsValuationService.upsert} —409 al dar de alta un país,
     * mientras que editar uno existente funcionaba— y no hay motivo para repetirlo.
     */
    @Transactional
    public CarrierChannelLimitEntity upsert(CarrierChannelLimitEntity input) {
        String canal = normalize(input.getChannelCode());
        String pais = normalize(input.getCountryCode());
        CarrierChannelLimitEntity e = repository.findByChannelCodeIgnoreCaseAndCountryCodeIgnoreCase(canal, pais)
                .orElseGet(CarrierChannelLimitEntity::new);
        e.setChannelCode(canal);
        e.setCountryCode(pais);
        // Negativos a cero: un tope negativo no significa nada y «0 = sin límite» ya es el neutro.
        e.setMaxWeightGrams(Math.max(0, input.getMaxWeightGrams()));
        e.setVolumetricDivisor(Math.max(0, input.getVolumetricDivisor()));
        e.setMinBillableGrams(Math.max(0, input.getMinBillableGrams()));
        e.setMaxLengthMm(Math.max(0, input.getMaxLengthMm()));
        e.setMaxWidthMm(Math.max(0, input.getMaxWidthMm()));
        e.setMaxHeightMm(Math.max(0, input.getMaxHeightMm()));
        e.setSingleParcelOnly(input.isSingleParcelOnly());
        e.setNotes(input.getNotes());
        e.setActive(input.isActive());
        return repository.save(e);
    }

    /**
     * Baja del límite de un (canal, país). Devuelve {@code true} si había algo que borrar.
     *
     * <p>Borrar la excepción de un país NO deja al canal sin tope: la resolución cae a la fila comodín
     * del canal, que es justo lo que se quiere cuando el transportista retira una excepción.
     */
    @Transactional
    public boolean delete(String channelCode, String countryCode) {
        Optional<CarrierChannelLimitEntity> found = repository
                .findByChannelCodeIgnoreCaseAndCountryCodeIgnoreCase(normalize(channelCode), normalize(countryCode));
        found.ifPresent(repository::delete);
        return found.isPresent();
    }
}
