package com.example.transactions;

import java.time.Instant;

public final class TransactionRecord {
    private final TransactionRequest request;
    private TransactionStatus status = TransactionStatus.RECEIVED;
    private int attempts;
    private String failureReason;
    private final Instant receivedAt = Instant.now();

    public TransactionRecord(TransactionRequest request) {
        this.request = request;
    }

    public TransactionRequest request() { return request; }
    public TransactionStatus status() { return status; }
    public int attempts() { return attempts; }
    public String failureReason() { return failureReason; }
    public Instant receivedAt() { return receivedAt; }

    void status(TransactionStatus status) { this.status = status; }
    void incrementAttempts() { this.attempts++; }
    void failureReason(String reason) { this.failureReason = reason; }

    @Override
    public String toString() {
        return "TransactionRecord{transactionId='" + request.transactionId()
                + "', accountId='" + request.accountId()
                + "', sequence=" + request.sequenceNumber()
                + ", status=" + status
                + ", attempts=" + attempts
                + (failureReason == null ? "" : ", failureReason='" + failureReason + "'")
                + "}";
    }
}
