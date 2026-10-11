package com.example.transactions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Lightweight, in-memory transaction processor.
 *
 * Ordering assumption: sequence numbers start at 1 and are consecutive per
 * account. Business idempotency key: transactionId. A transactionId may be
 * applied only once. This implementation is single-process and intended for an
 * assessment, not multi-node production use.
 */
public final class TransactionProcessor {
	private static final Logger LOG = Logger.getLogger(TransactionProcessor.class.getName());

	private final int maxAttempts;
	private final Map<String, TransactionRecord> recordsByTransactionId = new LinkedHashMap<>();
	private final Map<String, BigDecimal> balances = new HashMap<>();
	private final Map<String, TreeMap<Long, TransactionRecord>> pendingByAccount = new HashMap<>();
	private final Map<String, Long> nextSequenceByAccount = new HashMap<>();
	private final Map<String, String> currencyByAccount = new HashMap<>();

	public TransactionProcessor(int maxAttempts) {
		if (maxAttempts < 1)
			throw new IllegalArgumentException("maxAttempts must be >= 1");
		this.maxAttempts = maxAttempts;
	}

	public synchronized List<TransactionRecord> processBatch(List<TransactionRequest> requests) {
		List<TransactionRecord> batchRecords = new ArrayList<>();
		for (TransactionRequest request : requests) {
			TransactionRecord record = receive(request);
			batchRecords.add(record);
		}
		drainAllAccounts();
		return List.copyOf(batchRecords);
	}

	/** Adds one record and attempts all newly-unblocked work. */
	public synchronized TransactionRecord receive(TransactionRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		String error = request.validationError();
		if (error != null) {
			TransactionRecord invalid = new TransactionRecord(request);
			invalid.status(TransactionStatus.FAILED);
			invalid.failureReason(error);
			LOG.warning("Rejected transactionId=" + safe(request.transactionId()) + " reason=" + error);
			return invalid;
		}

		TransactionRecord existing = recordsByTransactionId.get(request.transactionId());
		if (existing != null) {
			TransactionRecord duplicate = new TransactionRecord(request);
			duplicate.status(TransactionStatus.DUPLICATE);
			duplicate.failureReason("transactionId already received; original status=" + existing.status());
			LOG.info("Duplicate transactionId=" + request.transactionId());
			return duplicate;
		}

		String accountCurrency = currencyByAccount.get(request.accountId());
		if (accountCurrency != null && !accountCurrency.equals(request.currency())) {
			TransactionRecord invalid = new TransactionRecord(request);
			invalid.status(TransactionStatus.FAILED);
			invalid.failureReason("currency mismatch for account");
			return invalid;
		}
		currencyByAccount.putIfAbsent(request.accountId(), request.currency());

		/*
		 * TransactionRecord record = new TransactionRecord(request);
		 * recordsByTransactionId.put(request.transactionId(), record);
		 * pendingByAccount.computeIfAbsent(request.accountId(), ignored -> new
		 * TreeMap<>()) .put(request.sequenceNumber(), record);
		 * nextSequenceByAccount.putIfAbsent(request.accountId(), 1L);
		 * LOG.info("Received transactionId=" + request.transactionId() + " sequence=" +
		 * request.sequenceNumber()); return record;
		 */
		TransactionRecord record = new TransactionRecord(request);

		recordsByTransactionId.put(request.transactionId(), record);

		pendingByAccount.computeIfAbsent(request.accountId(), ignored -> new TreeMap<>()).put(request.sequenceNumber(),
				record);

		nextSequenceByAccount.putIfAbsent(request.accountId(), 1L);

		LOG.info("Received transactionId=" + request.transactionId() + " sequence=" + request.sequenceNumber());

		// NEW: Process transactions whenever the next expected
		// sequence number is available.
		drainAccount(request.accountId());

		return record;
	}

	/**
	 * Retries a RETRY_PENDING record; safe because effects are committed only on
	 * success.
	 */
	public synchronized TransactionRecord retry(String transactionId) {
		TransactionRecord record = recordsByTransactionId.get(transactionId);
		if (record == null)
			throw new IllegalArgumentException("Unknown transactionId: " + transactionId);
		if (record.status() != TransactionStatus.RETRY_PENDING)
			return record;
		attempt(record);
		if (record.status() == TransactionStatus.PROCESSED) {
			removePending(record);
			advanceSequence(record);
			drainAccount(record.request().accountId());
		}
		return record;
	}

