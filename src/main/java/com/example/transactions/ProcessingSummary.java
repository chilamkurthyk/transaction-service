package com.example.transactions;

import java.util.EnumMap;
import java.util.Map;

public record ProcessingSummary(Map<TransactionStatus, Long> counts,
                                Map<String, String> accountBalances,
                                int totalRecords) {
    public static ProcessingSummary from(Iterable<TransactionRecord> records,
                                         Map<String, java.math.BigDecimal> balances) {
        Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus status : TransactionStatus.values()) counts.put(status, 0L);
        int total = 0;
        for (TransactionRecord record : records) {
            counts.merge(record.status(), 1L, Long::sum);
            total++;
        }
        Map<String, String> formattedBalances = new java.util.TreeMap<>();
        balances.forEach((account, amount) -> formattedBalances.put(account, amount.toPlainString()));
        return new ProcessingSummary(Map.copyOf(counts), Map.copyOf(formattedBalances), total);
    }
}
