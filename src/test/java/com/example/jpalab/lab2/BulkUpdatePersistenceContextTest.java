package com.example.jpalab.lab2;

import com.example.jpalab.member.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class BulkUpdatePersistenceContextTest {

    private static final Logger log = LoggerFactory.getLogger(BulkUpdatePersistenceContextTest.class);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MemberService memberService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from members");
    }

    @Test
    void experiment1_bulkUpdateBypassesPersistenceContext() {
        Long memberId = jdbcTemplate.queryForObject(
                "insert into members (id, email) values (nextval('member_sequence'), ?) returning id",
                Long.class,
                "first@example.com"
        );

        MemberService.BulkUpdateObservation observation =
                memberService.changeEmailWithBulk(memberId);

        String committedEmail = jdbcTemplate.queryForObject(
                "select email from members where id = ?",
                String.class,
                memberId
        );

        log.info("[observation] first email={}", observation.firstEmail());
        log.info("[observation] second email={}", observation.secondEmail());
        log.info("[observation] same instance={}", observation.sameInstance());
        log.info("[observation] committed email={}", committedEmail);

        assertThat(observation.updatedRows()).isEqualTo(1);
        assertThat(observation.firstEmail()).isEqualTo("first@example.com");
        assertThat(observation.secondEmail()).isEqualTo("first@example.com");
        assertThat(observation.sameInstance()).isTrue();
        assertThat(committedEmail).isEqualTo("bulk@example.com");
    }

    @Test
    void experiment2_clearAutomaticallyDetachesExistingEntity() {
        Long memberId = jdbcTemplate.queryForObject(
                "insert into members (id, email) values (nextval('member_sequence'), ?) returning id",
                Long.class,
                "first@example.com"
        );

        MemberService.BulkUpdateObservation observation =
                memberService.changeEmailWithBulkAndClear(memberId);

        String committedEmail = jdbcTemplate.queryForObject(
                "select email from members where id = ?",
                String.class,
                memberId
        );

        log.info("[observation] first email={}", observation.firstEmail());
        log.info("[observation] second email={}", observation.secondEmail());
        log.info("[observation] same instance={}", observation.sameInstance());
        log.info("[observation] committed email={}", committedEmail);

        assertThat(observation.updatedRows()).isEqualTo(1);
        assertThat(observation.firstEmail()).isEqualTo("first@example.com");
        assertThat(observation.secondEmail()).isEqualTo("bulk@example.com");
        assertThat(observation.sameInstance()).isFalse();
        assertThat(committedEmail).isEqualTo("bulk@example.com");
    }

    @Test
    void experiment3_refreshReloadsOneManagedEntity() {
        Long memberId = jdbcTemplate.queryForObject(
                "insert into members (id, email) values (nextval('member_sequence'), ?) returning id",
                Long.class,
                "first@example.com"
        );

        MemberService.RefreshObservation observation =
                memberService.changeEmailWithBulkAndRefresh(memberId);

        String committedEmail = jdbcTemplate.queryForObject(
                "select email from members where id = ?",
                String.class,
                memberId
        );

        log.info("[observation] before refresh={}", observation.beforeRefreshEmail());
        log.info("[observation] after refresh={}", observation.afterRefreshEmail());
        log.info("[observation] second email={}", observation.secondEmail());
        log.info("[observation] same instance={}", observation.sameInstance());
        log.info("[observation] committed email={}", committedEmail);

        assertThat(observation.updatedRows()).isEqualTo(1);
        assertThat(observation.beforeRefreshEmail()).isEqualTo("first@example.com");
        assertThat(observation.afterRefreshEmail()).isEqualTo("bulk@example.com");
        assertThat(observation.secondEmail()).isEqualTo("bulk@example.com");
        assertThat(observation.sameInstance()).isTrue();
        assertThat(committedEmail).isEqualTo("bulk@example.com");
    }

    @Test
    void experiment4_bulkDeleteLeavesStaleManagedEntity() {
        Long memberId = jdbcTemplate.queryForObject(
                "insert into members (id, email) values (nextval('member_sequence'), ?) returning id",
                Long.class,
                "first@example.com"
        );

        RuntimeException propagated = null;
        try {
            memberService.deleteAndChangeManagedEntity(memberId);
            log.info("[checkpoint] service returned normally");
        } catch (RuntimeException exception) {
            propagated = exception;
            log.info("[checkpoint] caught by caller: {}", exception.getClass().getSimpleName());
        }

        Long rowCount = jdbcTemplate.queryForObject(
                "select count(*) from members where id = ?",
                Long.class,
                memberId
        );
        String committedEmail = jdbcTemplate.queryForObject(
                "select email from members where id = ?",
                String.class,
                memberId
        );

        log.info("[observation] committed row count={}", rowCount);
        log.info("[observation] committed email={}", committedEmail);

        assertThat(propagated).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(rowCount).isEqualTo(1L);
        assertThat(committedEmail).isEqualTo("first@example.com");
    }
}
