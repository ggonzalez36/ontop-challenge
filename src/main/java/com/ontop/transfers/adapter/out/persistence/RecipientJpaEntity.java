package com.ontop.transfers.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recipient")
class RecipientJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "national_id", nullable = false)
    private String nationalId;

    @Column(name = "routing_number", nullable = false)
    private String routingNumber;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected RecipientJpaEntity() {
        // for JPA
    }

    RecipientJpaEntity(UUID id, long userId, String firstName, String lastName, String nationalId,
                       String routingNumber, String accountNumber, String bankName, String currency,
                       Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.nationalId = nationalId;
        this.routingNumber = routingNumber;
        this.accountNumber = accountNumber;
        this.bankName = bankName;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    void markDeleted(Instant at) {
        this.deletedAt = at;
    }

    UUID getId() {
        return id;
    }

    long getUserId() {
        return userId;
    }

    String getFirstName() {
        return firstName;
    }

    String getLastName() {
        return lastName;
    }

    String getNationalId() {
        return nationalId;
    }

    String getRoutingNumber() {
        return routingNumber;
    }

    String getAccountNumber() {
        return accountNumber;
    }

    String getBankName() {
        return bankName;
    }

    String getCurrency() {
        return currency;
    }
}
