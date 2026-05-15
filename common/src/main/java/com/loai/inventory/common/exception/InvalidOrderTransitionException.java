package com.loai.inventory.common.exception;

public class InvalidOrderTransitionException extends ConflictException {
  public InvalidOrderTransitionException(String message) {
    super(message);
  }
}
