package com.example.jpalab.lab3;

import com.example.jpalab.stock.Stock;
import com.example.jpalab.stock.StockRepository;
import com.example.jpalab.stock.VersionedStock;
import com.example.jpalab.stock.VersionedStockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class StockConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(StockConcurrencyTest.class);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    StockRepository stockRepository;

    @Autowired
    VersionedStockRepository versionedStockRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from stocks");
        jdbcTemplate.update("delete from versioned_stocks");
    }

    @Test
    void experiment1_lostUpdateWithoutConcurrencyControl() throws Exception {
        Long stockId = jdbcTemplate.queryForObject(
                "insert into stocks (id, quantity) values (nextval('stock_sequence'), ?) returning id",
                Long.class,
                10
        );

        CountDownLatch bothTransactionsRead = new CountDownLatch(2);
        CountDownLatch firstTransactionHoldsRowLock = new CountDownLatch(1);
        CountDownLatch secondTransactionStartsUpdate = new CountDownLatch(1);
        CountDownLatch allowFirstTransactionToCommit = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                Stock stock = stockRepository.findById(stockId).orElseThrow();
                log.info("[T1] selected quantity={}", stock.getQuantity());

                bothTransactionsRead.countDown();
                await(bothTransactionsRead);

                stock.decrease();
                log.info("[T1] object quantity={}", stock.getQuantity());

                stockRepository.flush();
                log.info("[T1] update executed, row lock held");

                firstTransactionHoldsRowLock.countDown();
                await(allowFirstTransactionToCommit);
            }));

            Future<?> second = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                Stock stock = stockRepository.findById(stockId).orElseThrow();
                log.info("[T2] selected quantity={}", stock.getQuantity());

                bothTransactionsRead.countDown();
                await(bothTransactionsRead);
                await(firstTransactionHoldsRowLock);

                stock.decrease();
                log.info("[T2] object quantity={}", stock.getQuantity());

                secondTransactionStartsUpdate.countDown();
                stockRepository.flush();
                log.info("[T2] update completed after T1 commit");
            }));

            await(secondTransactionStartsUpdate);

            boolean secondTransactionWaitedForLock;
            try {
                secondTransactionWaitedForLock = waitForBlockedStatement(
                        "stocks",
                        "update",
                        Duration.ofSeconds(5)
                );
            } finally {
                allowFirstTransactionToCommit.countDown();
            }

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            Integer finalQuantity = jdbcTemplate.queryForObject(
                    "select quantity from stocks where id = ?",
                    Integer.class,
                    stockId
            );

            log.info("[observation] T2 waited for row lock={}", secondTransactionWaitedForLock);
            log.info("[observation] final quantity={}", finalQuantity);

            assertThat(secondTransactionWaitedForLock).isTrue();
            assertThat(finalQuantity).isEqualTo(9);
        } finally {
            allowFirstTransactionToCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void experiment2_optimisticLockDetectsLostUpdate() throws Exception {
        Long stockId = jdbcTemplate.queryForObject(
                """
                insert into versioned_stocks (id, quantity, version)
                values (nextval('versioned_stock_sequence'), ?, ?)
                returning id
                """,
                Long.class,
                10,
                0
        );

        CountDownLatch bothTransactionsRead = new CountDownLatch(2);
        CountDownLatch firstTransactionHoldsRowLock = new CountDownLatch(1);
        CountDownLatch secondTransactionStartsUpdate = new CountDownLatch(1);
        CountDownLatch allowFirstTransactionToCommit = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                VersionedStock stock = versionedStockRepository.findById(stockId).orElseThrow();
                log.info("[T1] selected quantity={}, version={}", stock.getQuantity(), stock.getVersion());

                bothTransactionsRead.countDown();
                await(bothTransactionsRead);

                stock.decrease();
                log.info("[T1] object quantity={}, version={}", stock.getQuantity(), stock.getVersion());

                versionedStockRepository.flush();
                log.info("[T1] update executed, row lock held");

                firstTransactionHoldsRowLock.countDown();
                await(allowFirstTransactionToCommit);
            }));

            Future<RuntimeException> second = executor.submit(() -> {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        VersionedStock stock = versionedStockRepository.findById(stockId).orElseThrow();
                        log.info("[T2] selected quantity={}, version={}", stock.getQuantity(), stock.getVersion());

                        bothTransactionsRead.countDown();
                        await(bothTransactionsRead);
                        await(firstTransactionHoldsRowLock);

                        stock.decrease();
                        log.info("[T2] object quantity={}, version={}", stock.getQuantity(), stock.getVersion());

                        secondTransactionStartsUpdate.countDown();
                        versionedStockRepository.flush();
                        log.info("[T2] update completed");
                    });

                    return null;
                } catch (RuntimeException exception) {
                    log.info("[T2] failed: {}", exception.getClass().getSimpleName());
                    return exception;
                }
            });

            await(secondTransactionStartsUpdate);

            boolean secondTransactionWaitedForLock;
            try {
                secondTransactionWaitedForLock = waitForBlockedStatement(
                        "versioned_stocks",
                        "update",
                        Duration.ofSeconds(5)
                );
            } finally {
                allowFirstTransactionToCommit.countDown();
            }

            first.get(5, TimeUnit.SECONDS);
            RuntimeException secondFailure = second.get(5, TimeUnit.SECONDS);

            StockState finalState = jdbcTemplate.queryForObject(
                    "select quantity, version from versioned_stocks where id = ?",
                    (resultSet, rowNumber) -> new StockState(
                            resultSet.getInt("quantity"),
                            resultSet.getLong("version")
                    ),
                    stockId
            );

            log.info("[observation] T2 waited for row lock={}", secondTransactionWaitedForLock);
            log.info(
                    "[observation] T2 failure={}",
                    secondFailure == null ? "none" : secondFailure.getClass().getSimpleName()
            );
            log.info(
                    "[observation] final quantity={}, version={}",
                    finalState.quantity(),
                    finalState.version()
            );

            assertThat(secondTransactionWaitedForLock).isTrue();
            assertThat(secondFailure).isInstanceOf(ObjectOptimisticLockingFailureException.class);
            assertThat(finalState.quantity()).isEqualTo(9);
            assertThat(finalState.version()).isEqualTo(1L);
        } finally {
            allowFirstTransactionToCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void experiment3_pessimisticLockSerializesFromSelect() throws Exception {
        Long stockId = jdbcTemplate.queryForObject(
                "insert into stocks (id, quantity) values (nextval('stock_sequence'), ?) returning id",
                Long.class,
                10
        );

        CountDownLatch firstTransactionHoldsRowLock = new CountDownLatch(1);
        CountDownLatch secondTransactionStartsSelect = new CountDownLatch(1);
        CountDownLatch allowFirstTransactionToCommit = new CountDownLatch(1);

        AtomicInteger firstSelectedQuantity = new AtomicInteger();
        AtomicInteger secondSelectedQuantity = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                Stock stock = stockRepository.findByIdForUpdate(stockId).orElseThrow();
                firstSelectedQuantity.set(stock.getQuantity());
                log.info("[T1] selected for update, quantity={}", stock.getQuantity());

                stock.decrease();
                stockRepository.flush();
                log.info("[T1] updated quantity={}, row lock held", stock.getQuantity());

                firstTransactionHoldsRowLock.countDown();
                await(allowFirstTransactionToCommit);
            }));

            Future<?> second = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                await(firstTransactionHoldsRowLock);

                secondTransactionStartsSelect.countDown();
                Stock stock = stockRepository.findByIdForUpdate(stockId).orElseThrow();
                secondSelectedQuantity.set(stock.getQuantity());
                log.info("[T2] select completed, quantity={}", stock.getQuantity());

                stock.decrease();
                stockRepository.flush();
                log.info("[T2] updated quantity={}", stock.getQuantity());
            }));

            await(secondTransactionStartsSelect);

            boolean secondTransactionWaitedAtSelect;
            try {
                secondTransactionWaitedAtSelect = waitForBlockedStatement(
                        "stocks",
                        "select",
                        Duration.ofSeconds(5)
                );
            } finally {
                allowFirstTransactionToCommit.countDown();
            }

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            Integer finalQuantity = jdbcTemplate.queryForObject(
                    "select quantity from stocks where id = ?",
                    Integer.class,
                    stockId
            );

            log.info("[observation] T2 waited at select={}", secondTransactionWaitedAtSelect);
            log.info("[observation] T1 selected quantity={}", firstSelectedQuantity.get());
            log.info("[observation] T2 selected quantity={}", secondSelectedQuantity.get());
            log.info("[observation] final quantity={}", finalQuantity);

            assertThat(secondTransactionWaitedAtSelect).isTrue();
            assertThat(firstSelectedQuantity.get()).isEqualTo(10);
            assertThat(secondSelectedQuantity.get()).isEqualTo(9);
            assertThat(finalQuantity).isEqualTo(8);
        } finally {
            allowFirstTransactionToCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void experiment4_conditionalUpdateUsesAffectedRowsAsTheResult() throws Exception {
        Long stockId = jdbcTemplate.queryForObject(
                "insert into stocks (id, quantity) values (nextval('stock_sequence'), ?) returning id",
                Long.class,
                1
        );

        CountDownLatch firstTransactionHoldsRowLock = new CountDownLatch(1);
        CountDownLatch secondTransactionStartsUpdate = new CountDownLatch(1);
        CountDownLatch allowFirstTransactionToCommit = new CountDownLatch(1);

        AtomicInteger firstAffectedRows = new AtomicInteger(-1);
        AtomicInteger secondAffectedRows = new AtomicInteger(-1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                int affectedRows = stockRepository.decreaseAtomically(stockId);
                firstAffectedRows.set(affectedRows);
                log.info("[T1] conditional update affected rows={}", affectedRows);

                firstTransactionHoldsRowLock.countDown();
                await(allowFirstTransactionToCommit);
            }));

            Future<?> second = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                await(firstTransactionHoldsRowLock);

                secondTransactionStartsUpdate.countDown();
                int affectedRows = stockRepository.decreaseAtomically(stockId);
                secondAffectedRows.set(affectedRows);
                log.info("[T2] conditional update affected rows={}", affectedRows);
            }));

            await(secondTransactionStartsUpdate);

            boolean secondTransactionWaitedForLock;
            try {
                secondTransactionWaitedForLock = waitForBlockedStatement(
                        "stocks",
                        "update",
                        Duration.ofSeconds(5)
                );
            } finally {
                allowFirstTransactionToCommit.countDown();
            }

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            Integer finalQuantity = jdbcTemplate.queryForObject(
                    "select quantity from stocks where id = ?",
                    Integer.class,
                    stockId
            );

            log.info("[observation] T2 waited for row lock={}", secondTransactionWaitedForLock);
            log.info("[observation] T1 affected rows={}", firstAffectedRows.get());
            log.info("[observation] T2 affected rows={}", secondAffectedRows.get());
            log.info("[observation] final quantity={}", finalQuantity);

            assertThat(secondTransactionWaitedForLock).isTrue();
            assertThat(firstAffectedRows.get()).isEqualTo(1);
            assertThat(secondAffectedRows.get()).isZero();
            assertThat(finalQuantity).isZero();
        } finally {
            allowFirstTransactionToCommit.countDown();
            executor.shutdownNow();
        }
    }

    private boolean waitForBlockedStatement(
            String tableName,
            String statementKeyword,
            Duration timeout
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
            Integer blockedUpdates = jdbcTemplate.queryForObject(
                    """
                    select count(*)
                      from pg_stat_activity
                     where datname = current_database()
                       and wait_event_type = 'Lock'
                       and query ilike ?
                    """,
                    Integer.class,
                    "%" + statementKeyword + "%" + tableName + "%"
            );

            if (blockedUpdates != null && blockedUpdates > 0) {
                return true;
            }

            Thread.sleep(20);
        }

        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency checkpoint timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency checkpoint interrupted", exception);
        }
    }

    private record StockState(int quantity, long version) {
    }
}
