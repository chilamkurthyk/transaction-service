package com.example.transactions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class TransactionProcessorTest {

    @Test
    void duplicateTransactionDoesNotApplyBalanceTwice() {
        TransactionProcessor processor = new TransactionProcessor(3);
        TransactionRequest original = tx("T1", "R1", "A1", TransactionType.CREDIT, "100", 1, 0);
        TransactionRequest duplicate = tx("T1", "R2", "A1", TransactionType.CREDIT, "100", 1, 0);

        List<TransactionRecord> result = processor.processBatch(List.of(original, duplicate));

        assertEquals(TransactionStatus.PROCESSED, result.get(0).status());
        assertEquals(TransactionStatus.DUPLICATE, result.get(1).status());
        assertEquals(new BigDecimal("100"), processor.balance("A1"));
    }

    @Test
    void buffersOutOfOrderTransactionUntilMissingSequenceArrives() {
        TransactionProcessor processor = new TransactionProcessor(3);
        TransactionRecord sequence2 = processor.receive(
                tx("T2", "R2", "A1", TransactionType.DEBIT, "40", 2, 0));
        assertEquals(TransactionStatus.RECEIVED, sequence2.status());
        assertEquals(new BigDecimal("0"), processor.balance("A1"));
        assertTrue(processor.pendingGaps().stream().anyMatch(s -> s.contains("missing sequence 1")));

        processor.receive(tx("T1", "R1", "A1", TransactionType.CREDIT, "100", 1, 0));

        assertEquals(TransactionStatus.PROCESSED, sequence2.status());
        assertEquals(new BigDecimal("60"), processor.balance("A1"));
    }

    @Test
    void invalidRecordFailsWithoutStoppingBatch() {
        TransactionProcessor processor = new TransactionProcessor(3);
        TransactionRecord invalid = processor.receive(
                tx("BAD", "RB", "A1", TransactionType.CREDIT, "-1", 1, 0));
        TransactionRecord valid = processor.receive(
                tx("GOOD", "RG", "A2", TransactionType.CREDIT, "10", 1, 0));

        assertEquals(TransactionStatus.FAILED, invalid.status());
        assertEquals(TransactionStatus.PROCESSED, valid.status());
        assertEquals(new BigDecimal("10"), processor.balance("A2"));
    }

    @Test
    void transientFailureCanBeRetriedWithoutDuplicateEffect() {
        TransactionProcessor processor = new TransactionProcessor(3);
        TransactionRecord record = processor.receive(
                tx("T1", "R1", "A1", TransactionType.CREDIT, "50", 1, 1));

        assertEquals(TransactionStatus.RETRY_PENDING, record.status());
        assertEquals(new BigDecimal("0"), processor.balance("A1"));

        processor.retry("T1");

        assertEquals(TransactionStatus.PROCESSED, record.status());
        assertEquals(2, record.attempts());
        assertEquals(new BigDecimal("50"), processor.balance("A1"));
    }

    @Test
    void retryExhaustionMarksRecordFailed() {
        TransactionProcessor processor = new TransactionProcessor(2);
        TransactionRecord record = processor.receive(
                tx("T1", "R1", "A1", TransactionType.CREDIT, "50", 1, 5));

        assertEquals(TransactionStatus.RETRY_PENDING, record.status());
        processor.retry("T1");

        assertEquals(TransactionStatus.FAILED, record.status());
        assertEquals(2, record.attempts());
        assertEquals(new BigDecimal("0"), processor.balance("A1"));
    }

    @Test
    void debitCannotOverdrawAccount() {
        TransactionProcessor processor = new TransactionProcessor(3);
        processor.processBatch(List.of(
                tx("T1", "R1", "A1", TransactionType.CREDIT, "20", 1, 0),
                tx("T2", "R2", "A1", TransactionType.DEBIT, "25", 2, 0)));

        assertEquals(new BigDecimal("20"), processor.balance("A1"));
        assertEquals(TransactionStatus.FAILED,
                processor.records().stream().filter(r -> r.request().transactionId().equals("T2"))
                        .findFirst().orElseThrow().status());
    }

    @Test
    void concurrentDuplicateSubmissionAppliesEffectOnce() throws Exception {
        TransactionProcessor processor = new TransactionProcessor(3);
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var tasks = java.util.stream.IntStream.range(0, 20).<java.util.concurrent.Callable<Void>>mapToObj(i -> () -> {
                start.await();
                processor.receive(tx("SAME", "R" + i, "A1", TransactionType.CREDIT, "10", 1, 0));
                return null;
            }).toList();
            var futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (var future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(new BigDecimal("10"), processor.balance("A1"));
    }

    private static TransactionRequest tx(String id, String requestId, String account,
                                         TransactionType type, String amount, long sequence,
                                         int simulatedFailures) {
        return new TransactionRequest(id, requestId, account, type,
                new BigDecimal(amount), "USD", sequence, simulatedFailures);
    }
}
