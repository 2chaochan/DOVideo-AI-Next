package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.repository.ChunkUploadRepository;
import com.example.server.utils.MinioUtils;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ChunkUploadService {

  private static final Logger log = LoggerFactory.getLogger(ChunkUploadService.class);
  private static final String UPLOAD_KEY_PREFIX = "upload:chunked:";
  private static final long MAX_CHUNK_BYTES = 5L * 1024 * 1024;
  private static final int MAX_TOTAL_CHUNKS = 410;
  private static final String CHUNK_OBJECT_PREFIX = "chunk-uploads/";

  private final StringRedisTemplate redisTemplate;
  private final RedissonClient redissonClient;
  private final MinioUtils minioUtils;
  private final MediaService mediaService;
  private final ChunkUploadRepository uploads;

  public ChunkUploadService(
      StringRedisTemplate redisTemplate,
      RedissonClient redissonClient,
      MinioUtils minioUtils,
      MediaService mediaService,
      ChunkUploadRepository uploads) {
    this.redisTemplate = redisTemplate;
    this.redissonClient = redissonClient;
    this.minioUtils = minioUtils;
    this.mediaService = mediaService;
    this.uploads = uploads;
  }

  public String initialize(String filename, int totalChunks, Long userId) throws IOException {
    String normalizedFilename = mediaService.normalizeVideoFilename(filename);
    if (totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS) {
      throw new IllegalArgumentException("totalChunks must be between 1 and " + MAX_TOTAL_CHUNKS);
    }
    if (userId == null) throw new SecurityException("missing authenticated user");

    String uploadId = UUID.randomUUID().toString();
    Map<String, String> metadata = new HashMap<>();
    metadata.put("filename", normalizedFilename);
    metadata.put("totalChunks", String.valueOf(totalChunks));
    metadata.put("userId", String.valueOf(userId));
    redisTemplate.opsForHash().putAll(uploadKey(uploadId), metadata);
    redisTemplate.expire(uploadKey(uploadId), 1, TimeUnit.DAYS);
    return uploadId;
  }

  public Set<Integer> uploadedChunks(String uploadId, Long userId) {
    validateUploadId(uploadId);
    var completion = uploads.find(uploadId);
    if (completion != null) {
      requireCompletionOwner(completion, userId);
      mediaService.requireOwnedMedia(completion.mediaId(), userId);
      Set<Integer> result = new TreeSet<>();
      for (int i = 0; i < completion.totalChunks(); i++) result.add(i);
      return result;
    }
    requireUpload(uploadId, userId);
    Set<String> members = redisTemplate.opsForSet().members(partsKey(uploadId));
    Set<Integer> result = new TreeSet<>();
    if (members != null) {
      for (String member : members) result.add(Integer.parseInt(member));
    }
    return result;
  }

  public void uploadChunk(
      String uploadId, int chunkIndex, int totalChunks, MultipartFile chunk, Long userId)
      throws Exception {
    if (chunk == null || chunk.isEmpty()) throw new IllegalArgumentException("chunk is empty");
    if (chunk.getSize() > MAX_CHUNK_BYTES) {
      throw new IllegalArgumentException("chunk size cannot exceed 5MB");
    }

    validateUploadId(uploadId);
    // Check the durable receipt before Redis metadata, which may have expired.
    if (completedUpload(uploadId, userId) != null) return;
    Map<Object, Object> metadata = requireUpload(uploadId, userId);
    int expectedChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
    if (totalChunks != expectedChunks || chunkIndex < 0 || chunkIndex >= expectedChunks) {
      throw new IllegalArgumentException("invalid chunk index or totalChunks");
    }
    RLock uploadLock = redissonClient.getReadWriteLock("lock:upload:state:" + uploadId).readLock();
    boolean acquired = false;
    try {
      acquired = uploadLock.tryLock(30, TimeUnit.SECONDS);
      if (!acquired) throw new BusinessException(ErrorCode.CONFLICT, "该上传任务正在合并中，请稍后重试");
      // Completion may have won while this request waited for the shared state lock.
      if (completedUpload(uploadId, userId) != null) return;
      try (InputStream inputStream = chunk.getInputStream()) {
        minioUtils.uploadObject(
            chunkObjectName(uploadId, chunkIndex),
            inputStream,
            chunk.getSize(),
            "application/octet-stream");
      }
      redisTemplate.opsForSet().add(partsKey(uploadId), String.valueOf(chunkIndex));
      redisTemplate.expire(uploadKey(uploadId), 1, TimeUnit.DAYS);
      redisTemplate.expire(partsKey(uploadId), 1, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    } finally {
      if (acquired && uploadLock.isHeldByCurrentThread()) uploadLock.unlock();
    }
  }

  public MediaFile complete(String uploadId, Long userId) throws Exception {
    validateUploadId(uploadId);
    MediaFile saved = completedUpload(uploadId, userId);
    if (saved != null) return saved;
    RLock mergeLock = redissonClient.getReadWriteLock("lock:upload:state:" + uploadId).writeLock();
    // 这两种情况是客户端可重试的“状态冲突”，不是服务端故障：用 BusinessException 标成 409，
    // 避免和本类中真正的内部错误（如 md5Digest 的 IllegalStateException）混在一起被兜底成 500。
    if (!mergeLock.tryLock()) {
      throw new BusinessException(ErrorCode.CONFLICT, "该上传任务正在合并中，请稍后重试");
    }

    try {
      MediaFile completed = completedUpload(uploadId, userId);
      if (completed != null) return completed;

      Map<Object, Object> metadata = requireUpload(uploadId, userId);
      String filename = String.valueOf(metadata.get("filename"));
      int totalChunks = Integer.parseInt(String.valueOf(metadata.get("totalChunks")));
      Set<Integer> uploadedChunks = uploadedChunks(uploadId, userId);
      if (uploadedChunks.size() != totalChunks) {
        throw new BusinessException(
            ErrorCode.CONFLICT, "分片尚未全部上传完成（已传 " + uploadedChunks.size() + "/" + totalChunks + "）");
      }

      Path mergedFile = Files.createTempFile("dovideo-merged-", fileSuffix(filename));
      MessageDigest digest = md5Digest();
      try {
        try (OutputStream fileOutput = Files.newOutputStream(mergedFile);
            DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
            BufferedOutputStream output = new BufferedOutputStream(digestOutput)) {
          for (int i = 0; i < totalChunks; i++) {
            minioUtils.copyObjectTo(chunkObjectName(uploadId, i), output);
          }
        }

        String fileUrl = minioUtils.uploadLocalFile(mergedFile.toFile(), filename);
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(filename);
        mediaFile.setFilePath(fileUrl);
        mediaFile.setUserId(userId);
        mediaFile.setStatus("COMPLETED");
        mediaFile.setContentHash(HexFormat.of().formatHex(digest.digest()));
        mediaFile.setUploadTime(LocalDateTime.now());
        try {
          // The Spring repository proxy commits both rows before returning.
          mediaFile = uploads.save(uploadId, totalChunks, mediaFile);
        } catch (DuplicateKeyException duplicate) {
          removeUncommittedObject(fileUrl, duplicate);
          MediaFile existing = completedUpload(uploadId, userId);
          if (existing == null) throw duplicate;
          return existing;
        } catch (RuntimeException failure) {
          // A connection failure during COMMIT can have an unknown outcome.
          // Keep the object: deleting it could break an already committed media row.
          log.warn(
              "upload_database_completion_failed uploadId={} objectRetained=true",
              uploadId,
              failure);
          throw failure;
        }
        mediaService.rememberContentHash(mediaFile.getId(), mediaFile.getContentHash());
        mediaService.invalidateUserList(userId);
        try {
          redisTemplate
              .opsForValue()
              .set(completedKey(uploadId), String.valueOf(mediaFile.getId()), 25, TimeUnit.HOURS);
          redisTemplate.expire(uploadKey(uploadId), 1, TimeUnit.DAYS);
          redisTemplate.expire(partsKey(uploadId), 1, TimeUnit.DAYS);
        } catch (RuntimeException cacheFailure) {
          log.warn(
              "upload_completion_cache_failed uploadId={} mediaId={}",
              uploadId,
              mediaFile.getId(),
              cacheFailure);
        }
        cleanup(uploadId, totalChunks, mediaFile.getId());
        return mediaFile;
      } finally {
        Files.deleteIfExists(mergedFile);
      }
    } finally {
      if (mergeLock.isHeldByCurrentThread()) mergeLock.unlock();
    }
  }

  private MediaFile completedUpload(String uploadId, Long userId) {
    var completion = uploads.find(uploadId);
    if (completion != null) {
      requireCompletionOwner(completion, userId);
      return mediaService.requireOwnedMedia(completion.mediaId(), userId);
    }
    // Compatibility with uploads completed before migration V4, while their receipt lives.
    String mediaId = redisTemplate.opsForValue().get(completedKey(uploadId));
    if (mediaId == null) return null;
    try {
      return mediaService.requireOwnedMedia(Long.valueOf(mediaId), userId);
    } catch (NumberFormatException e) {
      redisTemplate.delete(completedKey(uploadId));
      return null;
    }
  }

  private void requireCompletionOwner(ChunkUploadRepository.Completion completion, Long userId) {
    if (!Objects.equals(completion.userId(), userId)) throw new SecurityException("无权访问该上传任务");
  }

  private void removeUncommittedObject(String fileUrl, RuntimeException failure) {
    try {
      minioUtils.removeFile(fileUrl);
    } catch (RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
      log.warn("duplicate_upload_object_cleanup_failed", cleanupFailure);
    }
  }

  private Map<Object, Object> requireUpload(String uploadId, Long userId) {
    validateUploadId(uploadId);
    Map<Object, Object> metadata = redisTemplate.opsForHash().entries(uploadKey(uploadId));
    if (metadata.isEmpty()) {
      throw new IllegalArgumentException("uploadId does not exist or has expired");
    }
    if (!Objects.equals(String.valueOf(userId), String.valueOf(metadata.get("userId")))) {
      throw new SecurityException("无权访问该上传任务");
    }
    return metadata;
  }

  private void validateUploadId(String uploadId) {
    try {
      UUID.fromString(uploadId);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid uploadId");
    }
  }

  private void cleanup(String uploadId, int totalChunks, Long mediaId) {
    for (int i = 0; i < totalChunks; i++) {
      try {
        minioUtils.removeObject(chunkObjectName(uploadId, i));
      } catch (RuntimeException e) {
        log.warn(
            "chunk_object_cleanup_failed uploadId={} chunkIndex={} mediaId={}",
            uploadId,
            i,
            mediaId,
            e);
      }
    }
    // Keep the small ownership metadata and part indexes until their TTL expires.
    // A client that missed the successful response checks /upload-status first;
    // Retain the legacy resumable metadata; durable completion no longer depends on it.
  }

  private MessageDigest md5Digest() {
    try {
      return MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 is not available", e);
    }
  }

  private String uploadKey(String uploadId) {
    return UPLOAD_KEY_PREFIX + uploadId;
  }

  private String partsKey(String uploadId) {
    return uploadKey(uploadId) + ":parts";
  }

  private String completedKey(String uploadId) {
    return uploadKey(uploadId) + ":completed";
  }

  private String chunkObjectName(String uploadId, int chunkIndex) {
    validateUploadId(uploadId);
    return CHUNK_OBJECT_PREFIX + uploadId + "/part-" + chunkIndex;
  }

  private String fileSuffix(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot >= 0 ? filename.substring(dot) : "";
  }
}
