package com.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordsTest {
    @Test void independentSaltsAndVerification() {
        String first = Passwords.hash("Correct-password-2026");
        String second = Passwords.hash("Correct-password-2026");
        assertNotEquals(first, second);
        assertTrue(Passwords.verify("Correct-password-2026", first));
        assertFalse(Passwords.verify("Wrong-password-2026", first));
        assertFalse(Passwords.verify(null, first));
    }
    @Test void rejectsMalformedAndUnboundedParameters() {
        assertFalse(Passwords.verify("abc", "plain"));
        assertFalse(Passwords.verify("abc", "pbkdf2-sha256$v1$2147483647$aaaa$bbbb"));
        assertFalse(Passwords.verify("abc", "pbkdf2-sha256$v2$600000$aaaa$bbbb"));
        assertThrows(com.service.BusinessException.class, () -> Passwords.hash("short"));
        assertThrows(com.service.BusinessException.class, () -> Passwords.hash("a".repeat(129)));
    }
}

