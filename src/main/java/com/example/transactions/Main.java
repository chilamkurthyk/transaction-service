package com.example.transactions;

import java.math.BigDecimal;
import java.util.List;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        TransactionProcessor processor = new TransactionProcessor(3);

        List<TransactionRequest> input = List.of(
                tx("TXN-2002", "REQ-2", "ACC-201", TransactionType.DEBIT, "40", 2, 0),
                tx("TXN-2001", "REQ-1", "ACC-201", TransactionType.CREDIT, "100", 1, 0),
                tx("TXN-2002", "REQ-2-DUP", "ACC-201", TransactionType.DEBIT, "40", 2, 0),
                tx("TXN-2003", "REQ-3", "ACC-201", TransactionType.CREDIT, "25", 3, 0),
                tx("TXN-2004", "REQ-4", "ACC-202", TransactionType.DEBIT, "-10", 1, 0),
                tx("TXN-2005", "REQ-5", "ACC-203", TransactionType.CREDIT, "500", 1, 1)
        );

        processor.processBatch(input);
        processor.retry("TXN-2005");

        System.out.println("\n--- Transaction records ---");
        processor.records().forEach(System.out::println);
        System.out.println("\n--- Pending gaps / retries ---");
        processor.pendingGaps().forEach(System.out::println);
        System.out.println("\n--- Processing summary ---");
        System.out.println(processor.summary());
    }

    private static TransactionRequest tx(String id, String requestId, String account,
                                         TransactionType type, String amount, long sequence,
                                         int simulatedFailures) {
        return new TransactionRequest(id, requestId, account, type,
                new BigDecimal(amount), "USD", sequence, simulatedFailures);
    }
}
