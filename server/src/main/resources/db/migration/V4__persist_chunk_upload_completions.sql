CREATE TABLE chunk_upload_completions (
    upload_id VARCHAR(36) NOT NULL,
    media_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    total_chunks INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (upload_id),
    UNIQUE KEY uk_chunk_upload_media (media_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
