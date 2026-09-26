package com.pvzce.client.input.wayland;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Single-finger touch, read from the compositor through GLFW's own Wayland connection.
 *
 * <p><b>Why this is hand-written native code.</b> GLFW has no touch API at all - its Wayland
 * backend has no {@code wl_touch} listener, so a finger on a Wayland session produces no mouse
 * events, unlike Windows (the OS promotes the primary touch pointer to mouse messages) and X11
 * (XInput2 emulates pointer events for the first touch point). Without this, the game is simply
 * unclickable with a finger on a modern Wayland desktop. It is deliberately <em>not</em> a fork of
 * GLFW: we reuse GLFW's connection ({@code glfwGetWaylandDisplay}) and let its normal event loop
 * dispatch our objects, which costs a few hundred lines of FFM instead of a patched native library
 * per platform.
 *
 * <p><b>How it fits together.</b> GLFW's Wayland poll uses the <em>default</em> event queue
 * ({@code wl_display_prepare_read/read_events/dispatch_pending}), so anything we create on the same
 * connection has its callbacks invoked from inside {@code glfwPollEvents()} - no extra thread and
 * no second event loop. The flow is the usual Wayland dance: get a registry, wait for the globals,
 * bind the seat (only after its {@code capabilities} event says it has a touch device - calling
 * {@code get_touch} on a seat without one is a protocol error that kills the connection), then
 * {@code get_touch} and listen.
 *
 * <p>Three mistakes are easy to make here and were made once already while probing this:
 * <ul>
 *   <li>the argument array of {@code wl_proxy_marshal_array_flags} may not be NULL - every request
 *       has a {@code new_id} argument and libwayland dereferences it (a NULL there is a SIGSEGV);</li>
 *   <li>a listener array must outlive the connection, not the call: libwayland only stores the
 *       pointer, and an array freed with a short-lived arena makes the callbacks silently never
 *       fire;</li>
 *   <li>two round trips are needed at install time, because the {@code bind} we send while handling
 *       a global is still sitting in the client's buffer when the first round trip returns.</li>
 * </ul>
 */
