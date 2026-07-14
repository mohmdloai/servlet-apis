package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER_ADDRESS;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.CustomerAddress;
import com.loai.inventory.domain.repository.CustomerAddressRepository;
import com.loai.inventory.repository.generated.tables.records.CustomerAddressRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CustomerAddressRepositoryImpl implements CustomerAddressRepository {
  private static final Logger log = LoggerFactory.getLogger(CustomerAddressRepositoryImpl.class);
  private final DSLContext dsl;

  public CustomerAddressRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<CustomerAddress> findByCustomerId(UUID orgId, UUID customerId) {
    return dsl.selectFrom(CUSTOMER_ADDRESS)
        .where(CUSTOMER_ADDRESS.ORG_ID.eq(orgId).and(CUSTOMER_ADDRESS.CUSTOMER_ID.eq(customerId)))
        .orderBy(
            CUSTOMER_ADDRESS.IS_DEFAULT.desc(),
            CUSTOMER_ADDRESS.CREATED_AT.desc(),
            CUSTOMER_ADDRESS.ID.desc())
        .fetch()
        .map(this::toAddress);
  }

  @Override
  public Optional<CustomerAddress> findById(UUID orgId, UUID customerId, UUID id) {
    return dsl.selectFrom(CUSTOMER_ADDRESS)
        .where(
            CUSTOMER_ADDRESS
                .ORG_ID
                .eq(orgId)
                .and(CUSTOMER_ADDRESS.CUSTOMER_ID.eq(customerId))
                .and(CUSTOMER_ADDRESS.ID.eq(id)))
        .fetchOptional()
        .map(this::toAddress);
  }

  @Override
  public long countByCustomerId(UUID orgId, UUID customerId) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(CUSTOMER_ADDRESS)
            .where(
                CUSTOMER_ADDRESS
                    .ORG_ID
                    .eq(orgId)
                    .and(CUSTOMER_ADDRESS.CUSTOMER_ID.eq(customerId))));
  }

  @Override
  public CustomerAddress insert(CustomerAddress a) {
    CustomerAddressRecord record =
        dsl.insertInto(CUSTOMER_ADDRESS)
            .set(CUSTOMER_ADDRESS.ORG_ID, a.getOrgId())
            .set(CUSTOMER_ADDRESS.CUSTOMER_ID, a.getCustomerId())
            .set(CUSTOMER_ADDRESS.LABEL, a.getLabel())
            .set(CUSTOMER_ADDRESS.RECIPIENT, a.getRecipient())
            .set(CUSTOMER_ADDRESS.PHONE, a.getPhone())
            .set(CUSTOMER_ADDRESS.ADDRESS, a.getAddress())
            .set(CUSTOMER_ADDRESS.IS_DEFAULT, a.isDefault())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into customer_address returned no record");
    }
    log.debug(
        "Inserted customer_address id={} orgId={} customerId={}",
        record.getId(),
        record.getOrgId(),
        record.getCustomerId());
    return toAddress(record);
  }

  @Override
  public CustomerAddress update(CustomerAddress a) {
    CustomerAddressRecord record =
        dsl.update(CUSTOMER_ADDRESS)
            .set(CUSTOMER_ADDRESS.LABEL, a.getLabel())
            .set(CUSTOMER_ADDRESS.RECIPIENT, a.getRecipient())
            .set(CUSTOMER_ADDRESS.PHONE, a.getPhone())
            .set(CUSTOMER_ADDRESS.ADDRESS, a.getAddress())
            .set(CUSTOMER_ADDRESS.UPDATED_AT, OffsetDateTime.now())
            .where(
                CUSTOMER_ADDRESS
                    .ORG_ID
                    .eq(a.getOrgId())
                    .and(CUSTOMER_ADDRESS.CUSTOMER_ID.eq(a.getCustomerId()))
                    .and(CUSTOMER_ADDRESS.ID.eq(a.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("CustomerAddress", a.getId());
    }
    log.debug("Updated customer_address id={}", record.getId());
    return toAddress(record);
  }

  @Override
  public void deleteById(UUID orgId, UUID customerId, UUID id) {
    int deleted =
        dsl.deleteFrom(CUSTOMER_ADDRESS)
            .where(
                CUSTOMER_ADDRESS
                    .ORG_ID
                    .eq(orgId)
                    .and(CUSTOMER_ADDRESS.CUSTOMER_ID.eq(customerId))
                    .and(CUSTOMER_ADDRESS.ID.eq(id)))
            .execute();
    if (deleted == 0) {
      throw new NotFoundException("CustomerAddress", id);
    }
  }

  @Override
  public void clearDefault(UUID orgId, UUID customerId) {
    dsl.update(CUSTOMER_ADDRESS)
        .set(CUSTOMER_ADDRESS.IS_DEFAULT, false)
        .set(CUSTOMER_ADDRESS.UPDATED_AT, OffsetDateTime.now())
        .where(
            CUSTOMER_ADDRESS
                .ORG_ID
                .eq(orgId)
                .and(CUSTOMER_ADDRESS.CUSTOMER_ID.eq(customerId))
                .and(CUSTOMER_ADDRESS.IS_DEFAULT.isTrue()))
        .execute();
  }

  @Override
  public void setDefault(UUID orgId, UUID customerId, UUID id) {
    int updated =
        dsl.update(CUSTOMER_ADDRESS)
            .set(CUSTOMER_ADDRESS.IS_DEFAULT, true)
            .set(CUSTOMER_ADDRESS.UPDATED_AT, OffsetDateTime.now())
            .where(
                CUSTOMER_ADDRESS
                    .ORG_ID
                    .eq(orgId)
                    .and(CUSTOMER_ADDRESS.CUSTOMER_ID.eq(customerId))
                    .and(CUSTOMER_ADDRESS.ID.eq(id)))
            .execute();
    if (updated == 0) {
      throw new NotFoundException("CustomerAddress", id);
    }
  }

  private CustomerAddress toAddress(CustomerAddressRecord r) {
    return new CustomerAddress(
        r.getId(),
        r.getOrgId(),
        r.getCustomerId(),
        r.getLabel(),
        r.getRecipient(),
        r.getPhone(),
        r.getAddress(),
        r.getIsDefault() != null && r.getIsDefault(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
