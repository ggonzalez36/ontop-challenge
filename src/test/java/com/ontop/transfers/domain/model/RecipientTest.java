package com.ontop.transfers.domain.model;

import com.ontop.transfers.domain.exception.InvalidRecipientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.ontop.transfers.TestData.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipientTest {

    @Test
    void createsRecipientFromScreenData() {
        Recipient recipient = Recipient.create(1000, " Lucho ", "Rodriguez", "3327809", "623852453", "2300299907",
                "Chase", USD);

        assertThat(recipient.id()).isNotNull();
        assertThat(recipient.fullName()).isEqualTo("Lucho Rodriguez");
        assertThat(recipient.snapshot().accountLast4()).isEqualTo("9907");
    }

    @ParameterizedTest(name = "{5}")
    @CsvSource({
            "'',      Rodriguez, 3327809, 623852453,  2300299907, First name",
            "Lucho,   Rodriguez, 3327809, 62385245,   2300299907, Routing number",
            "Lucho,   Rodriguez, 3327809, 62385245A,  2300299907, Routing number",
            "Lucho,   Rodriguez, 3327809, 623852453,  123,        Account number",
            "Lucho,   Rodriguez, '1 2',   623852453,  2300299907, National identification number"
    })
    void rejectsInvalidData(String first, String last, String nationalId, String routing, String account,
                            String expectedField) {
        assertThatThrownBy(() -> Recipient.create(1000, first, last, nationalId, routing, account, "Chase", USD))
                .isInstanceOf(InvalidRecipientException.class)
                .hasMessageStartingWith(expectedField);
    }
}
