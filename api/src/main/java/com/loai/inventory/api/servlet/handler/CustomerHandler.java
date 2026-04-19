package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CreateCustomerRequest;
import com.loai.inventory.api.dto.CustomerResponse;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.UpdateCustomerRequest;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CustomerHandler {

  private static final Logger log = LoggerFactory.getLogger(CustomerHandler.class);

  private final CustomerService customerService;
  private final ObjectMapper mapper;

  public CustomerHandler(CustomerService customerService, ObjectMapper mapper) {
    this.customerService = customerService;
    this.mapper = mapper;
  }

  public void handle(
      String method,
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID orgId,
      String remainingPath)
      throws IOException {
    try {
      UUID customerId = parseId(remainingPath);

      switch (method) {
        case "GET" -> doGet(req, resp, orgId, customerId);
        case "POST" -> doPost(req, resp, orgId, customerId);
        case "PUT" -> doPut(req, resp, orgId, customerId);
        case "DELETE" -> doDelete(req, resp, orgId, customerId);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/customers{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID customerId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);

    if (customerId == null) {
      int page = intParam(req, "page", 0);
      int size = intParam(req, "size", 10);
      List<Customer> customers = customerService.getAll(orgId, page, size);
      long total = customerService.count(orgId);
      List<CustomerResponse> data = customers.stream().map(CustomerResponse::from).toList();
      writeJson(resp, 200, new PageResponse<>(data, total, page, size));
    } else {
      Customer customer = customerService.getById(orgId, customerId);
      writeJson(resp, 200, CustomerResponse.from(customer));
    }
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID customerId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    if (customerId != null) {
      throw new ValidationException("POST does not accept a customer id in the path");
    }

    CreateCustomerRequest body = readBody(req, CreateCustomerRequest.class);
    Customer created = customerService.create(orgId, body.getEmail());
    writeJson(resp, 201, CustomerResponse.from(created));
  }

  private void doPut(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID customerId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    if (customerId == null) {
      throw new ValidationException("Customer id is required for update");
    }

    UpdateCustomerRequest body = readBody(req, UpdateCustomerRequest.class);
    Customer updated = customerService.update(orgId, customerId, body.getEmail());
    writeJson(resp, 200, CustomerResponse.from(updated));
  }

  private void doDelete(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID customerId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    if (customerId == null) {
      throw new ValidationException("Customer id is required for delete");
    }
    customerService.delete(orgId, customerId);
    resp.setStatus(204);
  }

  private UUID parseId(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || remainingPath.equals("/")) {
      return null;
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid customer id format: " + raw);
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    return mapper.readValue(req.getInputStream(), type);
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }

  private int intParam(HttpServletRequest req, String name, int defaultValue) {
    String value = req.getParameter(name);
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ValidationException("Parameter '" + name + "' must be an integer");
    }
  }
}
