package com.utils;

import com.service.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {
    @Test void integerBoundsAndPageDefaults() {
        assertEquals(Integer.MAX_VALUE, Validation.positiveInt("2147483647", "学号", Integer.MAX_VALUE));
        for (String input : new String[]{"", "0", "-1", "2147483648", "abc", "1.5"})
            assertThrows(BusinessException.class, () -> Validation.positiveInt(input, "学号", Integer.MAX_VALUE));
        assertEquals(1, Validation.page(null));
        assertEquals(1, Validation.page("-8"));
        assertEquals(Integer.MAX_VALUE, Validation.page("999999999999"));
        assertThrows(BusinessException.class, () -> Validation.page("abc"));
    }
    @Test void unicodeAndLengthValidation() {
        assertEquals("张三", Validation.text(" 张三 ", "姓名", 20, true));
        assertEquals("", Validation.text(null, "地址", 50, false));
        assertThrows(BusinessException.class, () -> Validation.text("　 ", "姓名", 20, true));
        assertThrows(BusinessException.class, () -> Validation.text("学".repeat(21), "姓名", 20, true));
        assertEquals("😀".repeat(20), Validation.text("😀".repeat(20), "姓名", 20, true));
    }
}

