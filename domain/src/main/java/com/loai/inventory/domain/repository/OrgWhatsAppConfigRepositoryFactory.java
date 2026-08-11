package com.loai.inventory.domain.repository;

/**
 * Creates an OrgWhatsAppConfigRepository bound to a specific execution context: transactional ctx
 */
public interface OrgWhatsAppConfigRepositoryFactory {
  OrgWhatsAppConfigRepository create(Object ctx);
}
