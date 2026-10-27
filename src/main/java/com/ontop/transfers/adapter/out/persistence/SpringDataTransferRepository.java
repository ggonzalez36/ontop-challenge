package com.ontop.transfers.adapter.out.persistence;

import com.ontop.transfers.domain.model.TransferStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataTransferRepository extends JpaRepository<TransferJpaEntity, UUID> {

    Optional<TransferJpaEntity> findByUserIdAndIdempotencyKey(long userId, String idempotencyKey);

    List<TransferJpaEntity> findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(Collection<TransferStatus> statuses,
                                                                                Instant before, Limit limit);

    /**
     * A closed transfer must have credited and then debited exactly its amount on the in-transit account.
     */
    @Query("""
            select t.id from TransferJpaEntity t
            where t.status in :closed
              and ((select coalesce(sum(l.credit), 0) from LedgerEntryEntity l
                    where l.transferId = t.id and l.account = :transit) <> t.amount
                or (select coalesce(sum(l.debit), 0) from LedgerEntryEntity l
                    where l.transferId = t.id and l.account = :transit) <> t.amount)
            """)
    List<UUID> findClosedWithUnbalancedTransit(@Param("closed") Collection<TransferStatus> closed,
                                               @Param("transit") String transitAccount);

    @Query("""
            select t.status as status, count(t) as total from TransferJpaEntity t
            where t.status in :open and t.updatedAt < :cutoff
            group by t.status
            """)
    List<StatusCount> countByStatusNotUpdatedSince(@Param("open") Collection<TransferStatus> open,
                                                   @Param("cutoff") Instant cutoff);

    interface StatusCount {
        TransferStatus getStatus();

        long getTotal();
    }
}
