package com.ontop.transfers.adapter.out.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Encrypts a column transparently. Hibernate obtains it from Spring (SpringBeanContainer), so the cipher is injected.
 * The domain never sees ciphertext and never knows encryption exists.
 */
@Converter
class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final PiiCipher cipher;

    EncryptedStringConverter(PiiCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : cipher.decrypt(dbData);
    }
}
