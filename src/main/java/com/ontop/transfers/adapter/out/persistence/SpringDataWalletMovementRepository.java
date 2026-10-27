package com.ontop.transfers.adapter.out.persistence;

import com.ontop.transfers.application.port.out.TransactionHistoryRepository.MovementType;
import com.ontop.transfers.domain.model.TransferStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface SpringDataWalletMovementRepository extends JpaRepository<WalletMovementEntity, Long> {

    String SELECT_ROWS = """
            select m.id as id, m.transferId as transferId, m.type as type, m.amount as amount,
                   m.currency as currency, t.status as transferStatus, t.recipientName as recipientName,
                   m.occurredAt as occurredAt
            from WalletMovementEntity m, TransferJpaEntity t
            where t.id = m.transferId and m.userId = :userId
            """;
    String NEWEST_FIRST = " order by m.occurredAt desc, m.id desc";

    @Query(SELECT_ROWS + NEWEST_FIRST)
    List<HistoryRow> firstPage(@Param("userId") long userId, Limit limit);

    @Query(SELECT_ROWS + " and (m.occurredAt < :at or (m.occurredAt = :at and m.id < :id))" + NEWEST_FIRST)
    List<HistoryRow> pageAfter(@Param("userId") long userId, @Param("at") Instant at, @Param("id") long id,
                               Limit limit);

    interface HistoryRow {
        Long getId();

        UUID getTransferId();

        MovementType getType();

        BigDecimal getAmount();

        String getCurrency();

        TransferStatus getTransferStatus();

        String getRecipientName();

        Instant getOccurredAt();
    }
}
