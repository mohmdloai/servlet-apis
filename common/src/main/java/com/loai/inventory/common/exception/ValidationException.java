package com.loai.inventory.common.exception;

public class ValidationException extends AppException {
  public ValidationException(String message) {
    super(400, message);
  }
}
