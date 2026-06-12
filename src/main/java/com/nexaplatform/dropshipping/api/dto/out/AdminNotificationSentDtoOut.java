package com.nexaplatform.dropshipping.api.dto.out;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Result of an admin notification send: how many recipients were notified. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminNotificationSentDtoOut {

    private int sent;
}
