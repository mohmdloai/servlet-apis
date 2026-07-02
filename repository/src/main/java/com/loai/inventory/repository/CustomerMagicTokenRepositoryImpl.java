package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER_MAGIC_TOKEN;

import com.loai.inventory.domain.model.CustomerMagicToken;
import com.loai.inventory.domain.model.MagicTokenPurpose;
import com.loai.inventory.domain.repository.CustomerMagicTokenRepository;
import com.loai.inventory.repository.generated.tables.records.CustomerMagicTokenRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * jOOQ persistence for order-scoped magic tokens. {@code purpose} is TEXT (enum {@code name()}).
 */
public final class CustomerMagicTokenRepositoryImpl implements CustomerMagicTokenRepository {

  private final DSLContext dsl;

  public CustomerMagicTokenRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public CustomerMagicToken insert(CustomerMagicToken token) {
    CustomerMagicTokenRecord r =
        dsl.insertInto(CUSTOMER_MAGIC_TOKEN)
            .set(CUSTOMER_MAGIC_TOKEN.ORG_ID, token.orgId())
            .set(CUSTOMER_MAGIC_TOKEN.CUSTOMER_ID, token.customerId())
            .set(CUSTOMER_MAGIC_TOKEN.TOKEN_HASH, token.tokenHash())
            .set(CUSTOMER_MAGIC_TOKEN.PURPOSE, token.purpose().name())
            .set(CUSTOMER_MAGIC_TOKEN.RESOURCE_ID, token.resourceId())
            .set(CUSTOMER_MAGIC_TOKEN.EXPIRES_AT, token.expiresAt())
            .returning()
            .fetchOne();
    if (r == null) {
      throw new IllegalStateException("INSERT into customer_magic_token returned no record");
    }
    return toToken(r);
  }

  @Override
  public Optional<CustomerMagicToken> findActiveByHash(String tokenHash, OffsetDateTime now) {
    return dsl.selectFrom(CUSTOMER_MAGIC_TOKEN)
        .where(CUSTOMER_MAGIC_TOKEN.TOKEN_HASH.eq(tokenHash))
        .and(CUSTOMER_MAGIC_TOKEN.CONSUMED_AT.isNull())
        .and(CUSTOMER_MAGIC_TOKEN.EXPIRES_AT.gt(now))
        .fetchOptional()
        .map(this::toToken);
  }

  private CustomerMagicToken toToken(CustomerMagicTokenRecord r) {
    return new CustomerMagicToken(
        r.getId(),
        r.getOrgId(),
        r.getCustomerId(),
        r.getTokenHash(),
        MagicTokenPurpose.valueOf(r.getPurpose()),
        r.getResourceId(),
        r.getExpiresAt(),
        r.getConsumedAt(),
        r.getCreatedAt());
  }
}
