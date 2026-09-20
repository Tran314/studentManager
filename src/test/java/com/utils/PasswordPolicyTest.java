package com.utils;

import static org.junit.jupiter.api.Assertions.*;

import com.service.BusinessException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    @Test
    void upgradesOldHashesWithoutInvalidatingThem() throws Exception {
        byte[] salt = new byte[16];
        PBEKeySpec spec = new PBEKeySpec("Legacy-secret-2026".toCharArray(), salt, 210_000, 256);
        String old = "pbkdf2-sha256$v1$210000$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder()
                        .encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                                .generateSecret(spec)
                                .getEncoded());
        spec.clearPassword();
        assertTrue(Passwords.verify("Legacy-secret-2026", old));
        assertTrue(Passwords.needsRehash(old));
        String current = Passwords.hash("Legacy-secret-2026");
        assertTrue(current.contains("$600000$"));
        assertFalse(Passwords.needsRehash(current));
        assertFalse(Passwords.needsRehash(null));
        assertFalse(Passwords.needsRehash("bad"));
        assertFalse(Passwords.needsRehash("pbkdf2-sha256$v1$bad$a$b"));
    }

    @Test
    void policyAndMalformedHashesFailSafely() {
        assertThrows(BusinessException.class, () -> Passwords.hash("PASSWORD123"));
        assertThrows(BusinessException.class, () -> Passwords.assertNotEqualToLogin("ADMIN1234", "admin1234"));
        Passwords.assertNotEqualToLogin(null, "admin");
        Passwords.assertNotEqualToLogin("different", null);
        assertFalse(Passwords.verify("x".repeat(129), "bad"));
        assertFalse(Passwords.verify("valid", null));
        assertFalse(Passwords.verify("valid", "pbkdf2-sha256$v1$abc$a$b"));
        assertFalse(Passwords.verify("valid", "pbkdf2-sha256$v1$600000$!$!"));
        assertFalse(Passwords.verify("valid", "pbkdf2-sha256$v1$999$a$b"));
        assertFalse(Passwords.verify("valid", "pbkdf2-sha256$v1$600000$aaaa$bbbb"));
    }
}
