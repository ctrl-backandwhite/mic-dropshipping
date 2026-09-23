package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * La descripción genérica con la que se declarará toda una terna del catálogo.
 *
 * @param ename obligatorio: es el {@code EName} de la declaración y sin él el transportista rechaza la
 *              guía
 * @param cname el {@code CName}; opcional aquí porque un grupo puede quedarse a medio redactar, pero
 *              solo se transmite si lleva ideogramas
 */
public record AdminDeclarationGroupUpdateDtoIn(@NotBlank @Size(max = 512) String ename, @Size(max = 512) String cname) {
}
