package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.repository.generated.tables.records.CustomerRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CustomerRepositoryImpl implements CustomerRepository {
  private static final Logger log = LoggerFactory.getLogger(CustomerRepositoryImpl.class);
  private final DSLContext dsl;

  public CustomerRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<Customer> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(CUSTOMER)
        .where(CUSTOMER.ORG_ID.eq(orgId).and(CUSTOMER.ID.eq(id)))
        .fetchOptional()
        .map(this::toCustomer);
  }

  @Override
  public List<Customer> findAll(UUID orgId, int offset, int limit) {
    return dsl.selectFrom(CUSTOMER)
        .where(CUSTOMER.ORG_ID.eq(orgId))
        .orderBy(CUSTOMER.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toCustomer);
  }

  @Override
  public long count(UUID orgId) {
    return dsl.fetchCount(dsl.selectFrom(CUSTOMER).where(CUSTOMER.ORG_ID.eq(orgId)));
  }

  @Override
  public Customer insert(Customer customer) {
    CustomerRecord record =
        dsl.insertInto(CUSTOMER)
            .set(CUSTOMER.ORG_ID, customer.getOrgId())
            .set(CUSTOMER.EMAIL, customer.getEmail())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into customer returned no record");
    }

    log.debug(
        "Inserted customer id={} orgId={} email={}",
        record.getId(),
        record.getOrgId(),
        record.getEmail());
    return toCustomer(record);
  }

  @Override
  public Customer update(Customer customer) {
    CustomerRecord record =
        dsl.update(CUSTOMER)
            .set(CUSTOMER.EMAIL, customer.getEmail())
            .set(CUSTOMER.UPDATED_AT, OffsetDateTime.now())
            .where(CUSTOMER.ORG_ID.eq(customer.getOrgId()).and(CUSTOMER.ID.eq(customer.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("Customer", customer.getId());
    }

    log.debug("Updated customer id={}", record.getId());
    return toCustomer(record);
  }

  @Override
  public void deleteById(UUID orgId, UUID id) {
    int deleted =
        dsl.deleteFrom(CUSTOMER).where(CUSTOMER.ORG_ID.eq(orgId).and(CUSTOMER.ID.eq(id))).execute();
    if (deleted == 0) {
      throw new NotFoundException("Customer", id);
    }
  }

  @Override
  public boolean existsByEmail(UUID orgId, String email) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(CUSTOMER)
            .where(CUSTOMER.ORG_ID.eq(orgId).and(CUSTOMER.EMAIL.eq(email))));
  }

  @Override
  public boolean existsByEmailAndIdNot(UUID orgId, String email, UUID excludeId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(CUSTOMER)
            .where(
                CUSTOMER
                    .ORG_ID
                    .eq(orgId)
                    .and(CUSTOMER.EMAIL.eq(email))
                    .and(CUSTOMER.ID.ne(excludeId))));
  }

  private Customer toCustomer(CustomerRecord r) {
    return new Customer(r.getId(), r.getOrgId(), r.getEmail(), r.getCreatedAt(), r.getUpdatedAt());
  }
}
