package com.example.jpalab.lab4;

import com.example.jpalab.query.Team;
import com.example.jpalab.query.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class QueryPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(QueryPerformanceTest.class);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    TeamRepository teamRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    EntityManager entityManager;

    Statistics statistics;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from lab4_team_members");
        jdbcTemplate.update("delete from lab4_teams");

        insertTeams(3, 2);

        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    private void insertTeams(int teamCount, int membersPerTeam) {
        for (int teamNumber = 1; teamNumber <= teamCount; teamNumber++) {
            Long teamId = jdbcTemplate.queryForObject(
                    "insert into lab4_teams (id, name) values (nextval('team_sequence'), ?) returning id",
                    Long.class,
                    "team-" + teamNumber
            );

            for (int memberNumber = 1; memberNumber <= membersPerTeam; memberNumber++) {
                jdbcTemplate.update(
                        """
                        insert into lab4_team_members (id, name, team_id)
                        values (nextval('team_member_sequence'), ?, ?)
                        """,
                        "member-" + teamNumber + "-" + memberNumber,
                        teamId
                );
            }
        }
    }

    @Test
    void experiment1_lazyCollectionsCauseNPlusOneQueries() {
        List<String> memberNames = transactionTemplate.execute(status -> {
            List<Team> teams = teamRepository.findAllByOrderByIdAsc();
            log.info("[checkpoint] teams loaded={}", teams.size());

            return teams.stream()
                    .flatMap(team -> team.getMembers().stream())
                    .map(member -> member.getName())
                    .toList();
        });

        long sqlCount = statistics.getPrepareStatementCount();
        int returnedRows = 3 + memberNames.size();

        log.info("[observation] SQL count={}", sqlCount);
        log.info("[observation] returned rows={}", returnedRows);

        assertThat(memberNames).hasSize(6);
        assertThat(sqlCount).isEqualTo(4);
        assertThat(returnedRows).isEqualTo(9);
    }

    @Test
    void experiment2_collectionFetchJoinUsesOneQueryWithRepeatedParentRows() {
        FetchJoinResult result = transactionTemplate.execute(status -> {
            List<Team> teams = teamRepository.findAllWithMembers();

            List<String> memberNames = teams.stream()
                    .flatMap(team -> team.getMembers().stream())
                    .map(member -> member.getName())
                    .toList();

            return new FetchJoinResult(teams.size(), memberNames);
        });

        long sqlCount = statistics.getPrepareStatementCount();
        int databaseRows = result.memberNames().size();

        log.info("[observation] SQL count={}", sqlCount);
        log.info("[observation] database join rows={}", databaseRows);
        log.info("[observation] Java team count={}", result.teamCount());
        log.info("[observation] Java member count={}", result.memberNames().size());

        assertThat(sqlCount).isEqualTo(1);
        assertThat(databaseRows).isEqualTo(6);
        assertThat(result.teamCount()).isEqualTo(3);
        assertThat(result.memberNames()).hasSize(6);
    }

    @Test
    void experiment3_collectionFetchJoinPaginationLoadsAllRowsBeforePaging() {
        jdbcTemplate.update("delete from lab4_team_members");
        jdbcTemplate.update("delete from lab4_teams");
        insertTeams(10, 2);
        statistics.clear();

        PageResult result = transactionTemplate.execute(status -> {
            Page<Team> page = teamRepository.findPageWithMembers(PageRequest.of(0, 3));

            int memberCount = page.getContent().stream()
                    .mapToInt(team -> team.getMembers().size())
                    .sum();

            return new PageResult(
                    page.getNumberOfElements(),
                    page.getTotalElements(),
                    memberCount
            );
        });

        long sqlCount = statistics.getPrepareStatementCount();
        long loadedEntityCount = statistics.getEntityLoadCount();

        log.info("[observation] SQL count={}", sqlCount);
        log.info("[observation] entities loaded before in-memory paging={}", loadedEntityCount);
        log.info("[observation] page team count={}", result.teamCount());
        log.info("[observation] page member count={}", result.memberCount());
        log.info("[observation] total team count={}", result.totalTeamCount());

        assertThat(sqlCount).isEqualTo(2);
        assertThat(loadedEntityCount).isEqualTo(30);
        assertThat(result.teamCount()).isEqualTo(3);
        assertThat(result.memberCount()).isEqualTo(6);
        assertThat(result.totalTeamCount()).isEqualTo(10);
    }

    @Test
    void experiment4_parentPaginationAndBatchFetchingLimitLoadedRows() {
        jdbcTemplate.update("delete from lab4_team_members");
        jdbcTemplate.update("delete from lab4_teams");
        insertTeams(10, 2);
        statistics.clear();

        PageResult result = transactionTemplate.execute(status -> {
            entityManager.unwrap(Session.class).setFetchBatchSize(3);

            Page<Team> page = teamRepository.findAll(PageRequest.of(0, 3));

            int memberCount = page.getContent().stream()
                    .mapToInt(team -> team.getMembers().size())
                    .sum();

            return new PageResult(
                    page.getNumberOfElements(),
                    page.getTotalElements(),
                    memberCount
            );
        });

        long sqlCount = statistics.getPrepareStatementCount();
        long loadedEntityCount = statistics.getEntityLoadCount();
        int databaseRows = result.teamCount() + 1 + result.memberCount();

        log.info("[observation] SQL count={}", sqlCount);
        log.info("[observation] database rows={}", databaseRows);
        log.info("[observation] loaded entity count={}", loadedEntityCount);

        assertThat(sqlCount).isEqualTo(3);
        assertThat(databaseRows).isEqualTo(10);
        assertThat(loadedEntityCount).isEqualTo(9);
        assertThat(result.teamCount()).isEqualTo(3);
        assertThat(result.memberCount()).isEqualTo(6);
        assertThat(result.totalTeamCount()).isEqualTo(10);
    }

    @Test
    void experiment5_idPaginationThenFetchJoinKeepsParentPageBoundary() {
        jdbcTemplate.update("delete from lab4_team_members");
        jdbcTemplate.update("delete from lab4_teams");
        insertTeams(10, 2);
        statistics.clear();

        PageResult result = transactionTemplate.execute(status -> {
            Page<Long> idPage = teamRepository.findPageIds(PageRequest.of(0, 3));
            List<Team> teams = teamRepository.findAllWithMembersByIdIn(idPage.getContent());

            int memberCount = teams.stream()
                    .mapToInt(team -> team.getMembers().size())
                    .sum();

            return new PageResult(
                    teams.size(),
                    idPage.getTotalElements(),
                    memberCount
            );
        });

        long sqlCount = statistics.getPrepareStatementCount();
        long loadedEntityCount = statistics.getEntityLoadCount();
        int databaseRows = result.teamCount() + 1 + result.memberCount();

        log.info("[observation] SQL count={}", sqlCount);
        log.info("[observation] database rows={}", databaseRows);
        log.info("[observation] loaded entity count={}", loadedEntityCount);

        assertThat(sqlCount).isEqualTo(3);
        assertThat(databaseRows).isEqualTo(10);
        assertThat(loadedEntityCount).isEqualTo(9);
        assertThat(result.teamCount()).isEqualTo(3);
        assertThat(result.memberCount()).isEqualTo(6);
        assertThat(result.totalTeamCount()).isEqualTo(10);
    }

    private record FetchJoinResult(int teamCount, List<String> memberNames) {
    }

    private record PageResult(int teamCount, long totalTeamCount, int memberCount) {
    }
}
