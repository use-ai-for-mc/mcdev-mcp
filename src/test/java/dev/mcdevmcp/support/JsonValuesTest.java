package dev.mcdevmcp.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonValuesTest {
    private static void addValue(Map<String, Object> values) {
        values.put("later", true);
    }

    @Test
    void rejectsNonFiniteFloatingPointNumbers() {
        assertThrows(IllegalArgumentException.class, () -> JsonValues.freeze(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> JsonValues.freeze(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> JsonValues.freeze(Float.NEGATIVE_INFINITY));
    }

    @Test
    void preservesFiniteNumbersNullsAndNestedImmutability() {
        var largeInteger = new BigInteger("1234567890123456789012345678901234567890");
        var preciseDecimal = new BigDecimal("1234567890.012345678901234567890");
        var nested = new LinkedHashMap<String, Object>();
        nested.put("missing", null);
        nested.put("numbers", new ArrayList<>(List.of(1, largeInteger, preciseDecimal, 1.5D, 2.5F)));
        Map<String, Object> frozen = JsonValues.freezeMap(nested);

        assertNull(frozen.get("missing"));
        var numbers = (List<?>) frozen.get("numbers");
        assertSame(largeInteger, numbers.get(1));
        assertSame(preciseDecimal, numbers.get(2));
        assertEquals(1.5D, numbers.get(3));
        assertEquals(2.5F, numbers.get(4));
        assertThrows(UnsupportedOperationException.class, () -> addValue(frozen));
        assertThrows(UnsupportedOperationException.class, numbers::clear);
    }
}
