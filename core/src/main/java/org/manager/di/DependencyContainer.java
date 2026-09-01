package org.manager.di;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple dependency injection container for managing component dependencies.
 * This container helps break circular dependencies and provides centralized
 * component management with proper lifecycle handling.
 */
public class DependencyContainer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyContainer.class);

    private final Map<Class<?>, Object> singletons = new ConcurrentHashMap<>();
    private final Map<Class<?>, Supplier<?>> factories = new ConcurrentHashMap<>();
    private final Map<Class<?>, ReentrantLock> creationLocks = new ConcurrentHashMap<>();
    private volatile boolean isShutdown = false;

    /**
     * Registers a singleton instance for the given type.
     *
     * @param type     The type to register
     * @param instance The singleton instance
     * @param <T>      The type parameter
     * @throws IllegalStateException    if the container has been shut down
     * @throws IllegalArgumentException if type or instance is null
     */
    public <T> void registerSingleton(Class<T> type, T instance) {
        checkNotShutdown();
        if (type == null || instance == null) {
            throw new IllegalArgumentException("Type and instance cannot be null");
        }

        if (singletons.containsKey(type)) {
            LOGGER.warn("Overriding existing singleton registration for type: " + type.getName());
        }

        singletons.put(type, instance);
        LOGGER.debug("Registered singleton for type: " + type.getName());
    }

    /**
     * Registers a factory for creating instances of the given type. The factory
     * will be called each time an instance is requested (not singleton).
     *
     * @param type    The type to register
     * @param factory The factory supplier
     * @param <T>     The type parameter
     * @throws IllegalStateException    if the container has been shut down
     * @throws IllegalArgumentException if type or factory is null
     */
    public <T> void registerFactory(Class<T> type, Supplier<T> factory) {
        checkNotShutdown();
        if (type == null || factory == null) {
            throw new IllegalArgumentException("Type and factory cannot be null");
        }

        if (factories.containsKey(type)) {
            LOGGER.warn("Overriding existing factory registration for type: " + type.getName());
        }

        factories.put(type, factory);
        LOGGER.debug("Registered factory for type: " + type.getName());
    }

    /**
     * Registers a singleton factory for the given type. The factory will be
     * called only once to create the singleton instance.
     *
     * @param type    The type to register
     * @param factory The factory supplier
     * @param <T>     The type parameter
     * @throws IllegalStateException    if the container has been shut down
     * @throws IllegalArgumentException if type or factory is null
     */
    public <T> void registerSingletonFactory(Class<T> type, Supplier<T> factory) {
        checkNotShutdown();
        if (type == null || factory == null) {
            throw new IllegalArgumentException("Type and factory cannot be null");
        }

        // Wrap the factory to ensure singleton behavior
        Supplier<T> singletonFactory = () -> {
            @SuppressWarnings("unchecked")
            T existing = (T) singletons.get(type);
            if (existing != null) {
                return existing;
            }

            // Use double-checked locking for thread safety
            ReentrantLock lock = creationLocks.computeIfAbsent(type, k -> new ReentrantLock());
            lock.lock();
            try {
                @SuppressWarnings("unchecked")
                T doubleChecked = (T) singletons.get(type);
                if (doubleChecked != null) {
                    return doubleChecked;
                }

                T instance = factory.get();
                if (instance != null) {
                    singletons.put(type, instance);
                    LOGGER.debug("Created singleton instance for type: " + type.getName());
                }
                return instance;
            } finally {
                lock.unlock();
            }
        };

        factories.put(type, singletonFactory);
        LOGGER.debug("Registered singleton factory for type: " + type.getName());
    }

    /**
     * Gets an instance of the specified type.
     *
     * @param type The type to get
     * @param <T>  The type parameter
     * @return The instance, or null if not registered
     * @throws IllegalStateException    if the container has been shut down
     * @throws IllegalArgumentException if type is null
     * @throws DependencyException      if there's an error creating the instance
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        checkNotShutdown();
        if (type == null) {
            throw new IllegalArgumentException("Type cannot be null");
        }

        // Check for existing singleton first
        T singleton = (T) singletons.get(type);
        if (singleton != null) {
            return singleton;
        }

        // Try to create using factory
        Supplier<?> factory = factories.get(type);
        if (factory != null) {
            try {
                return (T) factory.get();
            } catch (Exception e) {
                throw new DependencyException("Failed to create instance of type: " + type.getName(), e);
            }
        }

        // Not found
        return null;
    }

    /**
     * Gets an instance of the specified type, throwing an exception if not
     * found.
     *
     * @param type The type to get
     * @param <T>  The type parameter
     * @return The instance
     * @throws IllegalStateException    if the container has been shut down
     * @throws IllegalArgumentException if type is null
     * @throws DependencyException      if the type is not registered or creation
     *                                  fails
     */
    public <T> T getRequired(Class<T> type) {
        T instance = get(type);
        if (instance == null) {
            throw new DependencyException("No registration found for required type: " + type.getName());
        }
        return instance;
    }

    /**
     * Checks if a type is registered in the container.
     *
     * @param type The type to check
     * @return true if registered, false otherwise
     * @throws IllegalArgumentException if type is null
     */
    public boolean isRegistered(Class<?> type) {
        if (type == null) {
            throw new IllegalArgumentException("Type cannot be null");
        }
        return singletons.containsKey(type) || factories.containsKey(type);
    }

    /**
     * Unregisters a type from the container.
     *
     * @param type The type to unregister
     * @throws IllegalStateException    if the container has been shut down
     * @throws IllegalArgumentException if type is null
     */
    public void unregister(Class<?> type) {
        checkNotShutdown();
        if (type == null) {
            throw new IllegalArgumentException("Type cannot be null");
        }

        Object removed = singletons.remove(type);
        Supplier<?> factory = factories.remove(type);
        creationLocks.remove(type);

        if (removed != null || factory != null) {
            LOGGER.debug("Unregistered type: " + type.getName());
        }
    }

    /**
     * Clears all registrations and shuts down the container. After calling this
     * method, the container cannot be used anymore.
     */
    public void shutdown() {
        if (isShutdown) {
            return;
        }

        LOGGER.info("Shutting down DependencyContainer...");
        isShutdown = true;

        // Clear all registrations
        singletons.clear();
        factories.clear();
        creationLocks.clear();

        LOGGER.info("DependencyContainer shutdown completed");
    }

    /**
     * Checks if the container has been shut down.
     *
     * @return true if shut down, false otherwise
     */
    public boolean isShutdown() {
        return isShutdown;
    }

    /**
     * Gets the number of registered singletons.
     *
     * @return The number of singletons
     */
    public int getSingletonCount() {
        return singletons.size();
    }

    /**
     * Gets the number of registered factories.
     *
     * @return The number of factories
     */
    public int getFactoryCount() {
        return factories.size();
    }

    /**
     * Checks if the container is not shut down, throwing an exception if it is.
     *
     * @throws IllegalStateException if the container has been shut down
     */
    private void checkNotShutdown() {
        if (isShutdown) {
            throw new IllegalStateException("DependencyContainer has been shut down");
        }
    }

    /**
     * Exception thrown when there are issues with dependency resolution.
     */
    public static class DependencyException extends RuntimeException {

        public DependencyException(String message) {
            super(message);
        }

        public DependencyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
