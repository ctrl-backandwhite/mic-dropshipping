package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.usecase.impl.ShippingUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.CarbonFootprint;
import com.nexaplatform.dropshipping.domain.model.ShippingRate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ShippingUseCaseImplTest {

    @InjectMocks ShippingUseCaseImpl useCase;

    @Test
    void calculate_returnsFourCarrierRows() {
        List<ShippingRate> rows = useCase.calculate(1000, 2);

        assertThat(rows).extracting(ShippingRate::getMethod)
                .containsExactly("STANDARD", "EXPRESS", "AIR", "SEA");
    }

    @Test
    void carbonFootprint_reportsSeaAsGreenest() {
        CarbonFootprint esg = useCase.carbonFootprint(1000, 1);

        assertThat(esg.getGreenestMethod()).isEqualTo("SEA");
        assertThat(esg.getCarbonKg()).isNotNull();
    }
}
