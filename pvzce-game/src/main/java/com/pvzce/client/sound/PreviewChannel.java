package com.pvzce.client.sound;

import java.util.Objects;
import java.util.function.Consumer;

/** One exclusive audition channel, with a separate lifetime for each screen using it. */
public final class PreviewChannel {
    private final Consumer<String> play;
    private final Runnable stop;
    private Session owner;

    PreviewChannel(Consumer<String> play, Runnable stop) {
        this.play = Objects.requireNonNull(play);
        this.stop = Objects.requireNonNull(stop);
    }

    public Session open() {
        return new Session();
    }

    /** Resource reload and engine shutdown cancel whichever screen currently owns the source. */
    void stopAll() {
        if (owner != null) {
            owner = null;
            stop.run();
        }
    }

    public final class Session implements AutoCloseable {
        private boolean closed;

        private Session() {
        }

        public void play(String event) {
            if (closed) return;
            stopAll();
            if (event == null || event.isBlank()) return;
            owner = this;
            try {
                play.accept(event);
            } catch (RuntimeException | Error failure) {
                stop();
                throw failure;
            }
        }

        /** Hiding a screen stops its audition; returning to it still permits a new audition. */
        public void stop() {
            if (owner == this) stopAll();
        }

        @Override
        public void close() {
            stop();
            closed = true;
        }
    }
}
