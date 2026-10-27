package com.ontop.transfers.config;

import com.ontop.transfers.adapter.out.payments.PaymentsHttpAdapter;
import com.ontop.transfers.adapter.out.payments.PaymentsProperties;
import com.ontop.transfers.adapter.out.wallet.WalletHttpAdapter;
import com.ontop.transfers.adapter.out.wallet.WalletProperties;
import com.ontop.transfers.application.port.in.ApplyPaymentOutcomeUseCase;
import com.ontop.transfers.application.port.in.ExecuteTransferUseCase;
import com.ontop.transfers.application.port.in.GetTransactionHistoryUseCase;
import com.ontop.transfers.application.port.in.GetTransferUseCase;
import com.ontop.transfers.application.port.in.ManageRecipientsUseCase;
import com.ontop.transfers.application.port.in.QuoteTransferUseCase;
import com.ontop.transfers.application.port.in.ReconcileTransfersUseCase;
import com.ontop.transfers.application.port.in.RecoverStuckTransfersUseCase;
import com.ontop.transfers.application.port.out.ReconciliationRepository;
import com.ontop.transfers.application.port.out.RecipientRepository;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.application.service.ApplyPaymentOutcomeService;
import com.ontop.transfers.application.service.ExecuteTransferService;
import com.ontop.transfers.application.service.GetTransferService;
import com.ontop.transfers.application.service.PaymentOutcomeHandler;
import com.ontop.transfers.application.service.QuoteTransferService;
import com.ontop.transfers.application.service.RecipientService;
import com.ontop.transfers.application.service.ReconciliationService;
import com.ontop.transfers.application.service.TransactionHistoryService;
import com.ontop.transfers.application.service.TransferRecoveryService;
import com.ontop.transfers.domain.fee.FeePolicy;
import com.ontop.transfers.domain.fee.PercentageFeePolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Currency;

/**
 * The only place where the application core meets Spring: use cases are plain classes wired here to their ports.
 */
@Configuration(proxyBeanMethods = false)
public class BeanConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FeePolicy feePolicy(FeeProperties properties) {
        return new PercentageFeePolicy(properties.withdrawalRate());
    }

    @Bean
    WalletHttpAdapter walletHttpAdapter(RestClient.Builder builder, WalletProperties properties,
                                        CircuitBreakerRegistry circuitBreakers) {
        RestClient client = builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties.connectTimeout(), properties.readTimeout()))
                .build();
        return new WalletHttpAdapter(client, circuitBreakers.circuitBreaker("wallet"),
                Currency.getInstance(properties.currency()));
    }

    @Bean
    PaymentsHttpAdapter paymentsHttpAdapter(RestClient.Builder builder, PaymentsProperties properties,
                                            CircuitBreakerRegistry circuitBreakers) {
        RestClient client = builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties.connectTimeout(), properties.readTimeout()))
                .build();
        return new PaymentsHttpAdapter(client, circuitBreakers.circuitBreaker("payments"), properties.source(),
                properties.lookupEnabled());
    }

    @Bean
    PaymentOutcomeHandler paymentOutcomeHandler(TransferRepository transfers, WalletHttpAdapter wallet) {
        return new PaymentOutcomeHandler(transfers, wallet);
    }

    @Bean
    ExecuteTransferUseCase executeTransferUseCase(RecipientRepository recipients, TransferRepository transfers,
                                                  WalletHttpAdapter wallet, PaymentsHttpAdapter payments,
                                                  PaymentOutcomeHandler outcomes, FeePolicy feePolicy, Clock clock) {
        return new ExecuteTransferService(recipients, transfers, wallet, wallet, payments, outcomes, feePolicy, clock);
    }

    @Bean
    GetTransferUseCase getTransferUseCase(TransferRepository transfers) {
        return new GetTransferService(transfers);
    }

    @Bean
    QuoteTransferUseCase quoteTransferUseCase(WalletHttpAdapter wallet, FeePolicy feePolicy) {
        return new QuoteTransferService(wallet, feePolicy);
    }

    @Bean
    ManageRecipientsUseCase manageRecipientsUseCase(RecipientRepository recipients) {
        return new RecipientService(recipients);
    }

    @Bean
    GetTransactionHistoryUseCase getTransactionHistoryUseCase(TransactionHistoryRepository history) {
        return new TransactionHistoryService(history);
    }

    @Bean
    ApplyPaymentOutcomeUseCase applyPaymentOutcomeUseCase(TransferRepository transfers,
                                                          PaymentOutcomeHandler outcomes) {
        return new ApplyPaymentOutcomeService(transfers, outcomes);
    }

    @Bean
    RecoverStuckTransfersUseCase recoverStuckTransfersUseCase(TransferRepository transfers,
                                                              PaymentsHttpAdapter payments,
                                                              PaymentOutcomeHandler outcomes, Clock clock,
                                                              JobsProperties jobs) {
        return new TransferRecoveryService(transfers, payments, outcomes, clock, jobs.stuckAfter(), jobs.batchSize());
    }

    @Bean
    ReconcileTransfersUseCase reconcileTransfersUseCase(ReconciliationRepository reconciliation, Clock clock,
                                                        JobsProperties jobs) {
        return new ReconciliationService(reconciliation, clock, jobs.openTooLong());
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
