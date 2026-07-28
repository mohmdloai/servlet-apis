package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
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
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;

  public CustomerService(
      DSLContext rootDsl,
      CustomerRepositoryFactory repoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
  }

  /**
   * One page of a customer's orders, plus the total — the pager's two halves from one predicate.
   */
  public record CustomerOrders(List<SalesOrder> orders, long total) {}

  /**
   * A customer's order history, newest-first.
   *
   * <p>Deliberately a <b>subresource</b> rather than {@code ?customer_id=} on {@code
   * /sales-orders}, and the reason is that route's existing shape rather than taste: a bare {@code
   * GET /api/orgs/{orgId}/sales-orders} is a reserved 400 and {@code ?order_number=} returns <em>a
   * single object, not a list</em>. Adding a third mode returning a {@code PageResponse} would give
   * one URL three incompatible response shapes keyed on which parameter you passed.
   *
   * <p>An unknown customer is a <b>404</b> — a path segment names a thing — while a customer with
   * no orders is an <b>empty page</b>. That is the same distinction slice 4 drew between an unknown
   * {@code {orgId}} on the timeline (404) and an unmatched {@code ?org_id=} on the queues (empty).
   *
   * <p>Reuses {@code SalesOrderRepository.findByCustomerId}, which already existed for the portal's
   * "my orders" read with exactly this ordering — a second query would be a second definition of
   * "this customer's orders" and the two would eventually disagree.
   */
  public CustomerOrders getOrders(UUID orgId, UUID customerId, int page, int size) {
    if (page < 0) throw new ValidationException("page must be >= 0");
    if (size < 1 || size > 100) throw new ValidationException("size must be 1-100");
    getById(orgId, customerId); // 404s an unknown customer before reporting an empty history
    var repo = salesOrderRepoFactory.create(rootDsl);
    return new CustomerOrders(
        repo.findByCustomerId(orgId, customerId, page * size, size),
        repo.countByCustomerId(orgId, customerId));
  }

  public Customer getById(UUID orgId, UUID id) {
    CustomerRepository repo = repoFactory.create(rootDsl);
    return repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Customer", id));
  }

  public List<Customer> getAll(UUID orgId, int page, int size) {
    return getAll(orgId, null, page, size);
  }

  /**
   * The customer directory, optionally narrowed by {@code q} (name or email).
   *
   * <p>Whitespace-only {@code q} is treated as absent rather than as a search for spaces, and there
   * is deliberately <b>no minimum length</b> — see {@code CustomerRepositoryImpl}'s {@code
   * searchCondition} for the measurement that decided it.
   */
  public List<Customer> getAll(UUID orgId, String q, int page, int size) {
    if (page < 0) throw new ValidationException("page must be >= 0");
    if (size < 1 || size > 100) throw new ValidationException("size must be 1-100");
    CustomerRepository repo = repoFactory.create(rootDsl);
    return repo.findAll(orgId, q, page * size, size);
  }

  public long count(UUID orgId) {
    return count(orgId, null);
  }

  public long count(UUID orgId, String q) {
    CustomerRepository repo = repoFactory.create(rootDsl);
    return repo.count(orgId, q);
  }

  public Customer create(UUID orgId, String email) {
    String normalizedEmail = Text.normalizeEmail(email);
    validateEmail(normalizedEmail);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CustomerRepository repo = repoFactory.create(txDsl);

          if (repo.existsByEmail(orgId, normalizedEmail)) {
            throw new ConflictException("Email already registered in this org: " + normalizedEmail);
          }

          Customer customer = new Customer();
          customer.setOrgId(orgId);
          customer.setEmail(normalizedEmail);

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
    String normalizedEmail = Text.normalizeEmail(email);
    validateEmail(normalizedEmail);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CustomerRepository repo = repoFactory.create(txDsl);

          Customer existing =
              repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Customer", id));

          if (repo.existsByEmailAndIdNot(orgId, normalizedEmail, id)) {
            throw new ConflictException(
                "Email already used by another customer in this org: " + normalizedEmail);
          }

          existing.setEmail(normalizedEmail);

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
