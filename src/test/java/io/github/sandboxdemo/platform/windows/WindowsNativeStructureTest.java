package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeMappedConverter;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WindowsNativeStructureTest {

    @Test
    void nativeAclStructuresMatchWindowsAbi() {
        int expectedTrusteeSize = Native.POINTER_SIZE == 8 ? 32 : 20;
        int expectedExplicitAccessSize = Native.POINTER_SIZE == 8 ? 48 : 32;

        assertEquals(expectedTrusteeSize, new WindowsNative.TRUSTEE().size());
        assertEquals(expectedExplicitAccessSize, new WindowsNative.EXPLICIT_ACCESS().size());
        assertEquals(Native.POINTER_SIZE, new WindowsNative.TOKEN_DEFAULT_DACL().size());
    }

    @Test
    void nativeLsaStructuresAndStringLengthsMatchAbi() {
        int expectedObjectAttributesSize = Native.POINTER_SIZE == 8 ? 48 : 24;
        int expectedUnicodeStringSize = Native.POINTER_SIZE == 8 ? 16 : 8;
        String value = "SeInteractiveLogonRight";
        int lengthBytes = value.length() * Native.WCHAR_SIZE;
        Memory buffer = WindowsNative.wideString(value);
        WindowsNative.LSA_UNICODE_STRING string =
                new WindowsNative.LSA_UNICODE_STRING(buffer, lengthBytes);

        assertEquals(
                expectedObjectAttributesSize, new WindowsNative.LSA_OBJECT_ATTRIBUTES().size());
        assertEquals(expectedUnicodeStringSize, string.size());
        assertEquals(lengthBytes, Short.toUnsignedInt(string.Length));
        assertEquals(lengthBytes + Native.WCHAR_SIZE, Short.toUnsignedInt(string.MaximumLength));
    }

    @Test
    void generatedCapabilityIsAValidAccountStyleSidString() {
        assertTrue(
                WindowsCapabilitySid.random()
                        .matches("S-1-5-21-(?:[1-9][0-9]{0,9}-){3}[1-9][0-9]{0,9}"));
    }

    @Test
    void productionRestrictedTokenIncludesDedicatedUserAndCompatibilitySids() {
        String capability = "S-1-5-21-1-2-3-4";
        String userSid = "S-1-5-21-5-6-7-8";
        String logonSid = "S-1-5-5-123-456";

        assertEquals(
                Arrays.asList(capability, userSid, logonSid, "S-1-1-0", "S-1-5-33"),
                WindowsRestrictedProcessLauncher.restrictionSids(
                        java.util.Collections.singletonList(capability), userSid, logonSid, true));
    }

    @Test
    void developmentRestrictedTokenDoesNotUseHostUserAsAWriteCapability() {
        String capability = "S-1-5-21-1-2-3-4";
        String userSid = "S-1-5-21-5-6-7-8";
        String logonSid = "S-1-5-5-123-456";

        assertEquals(
                Arrays.asList(capability, logonSid, "S-1-1-0", "S-1-5-33"),
                WindowsRestrictedProcessLauncher.restrictionSids(
                        java.util.Collections.singletonList(capability), userSid, logonSid, false));
    }

    @Test
    void sizeReferenceCanBeConstructedReflectivelyByJna() throws Exception {
        Class<WindowsNative.SIZE_TByReference> type = WindowsNative.SIZE_TByReference.class;
        java.lang.reflect.Constructor<WindowsNative.SIZE_TByReference> constructor =
                type.getConstructor();

        assertTrue(Modifier.isPublic(type.getModifiers()));
        assertTrue(Modifier.isPublic(constructor.getModifiers()));
        assertTrue(constructor.newInstance().getValue().longValue() >= 0);
        assertTrue(
                NativeMappedConverter.getInstance(type).defaultValue()
                        instanceof WindowsNative.SIZE_TByReference);
    }

    @Test
    void writeDenyMaskDoesNotOverlapReadExecuteRights() {
        assertEquals(
                0,
                WindowsAclManager.readExecuteMaskForTest()
                        & WindowsAclManager.denyWriteMaskForTest());
    }

    @Test
    void createAndModifyMaskDoesNotContainDeniedDeleteRights() {
        assertEquals(
                0,
                WindowsAclManager.writeWithoutDeleteMaskForTest()
                        & WindowsAclManager.denyDeleteMaskForTest());
    }
}
