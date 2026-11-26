package com.example.server.repository;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 持久化上传完成凭据；与媒体记录原子提交，删除媒体后仍保留凭据避免旧请求重新建档。 */
@Repository
public class ChunkUploadRepository {
  private final JdbcTemplate jdbc;
  private final MediaFileMapper mediaMapper;

  public ChunkUploadRepository(JdbcTemplate jdbc, MediaFileMapper mediaMapper) {
    this.jdbc = jdbc;
    this.mediaMapper = mediaMapper;
  }

  public Completion find(String uploadId) {
    List<Completion> rows =
        jdbc.query(
            "SELECT media_id, user_id, total_chunks FROM chunk_upload_completions WHERE upload_id = ?",
            (rs, row) ->
                new Completion(
                    rs.getLong("media_id"), rs.getLong("user_id"), rs.getInt("total_chunks")),
            uploadId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @Transactional
  public MediaFile save(String uploadId, int totalChunks, MediaFile media) {
    mediaMapper.insert(media);
    jdbc.update(
        "INSERT INTO chunk_upload_completions (upload_id, media_id, user_id, total_chunks) VALUES (?, ?, ?, ?)",
        uploadId,
        media.getId(),
        media.getUserId(),
        totalChunks);
    return media;
  }

  public record Completion(Long mediaId, Long userId, int totalChunks) {}
}
