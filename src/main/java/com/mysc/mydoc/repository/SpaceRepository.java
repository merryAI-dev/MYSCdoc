package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.Space;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceRepository extends JpaRepository<Space, UUID> {
    Optional<Space> findBySlug(String slug);
}
