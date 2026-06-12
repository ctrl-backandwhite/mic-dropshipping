package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Result of uploading an image file: the public URL to store on a product/variant. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageUploadDtoOut {

    @Schema(description = "Public URL of the uploaded image")
    private String url;
}
