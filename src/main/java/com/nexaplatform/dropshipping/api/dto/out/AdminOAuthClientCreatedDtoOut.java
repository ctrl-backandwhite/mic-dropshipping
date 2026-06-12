package com.nexaplatform.dropshipping.api.dto.out;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Returned once on create/rotate: includes the plaintext secret (shown only this time). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminOAuthClientCreatedDtoOut {

    private String id;
    private String clientId;
    private String clientSecret;
    private String name;
    private String message;
}
