package com.example.jpalab.lab1;

import com.example.jpalab.member.Member;
import com.example.jpalab.member.MemberRepository;
import com.example.jpalab.member.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class FlushTransactionBoundaryTest {

    private static final Logger log = LoggerFactory.getLogger(FlushTransactionBoundaryTest.class);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    MemberService memberService;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from members");
    }

    @Test
    void experiment1_saveWithoutExplicitFlush() {
        log.info("[checkpoint] before transaction");

        transactionTemplate.executeWithoutResult(status -> {
            log.info("[checkpoint] before save");
            Member member = memberRepository.save(new Member("first@example.com"));
            log.info("[checkpoint] after save: id={}", member.getId());
        });

        log.info("[checkpoint] after transaction");
        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from members where email = ?",
                Long.class,
                "first@example.com"
        );
        log.info("[observation] committed row count={}", rowCount);

        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    void experiment2_constraintViolationWithoutExplicitFlush() {
        jdbcTemplate.update(
                "insert into members (id, email) values (nextval('member_sequence'), ?)",
                "duplicate@example.com"
        );

        log.info("[checkpoint] before service");

        RuntimeException propagated = null;
        try {
            memberService.registerDuplicate();
            log.info("[checkpoint] service returned normally");
        } catch (RuntimeException exception) {
            propagated = exception;
            log.info("[checkpoint] caught by caller: {}", exception.getClass().getSimpleName());
        }

        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from members where email = ?",
                Long.class,
                "duplicate@example.com"
        );
        log.info("[observation] committed row count={}", rowCount);

        assertThat(propagated).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    void experiment3_constraintViolationWithExplicitFlush() {
        jdbcTemplate.update(
                "insert into members (id, email) values (nextval('member_sequence'), ?)",
                "duplicate@example.com"
        );

        log.info("[checkpoint] before service");

        RuntimeException propagated = null;
        try {
            memberService.registerDuplicateWithFlush();
            log.info("[checkpoint] service returned normally");
        } catch (RuntimeException exception) {
            propagated = exception;
            log.info("[checkpoint] caught by caller: {}", exception.getClass().getSimpleName());
        }

        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from members where email = ?",
                Long.class,
                "duplicate@example.com"
        );
        log.info("[observation] committed row count={}", rowCount);

        assertThat(propagated).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(rowCount).isEqualTo(1L);
    }

    @Test
    void experiment4_dirtyCheckingWithoutSaveOrExplicitFlush() {
        Long memberId = jdbcTemplate.queryForObject(
                "insert into members (id, email) values (nextval('member_sequence'), ?) returning id",
                Long.class,
                "original@example.com"
        );

        log.info("[checkpoint] before service");

        RuntimeException propagated = null;
        try {
            memberService.changeEmail(memberId);
            log.info("[checkpoint] service returned normally");
        } catch (RuntimeException exception) {
            propagated = exception;
            log.info("[checkpoint] caught by caller: {}", exception.getClass().getSimpleName());
        }

        String email = jdbcTemplate.queryForObject(
                "select email from members where id = ?",
                String.class,
                memberId
        );
        log.info("[observation] committed email={}", email);

        assertThat(propagated).isNull();
        assertThat(email).isEqualTo("changed@example.com");
    }

    @Test
    void experiment5_autoFlushBeforeJpaQuery() {
        Long memberId = jdbcTemplate.queryForObject(
                "insert into members (id, email) values (nextval('member_sequence'), ?) returning id",
                Long.class,
                "original@example.com"
        );

        log.info("[checkpoint] before service");

        RuntimeException propagated = null;
        try {
            memberService.changeEmailAndCount(memberId);
            log.info("[checkpoint] service returned normally");
        } catch (RuntimeException exception) {
            propagated = exception;
            log.info("[checkpoint] caught by caller: {}", exception.getClass().getSimpleName());
        }

        String email = jdbcTemplate.queryForObject(
                "select email from members where id = ?",
                String.class,
                memberId
        );
        log.info("[observation] committed email={}", email);

        assertThat(propagated).isNull();
        assertThat(email).isEqualTo("changed@example.com");
    }

    @Test
    void experiment6_rollbackAfterAutoFlush() {
        Long memberId = jdbcTemplate.queryForObject(
                "insert into members (id, email) values (nextval('member_sequence'), ?) returning id",
                Long.class,
                "original@example.com"
        );

        log.info("[checkpoint] before service");

        RuntimeException propagated = null;
        try {
            memberService.changeEmailAndFail(memberId);
            log.info("[checkpoint] service returned normally");
        } catch (RuntimeException exception) {
            propagated = exception;
            log.info("[checkpoint] caught by caller: {}", exception.getClass().getSimpleName());
        }

        String email = jdbcTemplate.queryForObject(
                "select email from members where id = ?",
                String.class,
                memberId
        );
        log.info("[observation] committed email={}", email);

        assertThat(propagated).isInstanceOf(IllegalStateException.class);
        assertThat(email).isEqualTo("original@example.com");
    }
}
