package com.ontop.transfers;

import com.ontop.transfers.domain.fee.PercentageFeePolicy;
import com.ontop.transfers.domain.model.BankAccount;
import com.ontop.transfers.domain.model.IdempotencyKey;
import com.ontop.transfers.domain.model.Money;
import com.ontop.transfers.domain.model.Recipient;
import com.ontop.transfers.domain.model.Transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

public final class TestData {

    public static final long USER_ID = 1000L;
    public static final Currency USD = Currency.getInstance("USD");
    public static final UUID SEEDED_RECIPIENT_ID = UUID.fromString("6f1c2b8e-3a4d-4e5f-9a6b-7c8d9e0f1a2b");
    public static final PercentageFeePolicy TEN_PERCENT = new PercentageFeePolicy(new BigDecimal("0.10"));
    public static final Instant NOW = Instant.parse("2026-10-27T15:00:00Z");

    private TestData() {
    }

    public static Money usd(String amount) {
        return Money.of(amount, "USD");
    }

    public static Recipient recipient() {
        return recipient(USER_ID);
    }

    public static Recipient recipient(long userId) {
        return new Recipient(SEEDED_RECIPIENT_ID, userId, "Lucho", "Rodriguez", "3327809",
                new BankAccount("211927207", "1885226711", "Bank of America", USD));
    }

    public static Transfer newTransfer(String amount) {
        return newTransfer(USER_ID, amount, "key-" + UUID.randomUUID());
    }

    public static Transfer newTransfer(long userId, String amount, String idempotencyKey) {
        return Transfer.create(userId, recipient(userId), usd(amount), usd("99924.00"), TEN_PERCENT,
                new IdempotencyKey(idempotencyKey, "fingerprint"), NOW);
    }
}
