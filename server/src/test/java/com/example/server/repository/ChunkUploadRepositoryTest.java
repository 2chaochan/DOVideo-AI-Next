package com.example.server.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Real SQL + MyBatis + Spring transactions; H2 MySQL mode is not a MySQL deployment test. */
@SpringJUnitConfig(ChunkUploadRepositoryTest.Config.class)
class ChunkUploadRepositoryTest {
  @Autowired ChunkUploadRepository repository;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clear() {
    jdbc.update("DELETE FROM chunk_upload_completions");
    jdbc.update("DELETE FROM media_files");
  }

  @Test
  void duplicateUploadRollsBackSecondMediaInsert() {
    String uploadId = UUID.randomUUID().toString();
    MediaFile first = repository.save(uploadId, 2, media());
    assertThrows(DuplicateKeyException.class, () -> repository.save(uploadId, 2, media()));
    assertEquals(1, count("media_files"));
    assertEquals(1, count("chunk_upload_completions"));
    assertEquals(first.getId(), repository.find(uploadId).mediaId());
  }

  @Test
  void receiptFailureRollsBackMediaAndAllowsRetry() {
    String uploadId = UUID.randomUUID().toString();
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> repository.save(null, 2, media()));
    assertEquals(0, count("media_files"));
    assertEquals(0, count("chunk_upload_completions"));
    assertNotNull(repository.save(uploadId, 2, media()).getId());
    assertEquals(1, count("media_files"));
  }

  @Test
  void concurrentSubmissionsCommitExactlyOneMedia() throws Exception {
    String uploadId = UUID.randomUUID().toString();
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var task =
          (java.util.concurrent.Callable<Boolean>)
              () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                  repository.save(uploadId, 2, media());
                  return true;
                } catch (DuplicateKeyException expected) {
                  return false;
                }
              };
      var first = executor.submit(task);
      var second = executor.submit(task);
      start.countDown();
      assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }
    assertEquals(1, count("media_files"));
    assertEquals(1, count("chunk_upload_completions"));
  }

  @Test
  void mediaDeletionPreservesCompletionTombstone() {
    String uploadId = UUID.randomUUID().toString();
    MediaFile saved = repository.save(uploadId, 2, media());
    jdbc.update("DELETE FROM media_files WHERE id = ?", saved.getId());
    assertEquals(saved.getId(), repository.find(uploadId).mediaId());
    assertThrows(DuplicateKeyException.class, () -> repository.save(uploadId, 2, media()));
    assertEquals(0, count("media_files"));
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  private static MediaFile media() {
    MediaFile media = new MediaFile();
    media.setFilename("sample.mp4");
    media.setFilePath("http://localhost:9000/media/" + UUID.randomUUID() + ".mp4");
    media.setUserId(7L);
    media.setStatus("COMPLETED");
    return media;
  }

  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean
    DataSource dataSource() {
      var ds =
          new DriverManagerDataSource(
              "jdbc:h2:mem:upload-completions;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
      new ResourceDatabasePopulator(
              new ClassPathResource("db/migration/V1__create_core_tables.sql"),
              new ClassPathResource("db/migration/V4__persist_chunk_upload_completions.sql"))
          .execute(ds);
      return ds;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource ds) {
      return new JdbcTemplate(ds);
    }

    @Bean
    DataSourceTransactionManager transactionManager(DataSource ds) {
      return new DataSourceTransactionManager(ds);
    }

    @Bean
    MediaFileMapper mediaMapper(DataSource ds) throws Exception {
      var factory = new MybatisSqlSessionFactoryBean();
      factory.setDataSource(ds);
      var configuration = new MybatisConfiguration();
      configuration.setMapUnderscoreToCamelCase(true);
      configuration.addMapper(MediaFileMapper.class);
      factory.setConfiguration(configuration);
      return new SqlSessionTemplate(factory.getObject()).getMapper(MediaFileMapper.class);
    }

    @Bean
    ChunkUploadRepository repository(JdbcTemplate jdbc, MediaFileMapper mapper) {
      return new ChunkUploadRepository(jdbc, mapper);
    }
  }
}
