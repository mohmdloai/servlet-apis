package com.loai.inventory.common.exception;

/**
 * Thrown when a {@code InventoryReservation} mutator is invoked from a state that does not permit
 * it (e.g. releasing a reservation that is not {@code ACTIVE}). Mirrors {@link
 * InvalidOrderTransitionException} for the reservation child entity. Maps to HTTP 409.
 */
public class InvalidReservationTransitionException extends ConflictException {
  public InvalidReservationTransitionException(String message) {
    super(message);
  }
}
