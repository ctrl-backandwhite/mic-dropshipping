package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.PodAiResult;
import com.nexaplatform.dropshipping.domain.model.PodBlankProduct;
import com.nexaplatform.dropshipping.domain.model.PodDesign;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the print-on-demand aggregate (DROP-6). Operates on the
 * domain models ({@link PodDesign}, {@link PodBlankProduct}, {@link PodAiResult}).
 * Design operations are scoped to the owning {@code userId}.
 */
public interface PodDesignUseCase {

    /** Lists the POD blank-product cards in the requested language. */
    List<PodBlankProduct> blanks(String lang);

    /** Creates a POD design for the user (renders a mockup). */
    PodDesign create(UUID userId, PodDesign model);

    /** Lists the user's POD designs, newest first. */
    List<PodDesign> myDesigns(UUID userId);

    /** Generates a POD design mockup via a (mocked) AI provider. */
    PodAiResult aiGenerate(String prompt);

    /** Deletes one of the user's designs (404 if not owned). */
    void deleteDesign(UUID userId, UUID id);

    /** Renames one of the user's designs (404 if not owned). */
    PodDesign renameDesign(UUID userId, UUID id, String name);
}
