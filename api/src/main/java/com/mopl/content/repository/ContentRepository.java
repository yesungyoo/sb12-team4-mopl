package com.mopl.content.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.mopl.core.domain.content.entity.Content;

public interface ContentRepository
        extends JpaRepository<Content, UUID>, ContentRepositoryCustom {

    Optional<Content> findByIdAndDeletedAtIsNull(UUID id);

    Page<Content> findAllByDeletedAtIsNull(Pageable pageable);

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    List<Content> findAllByIdInAndDeletedAtIsNull(List<UUID> ids);
}