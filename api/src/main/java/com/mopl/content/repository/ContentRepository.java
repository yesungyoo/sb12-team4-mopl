package com.mopl.content.repository;

import com.mopl.core.domain.content.entity.Content;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ContentRepository extends JpaRepository<Content, UUID>, ContentRepositoryCustom {

    Optional<Content> findByIdAndDeletedAtIsNull(UUID id);

    Page<Content> findAllByDeletedAtIsNull(Pageable pageable);
}
