package com.ontop.transfers.adapter.in.web;

import com.ontop.transfers.application.port.in.ExecuteTransferCommand;
import com.ontop.transfers.application.port.in.ExecuteTransferUseCase;
import com.ontop.transfers.application.port.in.GetTransferUseCase;
import com.ontop.transfers.application.port.in.TransferOutcome;
import com.ontop.transfers.domain.model.Money;
import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Until authentication is in place the user is identified by the {@code X-User-Id} header; with JWT it would come
 * from the token subject and nothing else in this controller would change.
 */
@RestController
@RequestMapping("/v1/transfers")
public class TransferController {

    static final String USER_HEADER = "X-User-Id";
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ExecuteTransferUseCase executeTransfer;
    private final GetTransferUseCase getTransfer;

    public TransferController(ExecuteTransferUseCase executeTransfer, GetTransferUseCase getTransfer) {
        this.executeTransfer = executeTransfer;
        this.getTransfer = getTransfer;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> execute(
            @RequestHeader(USER_HEADER) @Positive long userId,
            @RequestHeader(IDEMPOTENCY_HEADER) @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        ExecuteTransferCommand command = new ExecuteTransferCommand(userId, request.recipientId(),
                Money.of(request.amount(), request.currency()), idempotencyKey);

        TransferOutcome outcome = executeTransfer.execute(command);
        Transfer transfer = outcome.transfer();
        TransferResponse body = TransferResponse.from(transfer);
        if (outcome.replayed()) {
            return ResponseEntity.ok(body);
        }
        HttpStatus status = transfer.status() == TransferStatus.IN_REVIEW ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(transfer.id()).toUri();
        return ResponseEntity.status(status).location(location).body(body);
    }

    @GetMapping("/{transferId}")
    public TransferResponse get(@RequestHeader(USER_HEADER) @Positive long userId, @PathVariable UUID transferId) {
        return TransferResponse.from(getTransfer.get(userId, transferId));
    }
}
