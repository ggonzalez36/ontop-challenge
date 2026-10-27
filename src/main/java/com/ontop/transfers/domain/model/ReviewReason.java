package com.ontop.transfers.domain.model;

/**
 * Why a transfer is in {@link TransferStatus#IN_REVIEW}. Only {@link #PAYMENT_UNKNOWN} can be resolved automatically
 * (by asking the provider); the others need someone to check the wallet.
 */
public enum ReviewReason {
    /** The wallet debit call failed ambiguously: the balance may or may not have changed. */
    WALLET_DEBIT_UNKNOWN,
    /** The wallet was debited and the payment call failed ambiguously: the payment may exist. */
    PAYMENT_UNKNOWN,
    /** The payment failed and the refund call failed ambiguously: the refund may have happened. */
    REFUND_UNKNOWN,
    /** The process stopped between persisting the transfer and confirming the wallet debit. */
    INTERRUPTED
}
