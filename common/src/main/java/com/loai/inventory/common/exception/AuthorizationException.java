package com.loai.inventory.common.exception;

public class AuthorizationException extends AppException {
  public AuthorizationException(String message) {
    super(403, message);
  }
}
