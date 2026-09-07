-- =========================================================
-- MOPL Domain Schema
-- MySQL 8.0
-- Flyway V1
-- =========================================================


-- =========================================================
-- 1. USERS
-- =========================================================

CREATE TABLE users
(

    id                       CHAR(36)     NOT NULL,

    created_at               DATETIME     NOT NULL,
    deleted_at               DATETIME NULL,

    email                    VARCHAR(255) NOT NULL,

    -- Soft Delete된 회원의 이메일은 UNIQUE 검사에서 제외하기 위한 Generated Column
    email_unique_key         VARCHAR(255)
        GENERATED ALWAYS AS (
            IF(deleted_at IS NULL, email, NULL)
            ) STORED,

    -- 소셜 로그인 전용 사용자는 비밀번호가 없을 수 있음
    password                 VARCHAR(255) NULL,

    name                     VARCHAR(50)  NOT NULL,
    profile_image_url        VARCHAR(500) NULL,

    role                     VARCHAR(20)  NOT NULL,
    locked                   BOOLEAN      NOT NULL,

    temp_password            VARCHAR(255) NULL,
    temp_password_expired_at DATETIME NULL,

    PRIMARY KEY (id),

    CONSTRAINT uk_users_email
        UNIQUE (email_unique_key)
);


-- =========================================================
-- 2. SOCIAL ACCOUNTS
-- =========================================================

CREATE TABLE social_accounts
(
    id          CHAR(36)     NOT NULL,
    user_id     CHAR(36)     NOT NULL,

    provider    VARCHAR(20)  NOT NULL,
    provider_id VARCHAR(255) NOT NULL,

    created_at  DATETIME     NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_social_accounts_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    -- 동일 소셜 계정이 여러 MOPL 사용자에게 연결되는 것 방지
    CONSTRAINT uk_social_accounts_provider
        UNIQUE (provider, provider_id),

    -- 한 사용자가 동일 Provider 계정을 여러 개 연결하는 것 방지
    CONSTRAINT uk_social_accounts_user_provider
        UNIQUE (user_id, provider)
);


-- =========================================================
-- 3. CONTENTS
-- =========================================================

CREATE TABLE contents
(
    id                  CHAR(36)     NOT NULL,
    type                VARCHAR(20)  NOT NULL,

    title               VARCHAR(255) NOT NULL,
    description         TEXT NULL,
    thumbnail_url       VARCHAR(1000) NULL,

    external_source     VARCHAR(30)  NOT NULL,
    external_id         VARCHAR(100) NULL,

    release_date        DATE NULL,

    external_popularity DECIMAL(15, 4) NULL,
    external_rating     DECIMAL(4, 2) NULL,
    external_vote_count BIGINT NULL,

    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    deleted_at          DATETIME NULL,

    PRIMARY KEY (id),

    CONSTRAINT uq_contents_external
        UNIQUE (external_source, external_id),

    INDEX               idx_contents_type (type),

    INDEX               idx_contents_created_at (created_at)
);


-- =========================================================
-- 4. CONTENT TAGS
-- =========================================================

CREATE TABLE content_tags
(
    content_id CHAR(36)     NOT NULL,
    tag        VARCHAR(30)  NOT NULL,
    value      VARCHAR(100) NOT NULL,

    PRIMARY KEY (content_id, tag, value),

    CONSTRAINT fk_content_tags_content
        FOREIGN KEY (content_id)
            REFERENCES contents (id)
            ON DELETE CASCADE,

    INDEX      idx_content_tags_tag_value_content (tag, value, content_id)
);


-- =========================================================
-- 5. SPORTS EVENT DETAILS
-- =========================================================

CREATE TABLE sports_event_details
(
    content_id     CHAR(36)    NOT NULL,

    sport          VARCHAR(50) NOT NULL,
    league_name    VARCHAR(255) NULL,
    home_team_name VARCHAR(255) NULL,
    away_team_name VARCHAR(255) NULL,

    event_at       DATETIME NULL,
    status         VARCHAR(50) NULL,

    PRIMARY KEY (content_id),

    CONSTRAINT fk_sports_event_details_content
        FOREIGN KEY (content_id)
            REFERENCES contents (id)
            ON DELETE CASCADE,

    INDEX          idx_sports_event_details_event_at (event_at)
);


-- =========================================================
-- 6. REVIEWS
-- =========================================================

CREATE TABLE reviews
(
    id         CHAR(36)      NOT NULL,
    user_id    CHAR(36)      NOT NULL,
    content_id CHAR(36)      NOT NULL,

    rating     DECIMAL(2, 1) NOT NULL,
    text       VARCHAR(1000) NOT NULL,

    created_at DATETIME      NOT NULL,
    updated_at DATETIME      NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_reviews_user
        FOREIGN KEY (user_id)
            REFERENCES users (id),

    CONSTRAINT fk_reviews_content
        FOREIGN KEY (content_id)
            REFERENCES contents (id)
            ON DELETE CASCADE,

    CONSTRAINT uq_reviews_user_content
        UNIQUE (user_id, content_id),

    CONSTRAINT chk_reviews_rating
        CHECK (rating >= 0.0 AND rating <= 5.0),

    INDEX      idx_reviews_content_created (content_id, created_at, id),

    INDEX      idx_reviews_content_rating (content_id, rating, id)
);


