package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WindowsCommandLineTest {

    @Test
    void quotesWhitespaceQuotesAndTrailingBackslashes() {
        assertEquals("plain", WindowsCommandLine.quote("plain"));
        assertEquals("\"two words\"", WindowsCommandLine.quote("two words"));
        assertEquals("\"a\\\\\\\"b\"", WindowsCommandLine.quote("a\\\"b"));
        assertEquals("\"C:\\Program Files\\\\\"", WindowsCommandLine.quote("C:\\Program Files\\"));
    }

    @Test
    void nativeStructuresCanBeMarshalled() {
        assertDoesNotThrow(
                () -> {
                    WindowsNative.JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits =
                            new WindowsNative.JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
                    limits.BasicLimitInformation.LimitFlags = 0x00002000;
                    limits.write();

                    WindowsNative.STARTUPINFOEX startup = new WindowsNative.STARTUPINFOEX();
                    startup.StartupInfo.cb = startup.size();
                    startup.write();
                });
    }
}
