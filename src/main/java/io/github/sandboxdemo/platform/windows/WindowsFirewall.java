package io.github.sandboxdemo.platform.windows;

import io.github.sandboxdemo.api.SandboxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** Installs user-scoped inbound/outbound block rules for the offline account. */
final class WindowsFirewall {

    private static final String RULE_PREFIX = "CrossPlatformSandbox-Offline";

    private WindowsFirewall() {}

    static void install(String offlineSid) throws SandboxException, InterruptedException {
        String principal = "D:(A;;CC;;;" + offlineSid + ')';
        String script =
                """
                $ErrorActionPreference = 'Stop'
                $profiles = Get-NetFirewallProfile
                if ($profiles | Where-Object { -not $_.Enabled }) {
                    throw 'Windows Firewall must be enabled for every profile'
                }
                @('%s-In', '%s-Out') | ForEach-Object {
                    Remove-NetFirewallRule -Name $_ -ErrorAction SilentlyContinue
                }
                New-NetFirewallRule -PolicyStore PersistentStore -Name '%s-In' `
                    -DisplayName '%s inbound deny' -Direction Inbound -Action Block `
                    -Enabled True -Profile Any -LocalUser '%s' | Out-Null
                New-NetFirewallRule -PolicyStore PersistentStore -Name '%s-Out' `
                    -DisplayName '%s outbound deny' -Direction Outbound -Action Block `
                    -Enabled True -Profile Any -LocalUser '%s' | Out-Null
                $active = Get-NetFirewallRule -PolicyStore ActiveStore -Name '%s-In','%s-Out' `
                    -ErrorAction Stop
                if (($active | Measure-Object).Count -ne 2) {
                    throw 'offline firewall rules are not active'
                }
                """
                        .formatted(
                                RULE_PREFIX,
                                RULE_PREFIX,
                                RULE_PREFIX,
                                RULE_PREFIX,
                                principal,
                                RULE_PREFIX,
                                RULE_PREFIX,
                                principal,
                                RULE_PREFIX,
                                RULE_PREFIX);
        runPowerShell(script, "install offline Windows Firewall rules");
    }

    static void uninstall() throws SandboxException, InterruptedException {
        String script =
                """
                $ErrorActionPreference = 'Stop'
                @('%s-In', '%s-Out') | ForEach-Object {
                    Remove-NetFirewallRule -Name $_ -ErrorAction SilentlyContinue
                }
                """
                        .formatted(RULE_PREFIX, RULE_PREFIX);
        runPowerShell(script, "remove offline Windows Firewall rules");
    }

    static void verify(String offlineSid) throws SandboxException, InterruptedException {
        String script =
                """
                $ErrorActionPreference = 'Stop'
                if (Get-NetFirewallProfile | Where-Object { -not $_.Enabled }) {
                    throw 'Windows Firewall must be enabled for every profile'
                }
                $in = Get-NetFirewallRule -PolicyStore ActiveStore -Name '%s-In' -ErrorAction Stop
                $out = Get-NetFirewallRule -PolicyStore ActiveStore -Name '%s-Out' -ErrorAction Stop
                if (-not $in.Enabled -or $in.Action -ne 'Block' -or $in.Direction -ne 'Inbound') {
                    throw 'offline inbound firewall rule is inactive or altered'
                }
                if (-not $out.Enabled -or $out.Action -ne 'Block' -or $out.Direction -ne 'Outbound') {
                    throw 'offline outbound firewall rule is inactive or altered'
                }
                $filters = @($in, $out) | Get-NetFirewallSecurityFilter
                if (($filters | Where-Object { $_.LocalUser -match '%s' } | Measure-Object).Count -ne 2) {
                    throw 'offline firewall rules are not scoped to the expected account'
                }
                """
                        .formatted(RULE_PREFIX, RULE_PREFIX, offlineSid.replace("\\", "\\\\"));
        runPowerShell(script, "verify offline Windows Firewall rules");
    }

    private static void runPowerShell(String script, String operation)
            throws SandboxException, InterruptedException {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path powershell =
                Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!Files.isRegularFile(powershell)) {
            throw new SandboxException("Windows PowerShell was not found: " + powershell);
        }
        String encoded =
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        List<String> command =
                List.of(
                        powershell.toString(),
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-EncodedCommand",
                        encoded);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new SandboxException(
                        "failed to "
                                + operation
                                + " (exit="
                                + exitCode
                                + "): "
                                + new String(output, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException e) {
            throw new SandboxException("failed to launch Windows PowerShell to " + operation, e);
        }
    }
}