public final class WaylandTouch implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Touch");
    /** {@code WL_SEAT_CAPABILITY_TOUCH}. */
    private static final int CAPABILITY_TOUCH = 4;
    /** Bind the seat at 5: newer versions only add {@code wl_touch} shape/orientation events. */
    private static final int SEAT_VERSION = 5;
    private static final int WL_DISPLAY_GET_REGISTRY = 1;
    private static final int WL_REGISTRY_BIND = 0;
    private static final int WL_SEAT_GET_TOUCH = 2;

    /** What the translator upstream wants to hear. */
    public interface Sink {
        void down(int id, double x, double y);

        void motion(int id, double x, double y);

        void up(int id);

        void cancel();
    }

    /**
     * The instance the native callbacks report to.
     *
     * <p>One window and one touch source per process, which is what the game has; passing a Java
     * object through Wayland's {@code void *data} would mean handing out raw pointers to the GC's
     * furniture for no gain.
     */
    private static WaylandTouch current;

    private final Sink sink;
    private final long surface;
    private final long registry;
    private final MemorySegment touch;
    private final MemorySegment seatProxy;
    private boolean closed;

    private final MethodHandle proxyMarshalArrayFlags;
    private final MethodHandle proxyAddListener;
    private final MethodHandle proxyGetVersion;
    private final MethodHandle proxyDestroy;
    private final MemorySegment registryInterface;
    private final MemorySegment seatInterface;
    private final MemorySegment touchInterface;

    /**
     * Starts listening for touch on {@code surface}, over the connection {@code display}.
     *
     * @return the listener, or {@code null} when this cannot work here (no libwayland, no seat, no
     *         touch device, any protocol surprise) - a missing touch device must never stop the game
     *         from starting, so every failure is a log line and a null
     */
    public static WaylandTouch install(long display, long surface, Sink sink) {
        if (display == 0 || surface == 0) {
            LOGGER.info("触控：拿不到 Wayland 的 display/surface，跳过");
            return null;
        }
        if (current != null && !current.closed) {
            LOGGER.warn("触控：已经装过一个监听器，先关掉它");
            current.close();
        }
        try {
            return new WaylandTouch(display, surface, sink);
        } catch (Throwable failure) {
            // The half-built instance may already have been published to the callbacks; drop it so
            // a stray event cannot reach a listener whose fields were never finished.
            current = null;
            LOGGER.warn("触控：Wayland 触摸不可用（{}）", failure.toString());
            return null;
        }
    }

    private WaylandTouch(long display, long surface, Sink sink) throws Throwable {
        this.sink = sink;
        this.surface = surface;
        Arena arena = Arena.global();
        Linker linker = Linker.nativeLinker();
        SymbolLookup library = SymbolLookup.libraryLookup("libwayland-client.so.0", arena);
        this.proxyMarshalArrayFlags = downcall(linker, library, "wl_proxy_marshal_array_flags",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));
        this.proxyAddListener = downcall(linker, library, "wl_proxy_add_listener",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
        this.proxyGetVersion = downcall(linker, library, "wl_proxy_get_version",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.proxyDestroy = downcall(linker, library, "wl_proxy_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        MethodHandle displayRoundtrip = downcall(linker, library, "wl_display_roundtrip",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.registryInterface = library.find("wl_registry_interface").orElseThrow();
        this.seatInterface = library.find("wl_seat_interface").orElseThrow();
        this.touchInterface = library.find("wl_touch_interface").orElseThrow();

        MemorySegment displaySegment = MemorySegment.ofAddress(display);
        current = this;
        this.registry = marshalNewId(displaySegment, WL_DISPLAY_GET_REGISTRY, registryInterface, arena, 1);
        if (registry == 0) {
            throw new IllegalStateException("wl_display.get_registry 返回空");
        }
        MemorySegment registryListener = allocateListener(arena, 2, new String[]{"onGlobal", "onGlobalRemove"},
                new FunctionDescriptor[]{
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT)},
                new Class<?>[][]{
                        {MemorySegment.class, MemorySegment.class, int.class, MemorySegment.class, int.class},
                        {MemorySegment.class, MemorySegment.class, int.class}});
        proxyAddListener.invokeWithArguments(MemorySegment.ofAddress(registry), registryListener,
                MemorySegment.NULL);
        // First round trip: the globals arrive and we bind the seat from inside the callback.
        displayRoundtrip.invokeWithArguments(displaySegment);
        // Second: our bind request is only flushed now, and its capabilities event comes back with it.
        displayRoundtrip.invokeWithArguments(displaySegment);

        this.seatProxy = seat == 0 ? null : MemorySegment.ofAddress(seat);
        this.touch = touchHandle == 0 ? null : MemorySegment.ofAddress(touchHandle);
        if (touch == null) {
            throw new IllegalStateException("这个 seat 没有触摸设备");
        }
        LOGGER.info("触控：已接管 Wayland 触摸（wl_touch，单指）");
    }

    private long seat;
    private long touchHandle;

    private static MethodHandle downcall(Linker linker, SymbolLookup library, String name,
                                        FunctionDescriptor descriptor) {
        return linker.downcallHandle(library.find(name)
                .orElseThrow(() -> new IllegalStateException("libwayland-client 里没有 " + name)), descriptor);
    }

    /** Builds a listener struct whose function-pointer array outlives the connection. */
    private MemorySegment allocateListener(Arena arena, int slots, String[] names,
                                           FunctionDescriptor[] descriptors, Class<?>[][] signatures)
            throws Throwable {
        MemorySegment listener = arena.allocate(ValueLayout.ADDRESS, slots);
        for (int i = 0; i < slots; i++) {
            MemorySegment stub = Linker.nativeLinker().upcallStub(
                    MethodHandles.lookup().findStatic(WaylandTouch.class, names[i],
                            MethodType.methodType(void.class, signatures[i])),
                    descriptors[i], arena);
            listener.set(ValueLayout.ADDRESS, i * 8L, stub);
        }
        return listener;
    }

    /** A request that creates a new object, through the non-variadic marshal entry point. */
    private long marshalNewId(MemorySegment proxy, int opcode, MemorySegment iface, Arena arena,
                              int arguments) throws Throwable {
        MemorySegment args = arena.allocate(ValueLayout.ADDRESS, Math.max(1, arguments));
        MemorySegment created = (MemorySegment) proxyMarshalArrayFlags.invokeWithArguments(
                proxy, opcode, iface, (int) proxyGetVersion.invokeWithArguments(proxy), 0, args);
        return created == null ? 0 : created.address();
    }

    // ---------- Wayland callbacks (invoked from inside glfwPollEvents) ----------

    private static void onGlobal(MemorySegment data, MemorySegment registry, int name,
                                 MemorySegment interfaceName, int version) {
        WaylandTouch self = current;
        if (self == null || self.closed) {
            return;
        }
        String iface = interfaceName.reinterpret(Long.MAX_VALUE).getString(0);
        if (!"wl_seat".equals(iface)) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment args = arena.allocate(ValueLayout.ADDRESS, 4);
            args.set(ValueLayout.JAVA_INT, 0, name);
            args.set(ValueLayout.ADDRESS, 8, arena.allocateFrom("wl_seat"));
            args.set(ValueLayout.JAVA_INT, 16, Math.min(version, SEAT_VERSION));
            args.set(ValueLayout.JAVA_INT, 24, 0);
            MemorySegment created = (MemorySegment) self.proxyMarshalArrayFlags.invokeWithArguments(
                    registry, WL_REGISTRY_BIND, self.seatInterface,
                    (int) self.proxyGetVersion.invokeWithArguments(registry), 0, args);
            if (created == null || created.address() == 0) {
                LOGGER.warn("触控：bind(wl_seat) 失败");
                return;
            }
            self.seat = created.address();
            MemorySegment listener = self.allocateListener(Arena.global(), 2,
                    new String[]{"onCapabilities", "onSeatName"},
                    new FunctionDescriptor[]{
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS)},
                    new Class<?>[][]{
                            {MemorySegment.class, MemorySegment.class, int.class},
                            {MemorySegment.class, MemorySegment.class, MemorySegment.class}});
            self.proxyAddListener.invokeWithArguments(created, listener, MemorySegment.NULL);
        } catch (Throwable failure) {
            LOGGER.warn("触控：绑 seat 时出错（{}）", failure.toString());
        }
    }

    private static void onGlobalRemove(MemorySegment data, MemorySegment registry, int name) {
        // Nothing to do: the game has one seat and never rebinds it.
    }

    private static void onCapabilities(MemorySegment data, MemorySegment seat, int capabilities) {
        WaylandTouch self = current;
        if (self == null || self.closed || self.touchHandle != 0 || (capabilities & CAPABILITY_TOUCH) == 0) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment args = arena.allocate(ValueLayout.ADDRESS, 1);
            MemorySegment created = (MemorySegment) self.proxyMarshalArrayFlags.invokeWithArguments(
                    MemorySegment.ofAddress(self.seat), WL_SEAT_GET_TOUCH, self.touchInterface,
                    (int) self.proxyGetVersion.invokeWithArguments(MemorySegment.ofAddress(self.seat)),
                    0, args);
            if (created == null || created.address() == 0) {
                LOGGER.warn("触控：seat 报了触摸能力但 get_touch 返回空");
                return;
            }
            self.touchHandle = created.address();
            MemorySegment listener = self.allocateListener(Arena.global(), 5,
                    new String[]{"onDown", "onUp", "onMotion", "onFrame", "onCancel"},
                    new FunctionDescriptor[]{
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)},
                    new Class<?>[][]{
                            {MemorySegment.class, MemorySegment.class, int.class, int.class,
                                    MemorySegment.class, int.class, int.class, int.class},
                            {MemorySegment.class, MemorySegment.class, int.class, int.class, int.class},
                            {MemorySegment.class, MemorySegment.class, int.class, int.class, int.class,
                                    int.class},
                            {MemorySegment.class, MemorySegment.class},
                            {MemorySegment.class, MemorySegment.class}});
            self.proxyAddListener.invokeWithArguments(created, listener, MemorySegment.NULL);
        } catch (Throwable failure) {
            LOGGER.warn("触控：get_touch 出错（{}）", failure.toString());
        }
    }

    private static void onSeatName(MemorySegment data, MemorySegment seat, MemorySegment name) {
        // Not needed: the game has one seat and the capability bit is the only thing it reads.
    }

    private static void onDown(MemorySegment data, MemorySegment touch, int serial, int time,
                               MemorySegment surface, int id, int x, int y) {
        WaylandTouch self = current;
        if (self == null || self.closed || surface == null || surface.address() != self.surface) {
            // A touch on GLFW's own fallback decoration frame is not a touch on the game.
            return;
        }
        self.sink.down(id, fixed(x), fixed(y));
    }

    private static void onMotion(MemorySegment data, MemorySegment touch, int time, int id, int x, int y) {
        WaylandTouch self = current;
        if (self != null && !self.closed) {
            self.sink.motion(id, fixed(x), fixed(y));
        }
    }

    private static void onUp(MemorySegment data, MemorySegment touch, int serial, int time, int id) {
        WaylandTouch self = current;
        if (self != null && !self.closed) {
            self.sink.up(id);
        }
    }

    private static void onFrame(MemorySegment data, MemorySegment touch) {
        // Events are forwarded as they arrive; a frame boundary carries nothing the gesture needs.
    }

    private static void onCancel(MemorySegment data, MemorySegment touch) {
        WaylandTouch self = current;
        if (self != null && !self.closed) {
            self.sink.cancel();
        }
    }

    /** {@code wl_fixed_t} is 24.8 fixed point. */
    private static double fixed(int value) {
        return value / 256.0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        current = null;
        try {
            if (touch != null) {
                proxyDestroy.invokeWithArguments(touch);
            }
            if (seatProxy != null) {
                proxyDestroy.invokeWithArguments(seatProxy);
            }
            if (registry != 0) {
                proxyDestroy.invokeWithArguments(MemorySegment.ofAddress(registry));
            }
        } catch (Throwable failure) {
            LOGGER.warn("触控：关闭监听器时出错（{}）", failure.toString());
        }
        // The upcall stubs and the listener arrays live in Arena.global() on purpose: libwayland
        // may still hold them until the proxies above are destroyed, and freeing them earlier is
        // how a working listener turns into one that silently never fires.
    }
}
