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


public class CustomerService {
  private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

  private final DSLContext rootDsl;
  private final CustomerRepositoryFactory repoFactory;

  public CustomerService( DSLContext rootDsl, CustomerRepositoryFactory repoFactory){
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
  }

  public Customer getById(UUID id) {
    CustomerRepository repo = repoFactory.create(rootDsl);
    return repo.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));
    }
  public List<Customer> getAll(int page, int size) {
    if (page < 0) throw new ValidationException("page must be >= 0");
    if (size < 1 || size > 100) throw new ValidationException("size must be 1-100");
    CustomerRepository repo = repoFactory.create(rootDsl);
    return repo.findAll(page * size, size);
  }

  public long count() {
    CustomerRepository repo = repoFactory.create(rootDsl);
    return repo.count();
  }
  // Transactional commands:
  public Customer create(String email, String passwordHash) {
    validateCreate(email, passwordHash);

    return rootDsl.transactionResult(cfg -> {
      DSLContext txDsl = DSL.using(cfg);
      CustomerRepository repo = repoFactory.create(txDsl);

      if (repo.existsByEmail(email)) {
        throw new ConflictException("Email already registered: " + email);
      }

      Customer customer = new Customer();
      customer.setEmail(email);
      customer.setPasswordHash(passwordHash);

      Customer saved = repo.insert(customer);
      log.info("Created customer id={} email={}", saved.getId(), saved.getEmail());
      return saved;
    });
  }

  public Customer update(UUID id, String email, String passwordHash) {
    validateCreate(email, passwordHash);

    return rootDsl.transactionResult(cfg -> {
      DSLContext txDsl = DSL.using(cfg);
      CustomerRepository repo = repoFactory.create(txDsl);

      Customer existing =
          repo.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));

      if (repo.existsByEmailAndIdNot(email, id)) {
        throw new ConflictException("Email already used by another customer: " + email);
      }

      existing.setEmail(email);
      existing.setPasswordHash(passwordHash);

      Customer updated = repo.update(existing);
      log.info("Updated customer id={}", id);
      return updated;
    });
  }

  public void delete(UUID id) {
    rootDsl.transaction(cfg -> {
      DSLContext txDsl = DSL.using(cfg);
      CustomerRepository repo = repoFactory.create(txDsl);

      repo.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));
      repo.deleteById(id);
      log.info("Deleted customer id={}", id);
    });
  }

  // Validate
  private void validateCreate(String email, String passwordHash) {
    if (email == null || email.isBlank()) {
      throw new ValidationException("email is required");
    }
    if (passwordHash == null || passwordHash.isBlank()) {
      throw new ValidationException("passwordHash is required");
    }
  }
}
