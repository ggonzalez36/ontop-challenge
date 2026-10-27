package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.TransferNotFoundException;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.domain.model.Transfer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.ontop.transfers.TestData.USER_ID;
import static com.ontop.transfers.TestData.newTransfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetTransferServiceTest {

    private final TransferRepository transfers = mock(TransferRepository.class);
    private final GetTransferService service = new GetTransferService(transfers);

    @Test
    void returnsTransferOfTheUser() {
        Transfer transfer = newTransfer("20.00");
        when(transfers.findById(transfer.id())).thenReturn(Optional.of(transfer));

        assertThat(service.get(USER_ID, transfer.id())).isSameAs(transfer);
    }

    @Test
    void transferOfAnotherUserIsNotFound() {
        Transfer transfer = newTransfer("20.00");
        when(transfers.findById(transfer.id())).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.get(USER_ID + 1, transfer.id()))
                .isInstanceOf(TransferNotFoundException.class);
    }
}
