package com.mopl.realtime.contentchat.repository;

import com.mopl.core.domain.content.entity.Content;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRepository extends JpaRepository<Content, UUID> {

	Optional<Content> findByIdAndDeletedAtIsNull(UUID id);
}