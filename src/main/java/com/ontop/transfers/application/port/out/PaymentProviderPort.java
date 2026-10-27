package com.ontop.transfers.application.port.out;

/**
 * Bank transfer from Ontop's account (configured in the adapter) to the recipient's account.
 */
public interface PaymentProviderPort {

    PaymentResult pay(PaymentOrder order);
}
