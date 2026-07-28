package com.loai.inventory.domain.model;

/**
 * The growth series' two time-bucket sizes (slice 8 of the platform console, {@code
 * stories/platform_growth_series.md}). Deliberately no {@code DAY}: tenant-lifecycle events are too
 * sparse for day buckets to read as anything but noise, and a {@code day|week|month} triple would
 * invite the reports comparison this endpoint must not make — revisit only when a single day
 * genuinely means something (a launch, a campaign).
 *
 * <p>Bucket edges are Postgres {@code date_trunc} in <strong>UTC</strong>, the reports dialect
 * verbatim ({@code stories/reporting_reads.md}); {@code WEEK} therefore starts on Monday.
 */
public enum PlatformGrowthBucket {
  WEEK,
  MONTH
}
