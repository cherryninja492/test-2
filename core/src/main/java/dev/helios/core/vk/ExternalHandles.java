package dev.helios.core.vk;

import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;

/** Lifetime helpers for exported memory handles. */
public final class ExternalHandles {
    private static long closeHandle;

    private ExternalHandles() {
    }

    /**
     * Closes a Win32 NT handle after OpenGL imported it (GL does not take ownership of Win32
     * handles, unlike POSIX fds). No-op on other platforms.
     */
    public static synchronized void closeWin32Handle(long handle) {
        if (Platform.get() != Platform.WINDOWS || handle == 0L) return;
        if (closeHandle == 0L) {
            SharedLibrary kernel32 = Library.loadNative(ExternalHandles.class, "org.lwjgl", "kernel32");
            closeHandle = kernel32.getFunctionAddress("CloseHandle");
        }
        JNI.callPI(handle, closeHandle);
    }
}
