package com.nexaplatform.dropshipping.infrastructure.campaign;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mapa país (ISO-3166 alfa-2) → zona horaria representativa, para enviar campañas a una hora local
 * conveniente en cada país. Para países con varias zonas se elige una representativa. Compartido por
 * las campañas programadas (novedades 18:00, recordatorio de carrito 14:00).
 */
public final class CountryTimeZones {

    private CountryTimeZones() {
    }

    private static final Map<String, ZoneId> ZONES = Map.ofEntries(
            Map.entry("ES", ZoneId.of("Europe/Madrid")), Map.entry("PT", ZoneId.of("Europe/Lisbon")),
            Map.entry("FR", ZoneId.of("Europe/Paris")), Map.entry("DE", ZoneId.of("Europe/Berlin")),
            Map.entry("IT", ZoneId.of("Europe/Rome")), Map.entry("NL", ZoneId.of("Europe/Amsterdam")),
            Map.entry("GB", ZoneId.of("Europe/London")), Map.entry("IE", ZoneId.of("Europe/Dublin")),
            Map.entry("BE", ZoneId.of("Europe/Brussels")), Map.entry("CH", ZoneId.of("Europe/Zurich")),
            Map.entry("AT", ZoneId.of("Europe/Vienna")), Map.entry("PL", ZoneId.of("Europe/Warsaw")),
            Map.entry("US", ZoneId.of("America/New_York")), Map.entry("CA", ZoneId.of("America/Toronto")),
            Map.entry("MX", ZoneId.of("America/Mexico_City")), Map.entry("CO", ZoneId.of("America/Bogota")),
            Map.entry("AR", ZoneId.of("America/Argentina/Buenos_Aires")), Map.entry("BR", ZoneId.of("America/Sao_Paulo")),
            Map.entry("CL", ZoneId.of("America/Santiago")), Map.entry("PE", ZoneId.of("America/Lima")),
            Map.entry("EC", ZoneId.of("America/Guayaquil")), Map.entry("VE", ZoneId.of("America/Caracas")),
            Map.entry("UY", ZoneId.of("America/Montevideo")), Map.entry("BO", ZoneId.of("America/La_Paz")),
            Map.entry("PY", ZoneId.of("America/Asuncion")), Map.entry("CR", ZoneId.of("America/Costa_Rica")),
            Map.entry("PA", ZoneId.of("America/Panama")), Map.entry("DO", ZoneId.of("America/Santo_Domingo")),
            Map.entry("GT", ZoneId.of("America/Guatemala")));

    /** Zona por defecto para países sin mapear (evita quedar fuera de toda campaña). */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Madrid");

    public static ZoneId zoneOf(String country) {
        if (country == null) {
            return DEFAULT_ZONE;
        }
        return ZONES.getOrDefault(country.trim().toUpperCase(), DEFAULT_ZONE);
    }

    /** Devuelve los países cuya hora local (según su zona) coincide ahora mismo con {@code hour} (0-23). */
    public static Set<String> countriesAtLocalHour(int hour) {
        return ZONES.entrySet().stream()
                .filter(e -> ZonedDateTime.now(e.getValue()).getHour() == hour)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    /** Todos los países con zona mapeada (para envíos manuales sin filtro horario). */
    public static Set<String> allCountries() {
        return ZONES.keySet();
    }

    /** True si en la zona del país es ahora la hora local indicada. */
    public static boolean isLocalHour(String country, int hour) {
        return ZonedDateTime.now(zoneOf(country)).getHour() == hour;
    }

    /** Hora local actual del país (para logs/decisiones). */
    public static LocalTime localTime(String country) {
        return ZonedDateTime.now(zoneOf(country)).toLocalTime();
    }
}
