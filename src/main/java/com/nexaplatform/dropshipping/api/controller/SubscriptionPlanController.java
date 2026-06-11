package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.SubscriptionPlanApi;
import com.nexaplatform.dropshipping.api.dto.in.SubscriptionPlanDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.SubscriptionPlanDtoOut;
import com.nexaplatform.dropshipping.api.mapper.SubscriptionPlanDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.SubscriptionPlanUseCase;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Subscription Plans controller. Pure implementation of {@link SubscriptionPlanApi}:
 * no routing/documentation annotations here (they live on the interface), no
 * business logic — maps DtoIn -> domain -> DtoOut and delegates to the use case.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/subscription-plans")
public class SubscriptionPlanController implements SubscriptionPlanApi {

    private final SubscriptionPlanDtoMapper mapper;
    private final SubscriptionPlanUseCase useCase;

    @Override
    public ResponseEntity<SubscriptionPlanDtoOut> create(SubscriptionPlanDtoIn dto) {
        SubscriptionPlan model = useCase.save(mapper.toDomain(dto));
        return new ResponseEntity<>(mapper.toDtoOut(model), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<SubscriptionPlanDtoOut> update(SubscriptionPlanDtoIn dto, UUID id) {
        SubscriptionPlan model = useCase.update(mapper.toDomain(dto), id);
        return new ResponseEntity<>(mapper.toDtoOut(model), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<SubscriptionPlanDtoOut> getById(UUID id) {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.getById(id)), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<SubscriptionPlanDtoOut>> findAll() {
        return new ResponseEntity<>(mapper.toDtoOutList(useCase.findAll()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        useCase.delete(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
