package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.in.CartSuggestionsDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CartSuggestionDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CartSuggestionsDtoOut;
import com.nexaplatform.dropshipping.application.service.CartSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Qué añadir al carrito sin pagar más aduana ni apenas más envío. */
@RestController
@RequestMapping("/api/catalog/cart-suggestions")
@RequiredArgsConstructor
@Tag(name = "Catálogo", description = "Sugerencias que abaratan el envío y el arancel")
public class CartSuggestionController {

    private final CartSuggestionService suggestions;

    @PostMapping
    @Operation(summary = "Productos que no suman arancel y apenas suman envío al carrito actual")
    public ResponseEntity<CartSuggestionsDtoOut> suggest(@Valid @RequestBody CartSuggestionsDtoIn body) {
        List<CartSuggestionService.Linea> carrito = body.getItems().stream()
                .map(l -> new CartSuggestionService.Linea(l.getProductId(), l.getVariantId(), l.getQuantity()))
                .toList();
        CartSuggestionService.Sugerencias resultado = suggestions.para(carrito, body.getLang());
        return ResponseEntity.ok(CartSuggestionsDtoOut.builder().gramosLibres(resultado.gramosLibres())
                .otroBultoFormatted(resultado.otroBultoFormatted())
                .items(resultado.items().stream()
                        .map(s -> CartSuggestionDtoOut.builder().id(s.id()).slug(s.slug()).title(s.title())
                                .image(s.image()).dutyExtraFormatted(s.dutyExtraFormatted())
                                .shippingExtraFormatted(s.shippingExtraFormatted())
                                .shippingAloneFormatted(s.shippingAloneFormatted()).motivo(s.motivo()).build())
                        .toList())
                .build());
    }
}
