package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.Map;

/**
 * Read-only projection model for the admin dashboard time-series buckets.
 * The inner maps keep their dynamic day-string keys (e.g. "2026-06-10") to
 * value counts/amounts. The api mapper translates it to
 * {@code AdminDashboardSeriesDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSeries {

    private Map<String, Long> ordersByDay;
    private Map<String, Long> gmvCentsByDay;
}
