package org.manager.di;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DependencyContainer registration and resolution")
class DependencyContainerTest {

    interface Service {
        String name();
    }

    static class Impl implements Service {
        @Override
        public String name() {
            return "impl";
        }
    }

    @Test
    @DisplayName("registered singleton resolves to the same instance")
    void singletonResolution() {
        DependencyContainer container = new DependencyContainer();
        Service service = new Impl();
        container.registerSingleton(Service.class, service);

        assertSame(service, container.get(Service.class));
        assertSame(service, container.getRequired(Service.class));
        assertTrue(container.isRegistered(Service.class));
        assertEquals(1, container.getSingletonCount());
    }

    @Test
    @DisplayName("factory produces a new instance per request")
    void factoryProducesNewInstances() {
        DependencyContainer container = new DependencyContainer();
        AtomicInteger creations = new AtomicInteger();
        container.registerFactory(Service.class, () -> {
            creations.incrementAndGet();
            return new Impl();
        });

        Service first = container.get(Service.class);
        Service second = container.get(Service.class);
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first != second, "a plain factory must create a new instance per request");
        assertEquals(2, creations.get());
        assertEquals(0, container.getSingletonCount());
        assertEquals(1, container.getFactoryCount());
    }

    @Test
    @DisplayName("singleton factory creates exactly one instance even under contention")
    void singletonFactoryIsThreadSafe() throws Exception {
        DependencyContainer container = new DependencyContainer();
        AtomicInteger creations = new AtomicInteger();
        container.registerSingletonFactory(Service.class, () -> {
            creations.incrementAndGet();
            // widen the race window so a broken double-checked lock fails loudly
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Impl();
        });

        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Service>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < readers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return container.get(Service.class);
            }));
        }
        start.countDown();
        Service expected = futures.get(0).get(10, TimeUnit.SECONDS);
        for (java.util.concurrent.Future<Service> future : futures) {
            assertSame(expected, future.get(10, TimeUnit.SECONDS),
                    "every concurrent reader must observe the same singleton");
        }
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, creations.get(), "the factory must run exactly once");
        assertEquals(1, container.getSingletonCount());
    }

    @Test
    @DisplayName("unregistered and null lookups resolve to null; getRequired throws DependencyException")
    void missingRegistrations() {
        DependencyContainer container = new DependencyContainer();
        assertNull(container.get(Service.class));
        assertFalse(container.isRegistered(Service.class));
        assertThrows(DependencyContainer.DependencyException.class, () -> container.getRequired(Service.class));
        assertThrows(IllegalArgumentException.class, () -> container.get(null));

        DependencyContainer.DependencyException wrapped = assertThrows(
                DependencyContainer.DependencyException.class,
                () -> container.getRequired(String.class));
        assertTrue(wrapped.getMessage().contains("String"));
    }

    @Test
    @DisplayName("a failing factory is wrapped in DependencyException with the cause preserved")
    void failingFactoryIsWrapped() {
        DependencyContainer container = new DependencyContainer();
        RuntimeException failure = new RuntimeException("cannot construct");
        container.registerFactory(Service.class, () -> {
            throw failure;
        });

        DependencyContainer.DependencyException ex = assertThrows(
                DependencyContainer.DependencyException.class, () -> container.get(Service.class));
        assertSame(failure, ex.getCause());
    }

    @Test
    @DisplayName("unregister removes both singleton and factory registrations")
    void unregisterRemovesRegistrations() {
        DependencyContainer container = new DependencyContainer();
        container.registerSingleton(Service.class, new Impl());
        container.registerFactory(String.class, () -> "x");
        container.unregister(Service.class);
        container.unregister(String.class);

        assertFalse(container.isRegistered(Service.class));
        assertFalse(container.isRegistered(String.class));
        assertEquals(0, container.getSingletonCount());
        assertEquals(0, container.getFactoryCount());
    }

    @Test
    @DisplayName("after shutdown every operation is rejected")
    void shutdownRejectsAllOperations() {
        DependencyContainer container = new DependencyContainer();
        container.registerSingleton(Service.class, new Impl());
        container.shutdown();
        assertTrue(container.isShutdown());

        assertThrows(IllegalStateException.class, () -> container.registerSingleton(Service.class, new Impl()));
        assertThrows(IllegalStateException.class, () -> container.registerFactory(Service.class, Impl::new));
        assertThrows(IllegalStateException.class, () -> container.registerSingletonFactory(Service.class, Impl::new));
        assertThrows(IllegalStateException.class, () -> container.get(Service.class));
        assertThrows(IllegalStateException.class, () -> container.unregister(Service.class));
    }

    @Test
    @DisplayName("shutdown is idempotent")
    void shutdownIsIdempotent() {
        DependencyContainer container = new DependencyContainer();
        container.shutdown();
        container.shutdown();
        assertTrue(container.isShutdown());
    }

    @Test
    @DisplayName("null type or instance arguments are rejected")
    void nullArgumentsRejected() {
        DependencyContainer container = new DependencyContainer();
        assertThrows(IllegalArgumentException.class, () -> container.registerSingleton(null, new Impl()));
        assertThrows(IllegalArgumentException.class, () -> container.registerSingleton(Service.class, null));
        assertThrows(IllegalArgumentException.class, () -> container.registerFactory(null, Impl::new));
        assertThrows(IllegalArgumentException.class, () -> container.registerFactory(Service.class, null));
        assertThrows(IllegalArgumentException.class, () -> container.unregister(null));
        assertThrows(IllegalArgumentException.class, () -> container.isRegistered(null));
    }
}
