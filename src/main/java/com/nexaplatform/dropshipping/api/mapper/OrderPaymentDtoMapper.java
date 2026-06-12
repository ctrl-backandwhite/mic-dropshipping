package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import com.nexaplatform.dropshipping.domain.model.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper projecting a {@link Payment} domain model into the order-payment
 * transport DtoOut (shared by the partner B2B and customer B2C endpoints). Injected
 * in the controllers. {@code clientSecret}/{@code approveUrl} are read from the
 * provider-response metadata; the crypto fields come from the payment columns.
 */
@Mapper(componentModel = "spring")
public interface OrderPaymentDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "method", expression = "java(model.getMethod() != null ? model.getMethod().name() : null)")
    @Mapping(target = "status", expression = "java(model.getStatus() != null ? model.getStatus().name() : null)")
    @Mapping(target = "amountUsdCents", source = "amountUsdCents")
    @Mapping(target = "amountDisplay", source = "amountDisplay")
    @Mapping(target = "currencyDisplay", source = "currencyDisplay")
    @Mapping(target = "provider", source = "provider")
    @Mapping(target = "providerRef", source = "providerRef")
    @Mapping(target = "clientSecret", expression = "java(meta(model, \"clientSecret\"))")
    @Mapping(target = "approveUrl", expression = "java(meta(model, \"approveUrl\"))")
    @Mapping(target = "cryptoAddress", source = "cryptoAddress")
    @Mapping(target = "cryptoChain", source = "cryptoChain")
    @Mapping(target = "qrUrl", source = "qrUrl")
    @Mapping(target = "cryptoExpiresAt", source = "cryptoExpiresAt")
    @Mapping(target = "createdAt", source = "createdAt")
    OrderPaymentDtoOut toDtoOut(Payment model);

    List<OrderPaymentDtoOut> toDtoOutList(List<Payment> models);

    /** Reads a string value from the payment provider-response metadata map. */
    default String meta(Payment model, String key) {
        if (model == null || model.getProviderResponse() == null) {
            return null;
        }
        Object v = model.getProviderResponse().get(key);
        return v != null ? v.toString() : null;
    }
}
