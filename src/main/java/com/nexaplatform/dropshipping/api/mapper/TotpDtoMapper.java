package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.TotpBackupCodesDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpEnableDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpSetupDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TotpStatusDtoOut;
import com.nexaplatform.dropshipping.domain.model.TotpSetup;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the TOTP endpoints: translates the use-case domain values
 * into the transport DtoOuts. Injected in the {@code TotpController}.
 */
@Mapper(componentModel = "spring")
public interface TotpDtoMapper {

    @Mapping(target = "base32Secret", source = "base32Secret")
    @Mapping(target = "otpauthUrl", source = "otpauthUrl")
    TotpSetupDtoOut toSetupDtoOut(TotpSetup model);

    default TotpEnableDtoOut toEnableDtoOut(List<String> backupCodes) {
        return TotpEnableDtoOut.builder().backupCodes(backupCodes).build();
    }

    default TotpBackupCodesDtoOut toBackupCodesDtoOut(List<String> backupCodes) {
        return TotpBackupCodesDtoOut.builder().backupCodes(backupCodes).build();
    }

    default TotpStatusDtoOut toStatusDtoOut(boolean enabled) {
        return TotpStatusDtoOut.builder().enabled(enabled).build();
    }
}
