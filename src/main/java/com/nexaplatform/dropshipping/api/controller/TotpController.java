package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.TotpApi;
import com.nexaplatform.dropshipping.api.dto.in.TotpDisableDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.TotpVerifyDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.TotpBackupCodesDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpEnableDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpSetupDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpStatusDtoOut;
import com.nexaplatform.dropshipping.application.service.TotpService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * TOTP two-factor authentication controller. Pure implementation of
 * {@link TotpApi}: no business logic and no manual mapping — delegates to
 * {@link TotpService} and wraps the result in a {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/me/2fa")
@RequiredArgsConstructor
public class TotpController implements TotpApi {

    private final TotpService totp;

    @Override
    public ResponseEntity<TotpSetupDtoOut> setup(Authentication auth) {
        return new ResponseEntity<>(totp.setupDto(UUID.fromString(auth.getName())), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<TotpEnableDtoOut> verifyAndEnable(Authentication auth, TotpVerifyDtoIn req) {
        return new ResponseEntity<>(totp.verifyAndEnableDto(UUID.fromString(auth.getName()), req.getOtp()),
                HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> disable(Authentication auth, TotpDisableDtoIn req) {
        totp.disableWithPassword(UUID.fromString(auth.getName()), req.getPassword());
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Override
    public ResponseEntity<TotpBackupCodesDtoOut> regenerate(Authentication auth) {
        return new ResponseEntity<>(totp.regenerateBackupCodesDto(UUID.fromString(auth.getName())), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<TotpStatusDtoOut> status(Authentication auth) {
        return new ResponseEntity<>(totp.status(UUID.fromString(auth.getName())), HttpStatus.OK);
    }
}
