package com.ontop.transfers.config;

import com.ontop.transfers.application.port.out.RecipientRepository;
import com.ontop.transfers.domain.model.BankAccount;
import com.ontop.transfers.domain.model.Recipient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * Saved bank accounts for the mock wallet user 1000, so the Postman collection works out of the box. Loaded through
 * the repository (not SQL) because account and national id numbers are encrypted at rest.
 */
@Component
@ConditionalOnProperty(name = "ontop.demo-data.enabled", havingValue = "true")
public class DemoDataLoader implements ApplicationRunner {

    public static final long DEMO_USER_ID = 1000L;
    public static final UUID DEMO_RECIPIENT_ID = UUID.fromString("6f1c2b8e-3a4d-4e5f-9a6b-7c8d9e0f1a2b");
    private static final Currency USD = Currency.getInstance("USD");

    private final RecipientRepository recipients;

    public DemoDataLoader(RecipientRepository recipients) {
        this.recipients = recipients;
    }

    @Override
    public void run(ApplicationArguments args) {
        List.of(
                new Recipient(DEMO_RECIPIENT_ID, DEMO_USER_ID, "Lucho", "Rodriguez", "3327809",
                        new BankAccount("211927207", "1885226711", "Bank of America", USD)),
                new Recipient(UUID.fromString("0a9b8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d"), DEMO_USER_ID, "Lucho",
                        "Rodriguez", "3327809", new BankAccount("623852453", "2300299907", "Chase", USD)))
                .forEach(recipient -> {
                    if (recipients.findByIdAndUserId(recipient.id(), recipient.userId()).isEmpty()) {
                        recipients.save(recipient);
                    }
                });
    }
}
