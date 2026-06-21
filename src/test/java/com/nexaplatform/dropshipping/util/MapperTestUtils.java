package com.nexaplatform.dropshipping.util;

import java.lang.reflect.Field;

/**
 * Utilidad de tests para inyectar por reflexión los mappers anidados que MapStruct genera como campos
 * (cuando se usa {@code Mappers.getMapper(...)} no hay Spring que los autoinyecte). Réplica del
 * helper de {@code mic-authservice}.
 */
public final class MapperTestUtils {

    private MapperTestUtils() {
    }

    public static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No se pudo asignar el campo '" + fieldName + "'", e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
