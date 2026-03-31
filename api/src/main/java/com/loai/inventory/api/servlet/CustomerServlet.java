package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.*;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.service.CustomerService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@WebServlet("/api/customers/*")
public class CustomerServlet extends HttpServlet {

  private CustomerService customerService;
  private ObjectMapper mapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute("config");
    this.customerService = config.customerService;
    this.mapper = config.objectMapper;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      UUID id = extractId(req);
      if (id == null) {
        int page = intParam(req, "page", 0);
        int size = intParam(req, "size", 10);
        List<Customer> customers = customerService.getAll(page, size);
        long total = customerService.count();
        List<CustomerResponse> items = customers.stream().map(CustomerResponse::from).toList();
        writeJson(resp, 200, new PageResponse<>(items, total, page, size));
      } else {
        Customer customer = customerService.getById(id);
        writeJson(resp, 200, CustomerResponse.from(customer));
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, e);
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      CreateCustomerRequest body = readBody(req, CreateCustomerRequest.class);
      Customer created = customerService.create(body.getEmail(), body.getPasswordHash());
      writeJson(resp, 201, CustomerResponse.from(created));
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, e);
    }
  }

  @Override
  protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      UUID id = extractId(req);
      if (id == null) throw new ValidationException("PUT requires /api/customers/{id}");
      UpdateCustomerRequest body = readBody(req, UpdateCustomerRequest.class);
      Customer updated = customerService.update(id, body.getEmail(), body.getPasswordHash());
      writeJson(resp, 200, CustomerResponse.from(updated));
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, e);
    }
  }

  @Override
  protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      UUID id = extractId(req);
      if (id == null) throw new ValidationException("DELETE requires /api/customers/{id}");
      customerService.delete(id);
      resp.setStatus(204);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, e);
    }
  }

  // Helpers

  private UUID extractId(HttpServletRequest req) {
    String path = req.getPathInfo();
    if (path == null || path.equals("/")) return null;
    try {
      return UUID.fromString(path.substring(1));
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid UUID: " + path.substring(1));
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

  private void writeError(HttpServletResponse resp, Exception e) throws IOException {
    writeJson(resp, 500, ApiError.of(500, "Internal server error"));
  }

  private int intParam(HttpServletRequest req, String name, int defaultValue) {
    String val = req.getParameter(name);
    return val == null ? defaultValue : Integer.parseInt(val);
  }
}
