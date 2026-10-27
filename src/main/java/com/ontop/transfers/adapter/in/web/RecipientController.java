package com.ontop.transfers.adapter.in.web;

import com.ontop.transfers.application.port.in.ManageRecipientsUseCase;
import com.ontop.transfers.application.port.in.ManageRecipientsUseCase.CreateRecipientCommand;
import com.ontop.transfers.domain.model.Recipient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

/**
 * Saved bank accounts. Responses never include the national id or the full account number.
 */
@RestController
@RequestMapping("/v1/recipients")
public class RecipientController {

    private final ManageRecipientsUseCase recipients;

    public RecipientController(ManageRecipientsUseCase recipients) {
        this.recipients = recipients;
    }

    @PostMapping
    public ResponseEntity<RecipientResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody CreateRecipientRequest request) {
        Recipient recipient = recipients.create(new CreateRecipientCommand(CurrentUser.id(jwt), request.firstName(),
                request.lastName(), request.nationalId(), request.routingNumber(), request.accountNumber(),
                request.bankName(), request.currency() == null ? "USD" : request.currency()));
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}")
                .buildAndExpand(recipient.id()).toUri()).body(RecipientResponse.from(recipient));
    }

    @GetMapping
    public List<RecipientResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return recipients.list(CurrentUser.id(jwt)).stream().map(RecipientResponse::from).toList();
    }

    @DeleteMapping("/{recipientId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID recipientId) {
        recipients.delete(CurrentUser.id(jwt), recipientId);
        return ResponseEntity.noContent().build();
    }

    public record CreateRecipientRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(max = 50) String nationalId,
            @NotBlank @Pattern(regexp = "\\d{9}", message = "must have 9 digits") String routingNumber,
            @NotBlank @Pattern(regexp = "\\d{4,17}", message = "must have 4 to 17 digits") String accountNumber,
            @NotBlank @Size(max = 100) String bankName,
            @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code") String currency) {
    }

    public record RecipientResponse(UUID id, String firstName, String lastName, String bankName,
                                    String routingNumber, String accountLast4, String currency) {

        static RecipientResponse from(Recipient recipient) {
            return new RecipientResponse(recipient.id(), recipient.firstName(), recipient.lastName(),
                    recipient.bankAccount().bankName(), recipient.bankAccount().routingNumber(),
                    recipient.snapshot().accountLast4(), recipient.bankAccount().currency().getCurrencyCode());
        }
    }
}
