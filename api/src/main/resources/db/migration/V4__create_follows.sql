-- =========================================================
-- 19. FOLLOWS
-- =========================================================

CREATE TABLE follows
(
    id          CHAR(36) NOT NULL,

    follower_id CHAR(36) NOT NULL,
    followee_id CHAR(36) NOT NULL,

    created_at  DATETIME NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_follows_follower
        FOREIGN KEY (follower_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_follows_followee
        FOREIGN KEY (followee_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    -- 동일한 팔로우 관계 중복 방지
    CONSTRAINT uq_follows_follower_followee
        UNIQUE (follower_id, followee_id),

    -- 자기 자신 팔로우 방지
    CONSTRAINT chk_follows_no_self_follow
        CHECK (follower_id <> followee_id),

    INDEX       idx_follows_followee_created (followee_id, created_at, id),

    INDEX       idx_follows_follower_created (follower_id, created_at, id)
);