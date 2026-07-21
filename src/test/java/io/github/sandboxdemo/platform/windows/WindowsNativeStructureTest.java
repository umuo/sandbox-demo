package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jna.Native;
import org.junit.jupiter.api.Test;

class WindowsNativeStructureTest {

    @Test
    void nativeAclStructuresMatchWindowsAbi() {
        int expectedTrusteeSize = Native.POINTER_SIZE == 8 ? 32 : 20;
        int expectedExplicitAccessSize = Native.POINTER_SIZE == 8 ? 48 : 32;

        assertEquals(expectedTrusteeSize, new WindowsNative.TRUSTEE().size());
        assertEquals(expectedExplicitAccessSize, new WindowsNative.EXPLICIT_ACCESS().size());
    }

    @Test
    void generatedCapabilityIsAValidAccountStyleSidString() {
        assertTrue(
                WindowsCapabilitySid.random()
                        .matches("S-1-5-21-(?:[1-9][0-9]{0,9}-){3}[1-9][0-9]{0,9}"));
    }
}
