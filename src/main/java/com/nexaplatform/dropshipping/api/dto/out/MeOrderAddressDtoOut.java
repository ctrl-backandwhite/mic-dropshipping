package com.nexaplatform.dropshipping.api.dto.out;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AddressEntity;
import lombok.Builder;
import lombok.Value;

/**
 * Address block of the authenticated user's order detail. Field names mirror the
 * legacy {@code OrderAddressView} record the controller exposed.
 */
@Value
@Builder
public class MeOrderAddressDtoOut {

    String fullName;
    String phone;
    String email;
    String line1;
    String line2;
    String city;
    String state;
    String postalCode;
    String country;

    public static MeOrderAddressDtoOut from(AddressEntity a) {
        if (a == null)
            return null;
        return MeOrderAddressDtoOut.builder().fullName(a.getFullName()).phone(a.getPhone()).email(a.getEmail())
                .line1(a.getLine1()).line2(a.getLine2()).city(a.getCity()).state(a.getState())
                .postalCode(a.getPostalCode()).country(a.getCountry()).build();
    }
}
