package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Pins the role-interface split of {@link DownloadManager}: the composite
 * interface must stay a thin lifecycle + settings facade over cohesive role
 * interfaces, and every method name in the family must be owned by exactly
 * one interface so responsibilities cannot silently drift back together.
 */
class DownloadManagerRoleSplitTest {

    private static final List<Class<?>> ROLE_INTERFACES = List.of(
            DownloadOperations.class,
            DownloadQueries.class,
            QueueOperations.class,
            DownloadListenerRegistry.class,
            AfterCompletionActions.class,
            DownloadMaintenance.class,
            MonitoringControl.class,
            SessionPersistence.class);

    @Test
    void downloadManagerComposesAllRoleInterfaces() {
        for (Class<?> role : ROLE_INTERFACES) {
            assertTrue(role.isAssignableFrom(DownloadManager.class),
                    "DownloadManager must extend " + role.getSimpleName());
            assertTrue(role.isInterface(),
                    role.getSimpleName() + " must remain an interface");
        }
    }

    @Test
    void implImplementsTheCompositeInterface() {
        assertTrue(DownloadManager.class.isAssignableFrom(DownloadManagerImpl.class),
                "DownloadManagerImpl must implement DownloadManager");
    }

    @Test
    void compositeOnlyOwnsLifecycleAndSettings() {
        Set<String> declared = methodNames(DownloadManager.class);
        assertEquals(
                new TreeSet<>(Set.of(
                        "initialize",
                        "shutdown",
                        "getGlobalSettings",
                        "setGlobalSettings",
                        "applyGlobalSettingsToActiveDownloads")),
                declared,
                "DownloadManager itself must only declare lifecycle + settings methods");
    }

    @Test
    void everyMethodNameHasExactlyOneOwner() {
        Map<String, Set<String>> ownersByMethod = new HashMap<>();
        List<Class<?>> family = new java.util.ArrayList<>(ROLE_INTERFACES);
        family.add(DownloadManager.class);
        for (Class<?> iface : family) {
            for (String name : methodNames(iface)) {
                ownersByMethod.computeIfAbsent(name, n -> new TreeSet<>())
                        .add(iface.getSimpleName());
            }
        }
        Map<String, Set<String>> duplicated = ownersByMethod.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals(Map.of(), duplicated,
                "Method names declared by more than one interface in the family");
    }

    private static Set<String> methodNames(Class<?> iface) {
        return Arrays.stream(iface.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
