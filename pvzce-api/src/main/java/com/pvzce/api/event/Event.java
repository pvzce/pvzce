package com.pvzce.api.event;

import java.util.function.Function;

/**
 * Array-backed event, designed like Fabric API's {@code Event<T>}: handlers
 * may be registered at any time and the invoker is rebuilt on registration.
 */
public final class Event<T> {
    private final Class<T> type;
    private final Function<T[], T> invokerFactory;
    private final T emptyInvoker;
    private volatile T[] handlers;
    private volatile T invoker;

    @SuppressWarnings("unchecked")
    public Event(Class<T> type, T emptyInvoker, Function<T[], T> invokerFactory) {
        this.type = type;
        this.emptyInvoker = emptyInvoker;
        this.invokerFactory = invokerFactory;
        this.handlers = (T[]) java.lang.reflect.Array.newInstance(type, 0);
        update();
    }

    public void register(T listener) {
        synchronized (this) {
            T[] old = handlers;
            @SuppressWarnings("unchecked")
            T[] next = (T[]) java.lang.reflect.Array.newInstance(type, old.length + 1);
            System.arraycopy(old, 0, next, 0, old.length);
            next[old.length] = listener;
            handlers = next;
            update();
        }
    }

    public T invoker() {
        return invoker;
    }

    private void update() {
        invoker = invokerFactory.apply(handlers.clone());
    }
}
