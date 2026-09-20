package com.utils;

import com.service.BusinessException;
import java.security.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Passwords {

    /** PBKDF2-HMAC-SHA256 baseline; existing weaker hashes upgrade after successful login. */
    private static final int ITERATIONS = 600_000;

    private static final java.util.concurrent.Semaphore HASH_SLOTS = new java.util.concurrent.Semaphore(4);

    /**
     * Curated subset of the most-leaked passwords. A real deployment would
     * ship a full top-10k list as a classpath resource; this catches the
     * common offenders without bloating the WAR.
     */
    private static final Set<String> COMMON = Set.of(
                    "password",
                    "password1",
                    "password123",
                    "p@ssw0rd",
                    "p@ssword1",
                    "12345678",
                    "123456789",
                    "1234567890",
                    "00000000",
                    "11111111",
                    "qwerty",
                    "qwerty123",
                    "abc12345",
                    "abcdefgh",
                    "asdfghjk",
                    "admin",
                    "admin123",
                    "admin1234",
                    "root",
                    "root123",
                    "welcome",
                    "welcome1",
                    "letmein",
                    "iloveyou",
                    "monkey123",
                    "dragon",
                    "master",
                    "starwars",
                    "trustno1",
                    "bailey",
                    "student",
                    "student123",
                    "teacher",
                    "teacher123")
            .stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    public static String hash(String password) {
        assertSafe(password);
        return hashVerifiedPassword(password);
    }

    /** Re-encode an already verified legacy password without imposing a new enrollment policy. */
    public static String hashVerifiedPassword(String password) {
        Validation.password(password);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$v1$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    public static boolean verify(String password, String encoded) {
        if (password == null || password.length() > 128 || encoded == null) {
            return false;
        }
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 5 || !"pbkdf2-sha256".equals(parts[0]) || !"v1".equals(parts[1])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[2]);
            if (iterations < 100_000 || iterations > 2_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] expected = Base64.getDecoder().decode(parts[4]);
            return salt.length == 16
                    && expected.length == 32
                    && MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns true if the encoded hash uses fewer PBKDF2 iterations than the
     * current setting. Lets the service rotate hashes transparently on
     * successful login without prompting the user.
     */
    public static boolean needsRehash(String encoded) {
        if (encoded == null) {
            return false;
        }
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 5) {
                return false;
            }
            int iterations = Integer.parseInt(parts[2]);
            return iterations < ITERATIONS;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Validates password policy at every entry point: register, change,
     * reset. Throws BusinessException on violation.
     */
    public static void assertSafe(String password) {
        Validation.password(password);
        if (COMMON.contains(password.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(400, Messages.ERR_PASSWORD_COMMON);
        }
    }

    /** Reject passwords equal to the login name or student number. */
    public static void assertNotEqualToLogin(String password, String loginHint) {
        if (loginHint != null && password != null && password.equalsIgnoreCase(loginHint)) {
            throw new BusinessException(400, Messages.ERR_PASSWORD_USERNAME);
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        if (!HASH_SLOTS.tryAcquire()) {
            throw new BusinessException(429, Messages.ERR_RATE_LIMIT);
        }
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing unavailable", e);
        } finally {
            spec.clearPassword();
            HASH_SLOTS.release();
        }
    }
}
