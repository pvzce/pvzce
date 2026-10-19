package com.pvzce.launcher;

import com.pvzce.client.account.GameAccount;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Separate JVM so JDK HTTP diagnostic flags have not already been initialized by another test. */
public final class GameLoginDiagnosticsProbe {
    public static void main(String[] args) throws Exception {
        var login = GameLoginArguments.consume(args);
        char[] code = login.takeCode();
        try (var account = new GameAccount()) {
            account.start(login.api(), code, login.invalid());
            login.clear();
            var field = GameAccount.class.getDeclaredField("worker");
            field.setAccessible(true);
            ((ExecutorService) field.get(account)).submit(() -> {}).get(12, TimeUnit.SECONDS);
            System.out.println(account.view());
            System.out.println(java.util.Arrays.toString(args));
            System.out.println(System.getProperty("sun.java.command"));
            // Exercise the same public data a default crash diagnostic would print.
            new RuntimeException("Launch diagnostics: " + java.util.Arrays.toString(args)
                    + " / " + System.getProperty("sun.java.command")).printStackTrace(System.err);
            account.logout();
            ((ExecutorService) field.get(account)).submit(() -> {}).get(12, TimeUnit.SECONDS);
        }
    }
}
