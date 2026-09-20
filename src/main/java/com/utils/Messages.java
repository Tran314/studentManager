package com.utils;

/**
 * Central registry of user-facing Chinese strings. Right now these are
 * plain constants; the migration path to {@code ResourceBundle}-backed
 * i18n is a near-mechanical replacement (each constant becomes a
 * {@code messages.getString("...")} lookup). Keeping them in one file
 * is the precondition that makes that swap a one-pass change instead of
 * a grep-and-pray refactor.
 *
 * <p>Conventions: ERR_* for user-visible error messages, TITLE_* for
 * flash / page titles. Strings that depend on runtime constants
 * (Limits.PASSWORD_MIN etc.) are computed in static initializers so
 * callers see a final value.
 */
public final class Messages {

    private Messages() {
    }

    // ---- Service-layer error messages ----
    public static final String ERR_INVALID_SNO = "学号必须为正整数。";
    public static final String ERR_AGE_RANGE =
            "年龄必须为" + Limits.AGE_MIN + "–" + Limits.AGE_MAX + "的整数。";
    public static final String ERR_DUPLICATE_SNO = "学号已存在，请使用其他学号。";
    public static final String ERR_DUPLICATE_LOGIN = "学号或登录名已存在，请使用其他学号。";
    public static final String ERR_CONSTRAINT = "提交的数据不符合约束要求。";
    public static final String ERR_STUDENT_NOT_FOUND = "学生记录不存在。";
    public static final String ERR_STUDENT_ACCOUNT_NOT_FOUND = "学生账号不存在。";
    public static final String ERR_INVALID_CREDENTIAL = "登录名或密码不正确。";
    public static final String ERR_SESSION_EXPIRED = "登录已过期，请重新登录。";
    public static final String ERR_OLD_PASSWORD = "当前密码不正确。";
    public static final String ERR_ADMIN_ONLY_STUDENTS = "仅管理员可以访问学生管理功能。";
    public static final String ERR_ADMIN_USE_DIRECTORY = "管理员请通过学生管理查看档案。";
    public static final String ERR_ADMIN_ONLY_RESET = "仅管理员可以重置学生密码。";
    public static final String ERR_PASSWORD_MISMATCH = "两次输入的新密码不一致。";

    // ---- Filter / infrastructure ----
    public static final String ERR_INTERNAL = "暂时无法完成操作，请稍后重试。";
    public static final String ERR_CSRF = "表单已过期，请刷新页面后重试。";
    public static final String ERR_METHOD_NOT_ALLOWED_GET = "请通过表单提交";
    public static final String ERR_METHOD_NOT_ALLOWED_POST = "不支持此操作";
    public static final String ERR_PUBLIC_REGISTRATION_CLOSED =
            "公开注册已关闭。学生账号由管理员创建，请联系管理员。";

    // ---- Rate limit (P0-2) ----
    public static final String ERR_RATE_LIMIT = "尝试过于频繁，请稍后重试。";
    public static final String ERR_RATE_LIMIT_LOCKED = "连续失败次数过多，已临时锁定，请稍后重试。";
    /** Prefix for the dynamic "请约 N 秒后再试" message; caller appends seconds + suffix. */
    public static final String ERR_RATE_LIMIT_LOCKED_PREFIX = "尝试过于频繁，已临时锁定，请约 ";

    // ---- Password policy (P4-3) ----
    public static final String ERR_PASSWORD_LENGTH =
            "密码长度必须为" + Limits.PASSWORD_MIN + "–" + Limits.PASSWORD_MAX + "个字符。";
    public static final String ERR_PASSWORD_USERNAME = "密码不能与登录名或学号相同。";
    public static final String ERR_PASSWORD_COMMON = "密码过于简单，请使用字母、数字与符号的组合。";

    // ---- Titles / flash messages ----
    public static final String TITLE_LOGIN = "欢迎回来";
    public static final String TITLE_LOGIN_SUCCESS = "登录成功，欢迎回来。";
    public static final String TITLE_LOGOUT = "已安全退出。";
    public static final String TITLE_STUDENT_SAVED = "学生资料已保存。";
    public static final String TITLE_STUDENT_DELETED = "学生及关联账号已删除。";
    public static final String TITLE_PASSWORD_CHANGED = "密码已修改，请重新登录。";
    public static final String TITLE_PASSWORD_RESET = "学生密码已重置，该学生的其他会话已失效。";
    public static final String TITLE_FORM_CHECK = "请检查学生资料";
}
