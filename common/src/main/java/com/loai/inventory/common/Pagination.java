package com.loai.inventory.common;

import com.loai.inventory.common.exception.ValidationException;

/**
 * Pagination helpers shared by the service layer. The repositories take an {@code int} offset, so a
 * caller-supplied {@code page} that is large enough to make {@code page * size} exceed {@link
 * Integer#MAX_VALUE} must be rejected up front — otherwise the multiply silently overflows to a
 * negative {@code int}, which Postgres rejects as a negative {@code OFFSET} (surfacing as a 500),
 * or wraps to a wrong positive offset that serves the wrong page. This is an anonymous,
 * CDN-cacheable surface, so the failure mode is a trivially repeatable error/DoS vector.
 */
public final class Pagination {

  private Pagination() {}

  /**
   * Compute a non-overflowing {@code int} offset for {@code page}/{@code size}, validating bounds.
   *
   * @throws ValidationException if page &lt; 0, size is outside 1-100, or {@code page * size} would
   *     overflow {@code int}.
   */
  public static int offset(int page, int size) {
    if (page < 0) throw new ValidationException("page must be >= 0");
    if (size < 1 || size > 100) throw new ValidationException("size must be 1-100");
    long offset = (long) page * size;
    if (offset > Integer.MAX_VALUE) {
      throw new ValidationException("page is out of range");
    }
    return (int) offset;
  }
}
