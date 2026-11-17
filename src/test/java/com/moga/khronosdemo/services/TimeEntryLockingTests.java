package com.moga.khronosdemo.services;

import com.moga.khronosdemo.dtos.request.ClockInRequestDTO;
import com.moga.khronosdemo.entities.Establishment;
import com.moga.khronosdemo.entities.Worker;
import com.moga.khronosdemo.exceptions.BusinessRuleException;
import com.moga.khronosdemo.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A worker can only have one open time entry, even when two clock-ins arrive at the same moment.
 */
class TimeEntryLockingTests extends IntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2030, 5, 10);

    @Autowired
    private TimeEntryService timeEntryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final CountDownLatch firstDone = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);

    private Worker worker;

    @BeforeEach
    void setUp() {
        Establishment venue = createEstablishment(createOwner("owner"));
        worker = createWorker(venue, "worker");
    }

    @AfterEach
    void stopThreads() throws InterruptedException {
        releaseFirst.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void aSecondClockInWaitsForTheFirstAndThenFails() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Future<?> first = pool.submit(() -> transaction.executeWithoutResult(status -> {
            timeEntryService.clockIn(worker.getUserId(), new ClockInRequestDTO(null));
            firstDone.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(firstDone.await(10, TimeUnit.SECONDS), "the first clock-in did not finish");

        Future<?> second = pool.submit(() -> timeEntryService.clockIn(worker.getUserId(), new ClockInRequestDTO(null)));
        Thread.sleep(500);
        assertFalse(second.isDone(), "the second clock-in should wait until the first one commits");

        releaseFirst.countDown();
        first.get(10, TimeUnit.SECONDS);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
        assertInstanceOf(BusinessRuleException.class, failure.getCause());
        assertEquals(1, timeEntryRepository.count());
    }

    @Test
    void twoClockInsSentTogetherSaveOnlyOneEntry() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        List<Future<?>> attempts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            attempts.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return timeEntryService.clockIn(worker.getUserId(), new ClockInRequestDTO(null));
            }));
        }

        int won = 0;
        for (Future<?> attempt : attempts) {
            try {
                attempt.get(10, TimeUnit.SECONDS);
                won++;
            } catch (ExecutionException ex) {
                assertInstanceOf(BusinessRuleException.class, ex.getCause());
            }
        }
        assertEquals(1, won);
        assertEquals(1, timeEntryRepository.count());
    }

    @Test
    void theDatabaseRejectsASecondOpenEntryEvenWithoutTheServiceChecks() {
        createTimeEntry(worker, null, DAY, "2030-05-10T16:00:00Z", null, 0);

        assertThrows(DataIntegrityViolationException.class,
                () -> createTimeEntry(worker, null, DAY, "2030-05-10T18:00:00Z", null, 0));
        assertEquals(1, timeEntryRepository.count());

        // A closed entry does not count as open
        createTimeEntry(worker, null, DAY, "2030-05-10T08:00:00Z", "2030-05-10T12:00:00Z", 0);
        assertEquals(2, timeEntryRepository.count());
    }
}
