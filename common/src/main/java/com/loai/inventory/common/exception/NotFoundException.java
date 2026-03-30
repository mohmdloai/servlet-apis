package com.loai.inventory.common.exception;

import java.util.UUID;

public class NotFoundException extends AppException {
  public NotFoundException(String entity, UUID id) {
    super(404, entity + " not found: " + id);
  }

  public NotFoundException(String message) {
    super(404, message);
  }
}
