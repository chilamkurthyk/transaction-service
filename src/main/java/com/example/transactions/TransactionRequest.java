package com.example.transactions;

import java.math.BigDecimal;
import java.util.Objects;

public record TransactionRequest(
        String transactionId,
        String requestId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        long sequenceNumber,
        int simulatedFailures
) {
    public TransactionRequest {
        // Validation is performed by TransactionProcessor so malformed records can be
        // isolated and reported rather than throwing while a batch is being created.
    }

    public String validationError() {
        if (isBlank(transactionId)) return "transactionId is required";
        if (isBlank(requestId)) return "requestId is required";
        if (isBlank(accountId)) return "accountId is required";
        if (type == null) return "transaction type is required";
        if (amount == null || amount.signum() <= 0) return "amount must be greater than zero";
        if (isBlank(currency) || !currency.matches("[A-Z]{3}")) return "currency must be a 3-letter uppercase code";
        if (sequenceNumber < 1) return "sequenceNumber must be >= 1";
        if (simulatedFailures < 0) return "simulatedFailures cannot be negative";
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