-- =========================================================
-- 7. PLAYLISTS
-- =========================================================

CREATE TABLE playlists
(
    id          CHAR(36)     NOT NULL,
    owner_id    CHAR(36)     NOT NULL,

    title       VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,

    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_playlists_owner
        FOREIGN KEY (owner_id)
            REFERENCES users (id)
);


-- =========================================================
-- 8. PLAYLIST CONTENTS
-- =========================================================

CREATE TABLE playlist_contents
(
    id          CHAR(36) NOT NULL,
    playlist_id CHAR(36) NOT NULL,
    content_id  CHAR(36) NOT NULL,

    created_at  DATETIME NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_playlist_contents_playlist
        FOREIGN KEY (playlist_id)
            REFERENCES playlists (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_playlist_contents_content
        FOREIGN KEY (content_id)
            REFERENCES contents (id),

    CONSTRAINT uq_playlist_contents
        UNIQUE (playlist_id, content_id)
);


-- =========================================================
-- 9. PLAYLIST SUBSCRIPTIONS
-- =========================================================

CREATE TABLE playlist_subscriptions
(
    id            CHAR(36) NOT NULL,
    playlist_id   CHAR(36) NOT NULL,
    subscriber_id CHAR(36) NOT NULL,

    created_at    DATETIME NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_playlist_subscriptions_playlist
        FOREIGN KEY (playlist_id)
            REFERENCES playlists (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_playlist_subscriptions_subscriber
        FOREIGN KEY (subscriber_id)
            REFERENCES users (id),

    CONSTRAINT uq_playlist_subscriptions
        UNIQUE (playlist_id, subscriber_id)
);


-- =========================================================
-- 10. CONTENT VIEWS
-- =========================================================

CREATE TABLE content_views
(
    user_id         CHAR(36) NOT NULL,
    content_id      CHAR(36) NOT NULL,

    view_count      BIGINT   NOT NULL DEFAULT 1,

    first_viewed_at DATETIME NOT NULL,
    last_viewed_at  DATETIME NOT NULL,

    PRIMARY KEY (user_id, content_id),

    CONSTRAINT fk_content_views_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_content_views_content
        FOREIGN KEY (content_id)
            REFERENCES contents (id)
            ON DELETE CASCADE
);


-- =========================================================
-- 11. CONVERSATIONS
-- =========================================================

CREATE TABLE conversations
(
    id         CHAR(36) NOT NULL,
    user1_id   CHAR(36) NOT NULL,
    user2_id   CHAR(36) NOT NULL,

    created_at DATETIME NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_conversations_user1
        FOREIGN KEY (user1_id)
            REFERENCES users (id),

    CONSTRAINT fk_conversations_user2
        FOREIGN KEY (user2_id)
            REFERENCES users (id),

    CONSTRAINT uq_conversations_users
        UNIQUE (user1_id, user2_id),

    CONSTRAINT chk_conversations_canonical_order
        CHECK (user1_id < user2_id)
);


-- =========================================================
-- 12. DIRECT MESSAGES
-- =========================================================

CREATE TABLE direct_messages
(
    id              CHAR(36)      NOT NULL,
    conversation_id CHAR(36)      NOT NULL,
    sender_id       CHAR(36)      NOT NULL,
    receiver_id     CHAR(36)      NOT NULL,

    content         VARCHAR(1000) NOT NULL,

    created_at      DATETIME      NOT NULL,
    read_at         DATETIME NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_direct_messages_conversation
        FOREIGN KEY (conversation_id)
            REFERENCES conversations (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_direct_messages_sender
        FOREIGN KEY (sender_id)
            REFERENCES users (id),

    CONSTRAINT fk_direct_messages_receiver
        FOREIGN KEY (receiver_id)
            REFERENCES users (id),

    INDEX           idx_direct_messages_cursor (conversation_id, created_at, id),

    INDEX           idx_direct_messages_sender_created (sender_id, created_at)
);


-- =========================================================
-- 13. CONTENT CHAT MESSAGES
-- =========================================================

CREATE TABLE content_chat_messages
(
    id         CHAR(36)      NOT NULL,
    content_id CHAR(36)      NOT NULL,
    sender_id  CHAR(36)      NOT NULL,

    content    VARCHAR(1000) NOT NULL,

    created_at DATETIME      NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_content_chat_messages_content
        FOREIGN KEY (content_id)
            REFERENCES contents (id),

    CONSTRAINT fk_content_chat_messages_sender
        FOREIGN KEY (sender_id)
            REFERENCES users (id),

    INDEX      idx_content_chat_messages_cursor (content_id, created_at, id),

    INDEX      idx_content_chat_messages_sender_created (sender_id, created_at)
);


-- =========================================================
-- 14. NOTIFICATIONS
-- =========================================================

CREATE TABLE notifications
(
    id          CHAR(36)     NOT NULL,

    created_at  DATETIME     NOT NULL
                                      DEFAULT CURRENT_TIMESTAMP,

    receiver_id CHAR(36)     NOT NULL,

    title       VARCHAR(100) NOT NULL,
    content     VARCHAR(500) NOT NULL,

    level       VARCHAR(20)  NOT NULL,
    is_read     BOOLEAN      NOT NULL DEFAULT FALSE,

    PRIMARY KEY (id),

    CONSTRAINT fk_notifications_receiver
        FOREIGN KEY (receiver_id)
            REFERENCES users (id),

    INDEX       idx_notifications_receiver_created (receiver_id, created_at)
);


-- =========================================================
-- 15. NOTIFICATION PREFERENCES
-- =========================================================

CREATE TABLE notification_preferences
(
    id         CHAR(36)    NOT NULL,

    created_at DATETIME    NOT NULL
                                    DEFAULT CURRENT_TIMESTAMP,

    updated_at DATETIME    NOT NULL
                                    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    user_id    CHAR(36)    NOT NULL,

    type       VARCHAR(30) NOT NULL,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,

    PRIMARY KEY (id),

    CONSTRAINT fk_notification_preferences_user
        FOREIGN KEY (user_id)
            REFERENCES users (id),

    CONSTRAINT uq_notification_preferences_user_type
        UNIQUE (user_id, type)
);


-- =========================================================
-- 16. MESSAGE MODERATION LOGS
-- =========================================================

CREATE TABLE message_moderation_logs
(
    id                      CHAR(36)    NOT NULL,
    sender_id               CHAR(36)    NOT NULL,

    message_type            VARCHAR(20) NOT NULL,

    conversation_id         CHAR(36) NULL,
    direct_message_id       CHAR(36) NULL,
    content_id              CHAR(36) NULL,
    content_chat_message_id CHAR(36) NULL,

    detection_source        VARCHAR(20) NOT NULL,
    category                VARCHAR(30) NOT NULL,
    action                  VARCHAR(20) NOT NULL,

    original_content        VARCHAR(1000) NULL,

    created_at              DATETIME    NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_moderation_logs_sender
        FOREIGN KEY (sender_id)
            REFERENCES users (id),

    CONSTRAINT fk_moderation_logs_conversation
        FOREIGN KEY (conversation_id)
            REFERENCES conversations (id)
            ON DELETE SET NULL,

    CONSTRAINT fk_moderation_logs_direct_message
        FOREIGN KEY (direct_message_id)
            REFERENCES direct_messages (id)
            ON DELETE SET NULL,

    CONSTRAINT fk_moderation_logs_content
        FOREIGN KEY (content_id)
            REFERENCES contents (id)
            ON DELETE SET NULL,

    CONSTRAINT fk_moderation_logs_content_chat_message
        FOREIGN KEY (content_chat_message_id)
            REFERENCES content_chat_messages (id)
            ON DELETE SET NULL,

    CONSTRAINT chk_moderation_logs_message_type
        CHECK (message_type IN ('DM', 'CONTENT_CHAT')),

    INDEX                   idx_moderation_logs_sender_created (sender_id, created_at)
);


-- =========================================================
-- 17. PLAYLIST AI SESSIONS
-- =========================================================

CREATE TABLE playlist_ai_sessions
(
    id         CHAR(36) NOT NULL,
    user_id    CHAR(36) NOT NULL,

    -- 목록에 표시될 대화방 제목
    title      VARCHAR(255) NULL,

    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_playlist_ai_sessions_user
        FOREIGN KEY (user_id)
            REFERENCES users (id),

    INDEX      idx_playlist_ai_sessions_user_updated (user_id, updated_at)
);


-- =========================================================
-- 18. PLAYLIST AI MESSAGES
-- =========================================================

CREATE TABLE playlist_ai_messages
(
    id                 CHAR(36)    NOT NULL,
    session_id         CHAR(36)    NOT NULL,

    -- USER / ASSISTANT
    role               VARCHAR(20) NOT NULL,
    content            TEXT        NOT NULL,

    result_playlist_id CHAR(36) NULL,

    created_at         DATETIME    NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_playlist_ai_messages_session
        FOREIGN KEY (session_id)
            REFERENCES playlist_ai_sessions (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_playlist_ai_messages_result_playlist
        FOREIGN KEY (result_playlist_id)
            REFERENCES playlists (id)
            ON DELETE SET NULL,

    INDEX              idx_playlist_ai_messages_cursor (session_id, created_at, id)
);