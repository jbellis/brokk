package io.github.jbellis.brokk.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class SwingUtil {
    private static final Logger logger = LogManager.getLogger(SwingUtil.class);

    /**
     * Executes a Callable on the EDT and handles exceptions properly.  Use this instead of Swingutilities.invokeAndWait.
     * @param task The task to execute
     * @param <T> The return type
     * @param defaultValue Value to return if execution fails
     * @return Result from task or defaultValue if execution fails
     */
    public static <T> T runOnEDT(Callable<T> task, T defaultValue) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                return task.call();
            }

            final CompletableFuture<T> future = new CompletableFuture<>();

            SwingUtilities.invokeAndWait(() -> {
                try {
                    future.complete(task.call());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Thread interrupted", e);
            return defaultValue;
        } catch (Exception e) {
            logger.warn("Execution error", e);
            return defaultValue;
        }
    }

    /** Executes a Runnable on the EDT and handles exceptions properly.  Use this instead of Swingutilities.invokeAndWait. */
    public static boolean runOnEDT(Runnable task) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                task.run();
            } else {
                SwingUtilities.invokeAndWait(task);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Thread interrupted", e);
            return false;
        } catch (InvocationTargetException e) {
            logger.warn("Execution error", e.getCause());
            return false;
        }
    }
}
