using System;
using System.Diagnostics;
using System.Reflection;
using System.Runtime.InteropServices;

// Bounded CI fixture: one 128 MiB commit or one child, never an unbounded stress test.
class ResourceProbe {
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern IntPtr VirtualAlloc(IntPtr address, UIntPtr size, uint allocation, uint protection);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool VirtualFree(IntPtr address, UIntPtr size, uint freeType);

    static int Main(string[] args) {
        if (args.Length != 1) return 2;
        if (args[0] == "child") return 42;
        if (args[0] == "memory") {
            IntPtr allocation = VirtualAlloc(IntPtr.Zero, new UIntPtr(128U * 1024U * 1024U), 0x3000, 0x04);
            if (allocation == IntPtr.Zero) {
                // ERROR_COMMITMENT_LIMIT (1455) or ERROR_NOT_ENOUGH_MEMORY (8).
                int error = Marshal.GetLastWin32Error();
                if (error != 1455 && error != 8) { Console.Error.Write("VirtualAlloc-denial-code=" + error); return 3; }
                Console.Write("allocation-denied");
            } else {
                try { Console.Write("allocation-allowed"); }
                finally { if (!VirtualFree(allocation, UIntPtr.Zero, 0x8000)) throw new InvalidOperationException("VirtualFree failed"); }
            }
            return 0;
        }
        if (args[0] == "process") {
            try {
                ProcessStartInfo start = new ProcessStartInfo(Assembly.GetExecutingAssembly().Location, "child");
                start.UseShellExecute = false;
                start.CreateNoWindow = true;
                using (Process child = Process.Start(start)) {
                    if (!child.WaitForExit(5000)) { child.Kill(); return 4; }
                    Console.Write(child.ExitCode == 42 ? "child-allowed" : "child-denied");
                }
            } catch (System.ComponentModel.Win32Exception) { Console.Write("child-denied"); }
            return 0;
        }
        return 2;
    }
}
