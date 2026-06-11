package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Time-series buckets for the admin dashboard. The outer field names preserve the
 * exact JSON keys previously emitted by the controller; the inner maps keep their
 * dynamic day-string keys (e.g. "2026-06-10") to value counts/amounts.
 */
@Value
@Builder
public class AdminDashboardSeriesDtoOut {

    Map<String, Long> ordersByDay;
    Map<String, Long> gmvCentsByDay;
}
