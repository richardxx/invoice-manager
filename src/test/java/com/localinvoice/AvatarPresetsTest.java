package com.localinvoice;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AvatarPresetsTest {
    @Test void presetsAreValidAndRenderable() {
        assertEquals(16, AvatarPresets.options().size());
        assertTrue(AvatarPresets.options().stream().map(AvatarPresets.Option::mood).distinct().count() >= 8);
        assertTrue(AvatarPresets.options().stream().map(AvatarPresets.Option::gesture).distinct().count() >= 5);
        for (AvatarPresets.Option option : AvatarPresets.options()) {
            assertEquals(option.id(), AvatarPresets.valid(option.id()));
            assertEquals(36, AvatarPresets.icon(option.id(), 36).getIconWidth());
        }
        assertThrows(IllegalArgumentException.class, () -> AvatarPresets.valid("../../outside"));
    }
}
