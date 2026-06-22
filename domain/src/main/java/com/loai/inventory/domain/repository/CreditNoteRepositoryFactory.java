package com.loai.inventory.domain.repository;

/** Creates a {@link CreditNoteRepository} bound to a specific transactional context. */
public interface CreditNoteRepositoryFactory {
  CreditNoteRepository create(Object ctx);
}
