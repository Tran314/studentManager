package com.utils;

/**
 * Single source of truth for input limits. Java code uses the static constants
 * directly; JSPs read the same values via the bean exposed as the
 * {@code limits} application-scoped attribute (see {@link AppLifecycle}).
 */
public final class Limits {

    public static final int PAGE_SIZE = 10;
    public static final int NAME_MAX = 20;
    public static final int ADDRESS_MAX = 50;
    public static final int AGE_MIN = 1;
    public static final int AGE_MAX = 150;
    public static final int PASSWORD_MIN = 8;
    public static final int PASSWORD_MAX = 128;
    public static final int USERNAME_MAX = 64;
    public static final int SNO_DIGITS = 10;
    public static final int SNO_MAX = Integer.MAX_VALUE;

    private static final Limits INSTANCE = new Limits();

    public static Limits get() {
        return INSTANCE;
    }

    private Limits() {}

    public int getPageSize() {
        return PAGE_SIZE;
    }

    public int getNameMax() {
        return NAME_MAX;
    }

    public int getAddressMax() {
        return ADDRESS_MAX;
    }

    public int getAgeMin() {
        return AGE_MIN;
    }

    public int getAgeMax() {
        return AGE_MAX;
    }

    public int getPasswordMin() {
        return PASSWORD_MIN;
    }

    public int getPasswordMax() {
        return PASSWORD_MAX;
    }

    public int getUsernameMax() {
        return USERNAME_MAX;
    }

    public int getSnoDigits() {
        return SNO_DIGITS;
    }

    public int getSnoMax() {
        return SNO_MAX;
    }
}
