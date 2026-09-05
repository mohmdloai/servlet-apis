package com.loai.inventory.common.exception;

/**
 * A counter sale or cash refund was attempted with no open cash shift while the org has {@code
 * shift_required} on ({@code stories/cash_shift.md}). A 409 whose envelope carries {@link #KIND},
 * so the client opens the shift sheet instead of showing a red error. Nothing is written.
 */
public class ShiftRequiredException extends ConflictException {

  public static final String KIND = "SHIFT_REQUIRED";

  public ShiftRequiredException() {
    super("open a cash shift before selling: this org requires one");
  }
}
