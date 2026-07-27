package com.loai.inventory.domain.repository;

/** Creates an {@link OrgTimelineRepository} bound to a specific execution context. */
public interface OrgTimelineRepositoryFactory {
  OrgTimelineRepository create(Object ctx);
}
