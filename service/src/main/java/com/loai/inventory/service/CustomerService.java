package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CRM-only customer: pure data record, no authentication. Org-scoped, email unique per org. */
public class CustomerService {
  private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

  private final DSLContext rootDsl;
  private final CustomerRepositoryFactory repoFactory;

  public CustomerService(DSLContext rootDsl, CustomerRepositoryFactory repoFactory) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
  }

  public Customer getById(UUID orgId, UUID id) {
    CustomerRepository repo = repoFactory.create(rootDsl);
    return repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Customer", id));
  }

  public List<Customer> getAll(UUID orgId, int page, int size) {
    if (page < 0) throw new ValidationException("page must be >= 0");
    if (size < 1 || size > 100) throw new ValidationException("size must be 1-100");
    CustomerRepository repo = repoFactory.create(rootDsl);
    return repo.findAll(orgId, page * size, size);
  }

  public long count(UUID orgId) {
    CustomerRepository repo = repoFactory.create(rootDsl);
    return repo.count(orgId);
  }

  public Customer create(UUID orgId, String email) {
    validateEmail(email);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CustomerRepository repo = repoFactory.create(txDsl);

          if (repo.existsByEmail(orgId, email)) {
            throw new ConflictException("Email already registered in this org: " + email);
          }

          Customer customer = new Customer();
          customer.setOrgId(orgId);
          customer.setEmail(email);

          Customer saved = repo.insert(customer);
          log.info(
              "Created customer id={} orgId={} email={}",
              saved.getId(),
              saved.getOrgId(),
              saved.getEmail());
          return saved;
        });
  }

  public Customer update(UUID orgId, UUID id, String email) {
    validateEmail(email);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CustomerRepository repo = repoFactory.create(txDsl);

          Customer existing =
              repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Customer", id));

          if (repo.existsByEmailAndIdNot(orgId, email, id)) {
            throw new ConflictException(
                "Email already used by another customer in this org: " + email);
          }

          existing.setEmail(email);

          Customer updated = repo.update(existing);
          log.info("Updated customer id={} orgId={}", id, orgId);
          return updated;
        });
  }

  public void delete(UUID orgId, UUID id) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CustomerRepository repo = repoFactory.create(txDsl);

          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Customer", id));
          repo.deleteById(orgId, id);
          log.info("Deleted customer id={} orgId={}", id, orgId);
        });
  }

  private void validateEmail(String email) {
    if (email == null || email.isBlank()) {
      throw new ValidationException("email is required");
    }
  }
}