	private void drainAllAccounts() {
		// Deterministic account iteration makes assessment output reproducible.
		List<String> accounts = new ArrayList<>(pendingByAccount.keySet());
		accounts.sort(Comparator.naturalOrder());
		for (String account : accounts)
			drainAccount(account);
	}

	private void drainAccount(String accountId) {
		TreeMap<Long, TransactionRecord> pending = pendingByAccount.get(accountId);
		if (pending == null)
			return;
		while (true) {
			long expected = nextSequenceByAccount.getOrDefault(accountId, 1L);
			TransactionRecord record = pending.get(expected);
			if (record == null)
				return; // gap: keep later events pending
			if (record.status() == TransactionStatus.RETRY_PENDING)
				return;
			if (record.status() == TransactionStatus.FAILED) {
				// A terminal sequence failure blocks this account's stream; operator action is
				// needed.
				return;
			}
			if (record.status() == TransactionStatus.PROCESSED) {
				pending.remove(expected);
				advanceSequence(record);
				continue;
			}
			attempt(record);
			if (record.status() != TransactionStatus.PROCESSED)
				return;
			pending.remove(expected);
			advanceSequence(record);
		}
	}

	private void attempt(TransactionRecord record) {
		TransactionRequest request = record.request();
		record.status(TransactionStatus.PROCESSING);
		record.incrementAttempts();

		if (record.attempts() <= request.simulatedFailures()) {
			record.status(
					record.attempts() >= maxAttempts ? TransactionStatus.FAILED : TransactionStatus.RETRY_PENDING);
			record.failureReason("simulated transient failure on attempt " + record.attempts());
			LOG.warning("Transient failure transactionId=" + request.transactionId() + " attempt=" + record.attempts()
					+ " status=" + record.status());
			return;
		}

		BigDecimal current = balances.getOrDefault(request.accountId(), BigDecimal.ZERO);
		BigDecimal newBalance;
		if (request.type() == TransactionType.CREDIT) {
			newBalance = current.add(request.amount());
		} else {
			if (current.compareTo(request.amount()) < 0) {
				record.status(TransactionStatus.FAILED);
				record.failureReason("insufficient balance");
				LOG.warning("Business failure transactionId=" + request.transactionId());
				return;
			}
			newBalance = current.subtract(request.amount());
		}

		// In-memory atomic critical section (method is synchronized); production needs
		// DB transaction.
		balances.put(request.accountId(), newBalance);
		record.status(TransactionStatus.PROCESSED);
		record.failureReason(null);
		LOG.info("Processed transactionId=" + request.transactionId() + " status=PROCESSED attempt="
				+ record.attempts());
	}

	private void removePending(TransactionRecord record) {
		String account = record.request().accountId();
		TreeMap<Long, TransactionRecord> pending = pendingByAccount.get(account);
		if (pending != null)
			pending.remove(record.request().sequenceNumber());
	}

	private void advanceSequence(TransactionRecord record) {
		String account = record.request().accountId();
		nextSequenceByAccount.put(account, record.request().sequenceNumber() + 1);
	}

	public synchronized BigDecimal balance(String accountId) {
		return balances.getOrDefault(accountId, BigDecimal.ZERO);
	}

	public synchronized List<TransactionRecord> records() {
		return List.copyOf(recordsByTransactionId.values());
	}

	public synchronized ProcessingSummary summary() {
		return ProcessingSummary.from(recordsByTransactionId.values(), balances);
	}

	public synchronized List<String> pendingGaps() {
		List<String> gaps = new ArrayList<>();
		pendingByAccount.forEach((account, pending) -> {
			if (!pending.isEmpty()) {
				long expected = nextSequenceByAccount.getOrDefault(account, 1L);
				long firstPending = pending.firstKey();
				if (firstPending > expected)
					gaps.add(account + ": missing sequence " + expected + " before pending sequence " + firstPending);
				else if (pending.get(expected) != null
						&& pending.get(expected).status() == TransactionStatus.RETRY_PENDING)
					gaps.add(account + ": sequence " + expected + " awaiting retry");
				else if (pending.get(expected) != null && pending.get(expected).status() == TransactionStatus.FAILED)
					gaps.add(account + ": sequence " + expected + " failed; stream blocked");
			}
		});
		return List.copyOf(gaps);
	}

	private static String safe(String value) {
		return value == null ? "<missing>" : value;
	}
}
