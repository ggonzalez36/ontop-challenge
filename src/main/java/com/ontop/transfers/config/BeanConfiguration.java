package com.ontop.transfers.config;

import com.ontop.transfers.adapter.out.payments.PaymentsHttpAdapter;
import com.ontop.transfers.adapter.out.payments.PaymentsProperties;
import com.ontop.transfers.adapter.out.wallet.WalletHttpAdapter;
import com.ontop.transfers.adapter.out.wallet.WalletProperties;
import com.ontop.transfers.application.port.in.ExecuteTransferUseCase;
import com.ontop.transfers.application.port.in.GetTransferUseCase;
import com.ontop.transfers.application.port.out.RecipientRepository;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.application.service.ExecuteTransferService;
import com.ontop.transfers.application.service.GetTransferService;
import com.ontop.transfers.domain.fee.FeePolicy;
import com.ontop.transfers.domain.fee.PercentageFeePolicy;
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
    WalletHttpAdapter walletHttpAdapter(RestClient.Builder builder, WalletProperties properties) {
        RestClient client = builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties.connectTimeout(), properties.readTimeout()))
                .build();
        return new WalletHttpAdapter(client, Currency.getInstance(properties.currency()));
    }

    @Bean
    PaymentsHttpAdapter paymentsHttpAdapter(RestClient.Builder builder, PaymentsProperties properties) {
        RestClient client = builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties.connectTimeout(), properties.readTimeout()))
                .build();
        return new PaymentsHttpAdapter(client, properties.source());
    }

    @Bean
    ExecuteTransferUseCase executeTransferUseCase(RecipientRepository recipients, TransferRepository transfers,
                                                  WalletHttpAdapter wallet, PaymentsHttpAdapter payments,
                                                  FeePolicy feePolicy, Clock clock) {
        return new ExecuteTransferService(recipients, transfers, wallet, wallet, payments, feePolicy, clock);
    }

    @Bean
    GetTransferUseCase getTransferUseCase(TransferRepository transfers) {
        return new GetTransferService(transfers);
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
