package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Adds an image to a product by URL (typed manually or returned by the upload endpoint). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddProductImageDtoIn {

    @NotBlank
    private String url;

    /** "MAIN" or "GALLERY"; defaults to GALLERY (MAIN if it's the first image). */
    private String role;
}
