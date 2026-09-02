package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class SpinnerActivityTest {

    @Test
    void overlappingWorkKeepsTheIndicatorActiveUntilTheLastTaskEnds() {
        List<Boolean> states = new ArrayList<>();
        SpinnerActivity activity = new SpinnerActivity(states::add);

        Runnable first = activity.begin();
        Runnable second = activity.begin();
        assertEquals(List.of(true), states);
        assertEquals(2, activity.activeCount());

        first.run();
        first.run();
        assertEquals(List.of(true), states, "an idempotent completion cannot stop other work");
        assertEquals(1, activity.activeCount());

        second.run();
        assertEquals(List.of(true, false), states);
        assertEquals(0, activity.activeCount());
    }

    @Test
    void trackedFutureEndsTheActivityOnSuccessOrFailure() {
        List<Boolean> states = new ArrayList<>();
        SpinnerActivity activity = new SpinnerActivity(states::add);
        CompletableFuture<String> success = new CompletableFuture<>();
        CompletableFuture<String> failure = new CompletableFuture<>();

        activity.track(success);
        activity.track(failure);
        success.complete("ok");
        failure.completeExceptionally(new IllegalStateException("failed"));

        assertEquals(List.of(true, false), states);
        assertEquals(0, activity.activeCount());
    }

    @Test
    void disposalStopsAndIgnoresNewWork() {
        List<Boolean> states = new ArrayList<>();
        SpinnerActivity activity = new SpinnerActivity(states::add);
        Runnable pending = activity.begin();

        activity.dispose();
        activity.begin().run();
        pending.run();

        assertEquals(List.of(true, false), states);
        assertEquals(0, activity.activeCount());
    }
}
