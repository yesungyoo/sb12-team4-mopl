package com.mopl.batch.content.repository;

import com.mopl.batch.external.common.dto.ExternalContentTagDto;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ContentTagBackfillRepository {

    private final JdbcTemplate jdbcTemplate;

    public ContentTagBackfillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Candidate> findUntaggedExternalContents() {
        return jdbcTemplate.query(
                """
                        SELECT
                            c.id,
                            c.type,
                            c.external_source,
                            c.external_id
                        FROM contents c
                        WHERE c.deleted_at IS NULL
                            AND c.external_id IS NOT NULL
                            AND c.external_source IN ('TMDB', 'THESPORTSDB')
                            AND NOT EXISTS (
                                SELECT 1
                                FROM content_tags ct
                                WHERE ct.content_id = c.id
                            )
                        ORDER BY c.external_source, c.type, c.id
                        """,
                (resultSet, rowNumber) -> new Candidate(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("type"),
                        resultSet.getString("external_source"),
                        resultSet.getString("external_id")
                )
        );
    }

    @Transactional
    public int insertTags(
            UUID contentId,
            List<ExternalContentTagDto> tags
    ) {
        if (tags == null || tags.isEmpty()) {
            return 0;
        }

        int insertedCount = 0;

        for (ExternalContentTagDto tag : tags) {
            insertedCount += jdbcTemplate.update(
                    """
                            INSERT IGNORE INTO content_tags (
                                content_id,
                                tag,
                                value
                            )
                            VALUES (?, ?, ?)
                            """,
                    contentId.toString(),
                    tag.tag(),
                    tag.value()
            );
        }

        return insertedCount;
    }

    public record Candidate(
            UUID contentId,
            String type,
            String externalSource,
            String externalId
    ) {
    }
}
