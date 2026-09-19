package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Copia de los datos personales del usuario, para el derecho de acceso y de portabilidad.
 *
 * <p>El art. 20 del RGPD no se cumple con poder mirar el perfil en pantalla: exige entregar los datos
 * en un formato estructurado, de uso común y lectura mecánica, de modo que la persona pueda llevárselos
 * a otro servicio. De ahí que esto devuelva JSON y no una página.
 *
 * <p>Se incluye lo que el usuario aportó o generó con su uso —cuenta, direcciones, pedidos—, no las
 * inferencias internas ni los datos de otras personas. Los importes se dan tal cual se cobraron.
 */
@Service
@RequiredArgsConstructor
public class PersonalDataExportService {

    private final UserRepository userRepository;
    private final UserAddressRepository addressRepository;
    private final OrderUseCase orderUseCase;

    /** Lo que se entrega al usuario. Mapa ordenado para que el fichero se lea de arriba abajo. */
    @Transactional(readOnly = true)
    public Map<String, Object> export(UUID userId) {
        User user = userRepository.getById(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generadoEl", Instant.now().toString());
        out.put("aviso", "Copia de los datos personales asociados a tu cuenta, entregada en cumplimiento"
                + " de los artículos 15 y 20 del RGPD (derecho de acceso y portabilidad).");
        out.put("cuenta", account(user));
        out.put("direcciones", addresses(userId));
        out.put("pedidos", orders(userId));
        return out;
    }

    private Map<String, Object> account(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", user.getEmail());
        m.put("nombre", user.getFirstName());
        m.put("primerApellido", user.getLastName1());
        m.put("segundoApellido", user.getLastName2());
        m.put("nombreMostrado", user.getDisplayName());
        m.put("empresa", user.getCompanyName());
        m.put("telefono", user.getPhone());
        m.put("pais", user.getCountry());
        m.put("idioma", user.getLanguage());
        m.put("altaEl", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        m.put("ultimoAcceso", user.getLastLogin() != null ? user.getLastLogin().toString() : null);
        // La contraseña NO se exporta ni siquiera cifrada: no es un dato que el usuario aportara para
        // llevárselo, y entregar el hash sólo sirve para que alguien intente romperlo fuera de aquí.
        return m;
    }

    /** Los pedidos con lo que el usuario necesita para reclamar o llevarse su historial. */
    private List<Map<String, Object>> orders(UUID userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Order o : orderUseCase.listMyOrders(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("numero", o.getOrderNumber());
            m.put("fecha", o.getCreatedAt() != null ? o.getCreatedAt().toString() : null);
            m.put("estado", o.getStatus() != null ? o.getStatus().name() : null);
            m.put("totalCentimosUsd", o.getTotalCents());
            m.put("destinatario", o.getShippingFullName());
            m.put("destino", o.getShippingCity() + ", " + o.getShippingCountry());
            m.put("seguimiento", o.getTrackingNumber());
            List<Map<String, Object>> lineas = new ArrayList<>();
            if (o.getItems() != null) {
                for (OrderItem it : o.getItems()) {
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("producto", it.getTitleSnapshot());
                    l.put("cantidad", it.getQuantity());
                    l.put("precioUnitarioCentimosUsd", it.getUnitPriceCents());
                    lineas.add(l);
                }
            }
            m.put("lineas", lineas);
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> addresses(UUID userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (UserAddressEntity a : addressRepository.findByUser_IdOrderByIsDefaultDescCreatedAtDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nombreCompleto", a.getFullName());
            m.put("linea1", a.getLine1());
            m.put("linea2", a.getLine2());
            m.put("ciudad", a.getCity());
            m.put("provincia", a.getState());
            m.put("codigoPostal", a.getPostalCode());
            m.put("pais", a.getCountry());
            m.put("telefono", a.getPhone());
            m.put("porDefecto", a.isDefault());
            list.add(m);
        }
        return list;
    }
}
