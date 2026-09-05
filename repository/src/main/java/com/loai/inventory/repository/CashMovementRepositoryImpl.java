package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CASH_MOVEMENT;

import com.loai.inventory.domain.model.CashMovement;
import com.loai.inventory.domain.model.CashMovementKind;
import com.loai.inventory.domain.repository.CashMovementRepository;
import com.loai.inventory.repository.generated.tables.records.CashMovementRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

public final class CashMovementRepositoryImpl implements CashMovementRepository {

  private final DSLContext dsl;

  public CashMovementRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void insert(CashMovement m) {
    dsl.insertInto(CASH_MOVEMENT)
        .set(CASH_MOVEMENT.ID, m.getId())
        .set(CASH_MOVEMENT.ORG_ID, m.getOrgId())
        .set(CASH_MOVEMENT.SHIFT_ID, m.getShiftId())
        .set(
            CASH_MOVEMENT.KIND,
            com.loai.inventory.repository.generated.enums.CashMovementKind.valueOf(
                m.getKind().name()))
        .set(CASH_MOVEMENT.AMOUNT, m.getAmount())
        .set(CASH_MOVEMENT.REASON, m.getReason())
        .set(CASH_MOVEMENT.RECORDED_BY, m.getRecordedBy())
        .set(CASH_MOVEMENT.RECORDED_AT, m.getRecordedAt())
        .execute();
  }

  @Override
  public List<CashMovement> findByShift(UUID orgId, UUID shiftId) {
    return dsl.selectFrom(CASH_MOVEMENT)
        .where(CASH_MOVEMENT.ORG_ID.eq(orgId).and(CASH_MOVEMENT.SHIFT_ID.eq(shiftId)))
        .orderBy(CASH_MOVEMENT.RECORDED_AT.asc(), CASH_MOVEMENT.ID.asc())
        .fetch(CashMovementRepositoryImpl::toMovement);
  }

  private static CashMovement toMovement(CashMovementRecord r) {
    return CashMovement.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getShiftId(),
        CashMovementKind.valueOf(r.getKind().name()),
        r.getAmount(),
        r.getReason(),
        r.getRecordedBy(),
        r.getRecordedAt());
  }
}
