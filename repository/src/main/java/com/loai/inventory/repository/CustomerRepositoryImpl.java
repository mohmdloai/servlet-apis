package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;

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
  public Optional<Customer> findById(UUID id) {
    return dsl.selectFrom(CUSTOMER).where(CUSTOMER.ID.eq(id)).fetchOptional().map(this::toCustomer);
  }

  @Override
  public List<Customer> findAll(int offset, int limit) {
    return dsl.selectFrom(CUSTOMER)
        .orderBy(CUSTOMER.CREATED_AT.desc())
        .offset(offset)
        .fetch()
        .map(this::toCustomer);
  }

  @Override
  public long count() {
    return dsl.fetchCount(CUSTOMER);
  }

  @Override
  public Customer insert(Customer customer) {
    CustomerRecord record =
        dsl.insertInto(CUSTOMER)
            .set(CUSTOMER.EMAIL, customer.getEmail())
            .set(CUSTOMER.PASSWORD_HASH, customer.getPasswordHash())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into customer returned no record");
    }

    log.debug("Inserted customer id={} email={}", record.getId(), record.getEmail());
    return toCustomer(record);
  }

  @Override
  public Customer update(Customer customer) {
    CustomerRecord record =
        dsl.update(CUSTOMER)
            .set(CUSTOMER.EMAIL, customer.getEmail())
            .set(CUSTOMER.PASSWORD_HASH, customer.getPasswordHash())
            .set(CUSTOMER.UPDATED_AT, OffsetDateTime.now())
            .where(CUSTOMER.ID.eq(customer.getId()))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("UPDATE customer returned no record");
    }

    log.debug("Updated customer id={}", record.getId());
    return toCustomer(record);
  }

  @Override
  public void deleteById(UUID id) {
    dsl.deleteFrom(CUSTOMER).where(CUSTOMER.ID.eq(id)).execute();
  }

  @Override
  public boolean existsByEmail(String email) {
    return dsl.fetchExists(dsl.selectOne().from(CUSTOMER).where(CUSTOMER.EMAIL.eq(email)));
  }

  @Override
  public boolean existsByEmailAndIdNot(String email, UUID excludeId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(CUSTOMER)
            .where(CUSTOMER.EMAIL.eq(email).and(CUSTOMER.ID.ne(excludeId))));
  }
  private Customer toCustomer(CustomerRecord r){
    return new Customer(
      r.getId(), r.getEmail(), r.getPasswordHash(),r.getCreatedAt(), r.getUpdatedAt()
    );
  }
}
