package dev.helios.core.vk;

import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * LWJGL stack management. Minecraft's render thread uses LWJGL's default 64 KiB {@link MemoryStack},
 * which is too small for Vulkan setup:
 * <ul>
 *   <li>Helios' own allocations use a private 1 MiB per-thread stack ({@link #stackPush()}).</li>
 *   <li>LWJGL itself allocates from the default stack too; notably the {@code VkInstance}
 *       constructor lists every GPU's device extensions there (~65 KiB per GPU). Such work runs on a
 *       helper thread whose default stack is enlarged ({@link #callWithLargeStack}).</li>
 * </ul>
 */
public final class HeliosStack {
    private static final Logger LOG = Logger.getLogger("Helios");
    private static final int SIZE = 1 << 20;
    private static final int LARGE_DEFAULT_SIZE = 4 << 20;
    private static final ThreadLocal<MemoryStack> STACK = ThreadLocal.withInitial(() -> MemoryStack.create(SIZE));

    private HeliosStack() {
    }

    /** Drop-in replacement for {@link MemoryStack#stackPush()}. */
    public static MemoryStack stackPush() {
        return STACK.get().push();
    }

    /**
     * Runs {@code task} on a new thread whose LWJGL default stack ({@link MemoryStack#stackGet()})
     * is {@value #LARGE_DEFAULT_SIZE} bytes, and waits for it. Exceptions are rethrown unchanged.
     */
    public static <T> T callWithLargeStack(String threadName, Callable<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            installLargeDefaultStack();
            try {
                result.set(task.call());
            } catch (Throwable t) {
                error.set(t);
            }
        }, threadName);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + threadName, e);
        }
        Throwable t = error.get();
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error err) throw err;
        if (t != null) throw new IllegalStateException(t);
        return result.get();
    }

    @SuppressWarnings("unchecked")
    static void installLargeDefaultStack() {
        try {
            Field tls = MemoryStack.class.getDeclaredField("TLS");
            tls.setAccessible(true);
            ((ThreadLocal<MemoryStack>) tls.get(null)).set(MemoryStack.create(LARGE_DEFAULT_SIZE));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warning("Could not enlarge LWJGL's stack (" + e + "); Vulkan setup may run out of stack space");
        }
    }
}
