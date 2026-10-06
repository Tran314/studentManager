package com.utils;

import com.service.BusinessException;
import java.util.Locale;

public final class Validation {

    private Validation() {}

    /** Shared login/limiter policy: reject Unicode input that can alias an ASCII account in MySQL. */
    public static String canonicalUsername(String value) {
        if (value == null || value.length() > Limits.USERNAME_MAX * 2) {
            return null;
        }
        String clean = value.strip();
        if (clean.length() > Limits.USERNAME_MAX || !clean.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        return clean.toLowerCase(Locale.ROOT);
    }

    public static String text(String value, String label, int max, boolean required) {
        String clean = value == null ? "" : value.strip();
        if ((required && clean.isEmpty()) || clean.codePointCount(0, clean.length()) > max) {
            throw new BusinessException(400, label + (required ? "不能为空，且" : "") + "不能超过" + max + "字。");
        }
        return clean;
    }

    public static int positiveInt(String value, String label, int max) {
        try {
            if (value == null || !value.matches("[0-9]{1,10}")) {
                throw new NumberFormatException();
            }
            int number = Integer.parseInt(value);
            if (number < 1 || number > max) {
                throw new NumberFormatException();
            }
            return number;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, label + "必须是1–" + max + "的整数。");
        }
    }

    public static int page(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Long.parseLong(value)));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "页码必须是整数。");
        }
    }

    public static void password(String password) {
        if (password == null || password.length() < Limits.PASSWORD_MIN || password.length() > Limits.PASSWORD_MAX) {
            throw new BusinessException(400, Messages.ERR_PASSWORD_LENGTH);
        }
    }
}
