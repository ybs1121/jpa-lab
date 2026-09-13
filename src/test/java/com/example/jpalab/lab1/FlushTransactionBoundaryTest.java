package com.example.jpalab.lab1;

import com.example.jpalab.member.Member;
import com.example.jpalab.member.MemberRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @Disabled("예상 답변을 기록한 뒤 실행한다")
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
    }
}
