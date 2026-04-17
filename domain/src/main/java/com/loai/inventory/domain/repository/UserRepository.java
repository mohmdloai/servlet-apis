package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.domain.model.UserTenantRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserRepository {
  Optional<AppUser> findById(UUID id);

  Optional<AppUser> findByEmail(String email);

  AppUser insert(AppUser user);

  AppUser update(AppUser user);

  int incrementTokenVersion(UUID userId);

  int getTokenVersion(UUID userId);

  List<UserTenantRole> findTenantRoles(UUID userId);

  Set<SystemRole> findSystemRoles(UUID userId);
}
