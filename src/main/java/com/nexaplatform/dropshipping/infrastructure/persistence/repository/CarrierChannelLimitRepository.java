package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CarrierChannelLimitEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repo de los límites de bulto por canal y país. */
public interface CarrierChannelLimitRepository extends JpaRepository<CarrierChannelLimitEntity, UUID> {

    Optional<CarrierChannelLimitEntity> findByChannelCodeIgnoreCaseAndCountryCodeIgnoreCase(
            String channelCode, String countryCode);

    List<CarrierChannelLimitEntity> findAllByOrderByChannelCodeAscCountryCodeAsc();
}
