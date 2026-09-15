package io.github.yuu518.hyperosime;

import org.junit.Test;

import static org.junit.Assert.*;

public class CompatibilityPolicyTest {
    @Test
    public void unsupportedOemActionsFallBackWithoutChangingSystemPreferences() {
        assertEquals("switch_input_method", CompatibilityPolicy.buttonFunction("voice_input", true));
        assertEquals("clipboard_phrase", CompatibilityPolicy.buttonFunction("switch_keyboard_language", false));
        assertEquals("switch_input_method", CompatibilityPolicy.buttonFunction(null, true));
        assertEquals("clipboard_phrase", CompatibilityPolicy.buttonFunction("clipboard_phrase", true));
        assertEquals("no_function", CompatibilityPolicy.buttonFunction("no_function", false));
    }

    @Test
    public void readerMustBeRegisteredCurrentImeWithMatchingUid() {
        assertTrue(CompatibilityPolicy.isReader(10500, 10500, "ime", "ime", true, true));
        assertFalse(CompatibilityPolicy.isReader(10500, 10500, "ime", "ime", false, true));
        assertFalse(CompatibilityPolicy.isReader(10500, 10500, "ime", "other", true, true));
        assertFalse(CompatibilityPolicy.isReader(10500, 10501, "ime", "ime", true, true));
        assertFalse(CompatibilityPolicy.isReader(10500, 10500, "ime", "ime", true, false));
        assertFalse(CompatibilityPolicy.isReader(1000, 1000, "ime", "ime", true, true));
        assertFalse(CompatibilityPolicy.isReader(10500, 10500, null, "ime", true, true));
    }
}
