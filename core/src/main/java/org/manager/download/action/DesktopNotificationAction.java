package org.manager.download.action;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.manager.download.Download;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends a completion alert through the desktop integration supplied by the UI. */
public final class DesktopNotificationAction implements AfterCompletionAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(DesktopNotificationAction.class);
    private final Function<Download, CompletableFuture<Void>> sender;
    private final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();

    public DesktopNotificationAction(Function<Download, CompletableFuture<Void>> sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    @Override
    public boolean execute(Download download) {
        CompletableFuture<Void> delivery = null;
        try {
            delivery = sender.apply(download);
            pending.add(delivery);
            delivery.get(10, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failure) {
            LOGGER.warn("Could not send download completion notification", failure);
            return false;
        } finally {
            if (delivery != null) {
                pending.remove(delivery);
                delivery.cancel(true);
            }
        }
    }

    @Override
    public ActionType getType() {
        return ActionType.DESKTOP_NOTIFICATION;
    }

    @Override
    public String getDescription() {
        return "Show desktop notification";
    }

    @Override
    public String getResultMessage() {
        return "Desktop notification sent";
    }

    @Override
    public String getFailureMessage() {
        return "Could not send desktop notification";
    }

    @Override
    public Severity getSeverity() {
        return Severity.LOW;
    }

    @Override
    public boolean cancel() {
        pending.forEach(delivery -> delivery.cancel(true));
        return true;
    }
}
