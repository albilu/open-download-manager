package org.manager.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Exception factory semantics")
class ExceptionFactoriesTest {

    @Test
    @DisplayName("ConfigurationException factories carry key/file/value context and recovery flags")
    void configurationExceptionFactories() {
        ConfigurationException invalid = ConfigurationException.invalidSetting("maxSpeed", -5, "must be >= 0");
        assertEquals(ConfigurationException.ErrorCodes.INVALID_SETTING, invalid.getErrorCode());
        assertEquals("maxSpeed", invalid.getConfigurationKey());
        assertEquals(-5, invalid.getInvalidValue());
        assertFalse(invalid.isRecoverable(), "an invalid value does not fix itself on retry");
        assertTrue(invalid.getDetailedMessage().contains("maxSpeed"));

        ConfigurationException missing = ConfigurationException.missingConfiguration("/cfg/settings.json");
        assertEquals(ConfigurationException.ErrorCodes.MISSING_CONFIGURATION, missing.getErrorCode());
        assertEquals("/cfg/settings.json", missing.getConfigurationFile());
        assertTrue(missing.isRecoverable());

        ConfigurationException denied = ConfigurationException.fileAccessDenied("/cfg/settings.json", "write");
        assertEquals(ConfigurationException.ErrorCodes.FILE_ACCESS_DENIED, denied.getErrorCode());
        assertTrue(denied.isRecoverable());

        IOException cause = new IOException("disk full");
        ConfigurationException serialization = ConfigurationException.serializationFailed("/cfg/s.json", cause);
        assertEquals(ConfigurationException.ErrorCodes.SERIALIZATION_FAILED, serialization.getErrorCode());
        assertSame(cause, serialization.getCause());
        assertTrue(serialization.isRecoverable());

        ConfigurationException deserialization = ConfigurationException.deserializationFailed("/cfg/s.json", cause);
        assertEquals(ConfigurationException.ErrorCodes.DESERIALIZATION_FAILED, deserialization.getErrorCode());
        assertTrue(deserialization.isRecoverable());

        ConfigurationException validation = ConfigurationException.validationFailed("dir", "/tmp/x", "Path is not a directory");
        assertEquals(ConfigurationException.ErrorCodes.VALIDATION_FAILED, validation.getErrorCode());
        assertFalse(validation.isRecoverable());

        ConfigurationException version = ConfigurationException.incompatibleVersion("/cfg/s.json", "2", "1");
        assertEquals(ConfigurationException.ErrorCodes.INCOMPATIBLE_VERSION, version.getErrorCode());
        assertEquals("1", version.getInvalidValue());
        assertFalse(version.isRecoverable());
    }

    @Test
    @DisplayName("plain ConfigurationException defaults to recoverable validation failure")
    void plainConfigurationExceptionDefaults() {
        ConfigurationException ex = new ConfigurationException("bad config");
        assertEquals(ConfigurationException.ErrorCodes.VALIDATION_FAILED, ex.getErrorCode());
        assertTrue(ex.isRecoverable());
        assertNull(ex.getConfigurationKey());
        assertNull(ex.getConfigurationFile());
        assertNull(ex.getInvalidValue());
    }

    @Test
    @DisplayName("DependencyException factories carry tool context and recovery flags")
    void dependencyExceptionFactories() {
        DependencyException notFound = DependencyException.toolNotFound("aria2c", null);
        assertEquals(DependencyException.ErrorCodes.TOOL_NOT_FOUND, notFound.getErrorCode());
        assertEquals("aria2c", notFound.getToolName());
        assertFalse(notFound.isRecoverable());
        assertEquals("Tool 'aria2c' not found", notFound.getMessage());

        DependencyException notFoundWithReason = DependencyException.toolNotFound("yt-dlp", "not on PATH");
        assertTrue(notFoundWithReason.getMessage().contains("not on PATH"));

        DependencyException incompatible = DependencyException.toolIncompatible("aria2c", "1.36", "1.30");
        assertEquals(DependencyException.ErrorCodes.TOOL_INCOMPATIBLE, incompatible.getErrorCode());
        assertEquals("1.36", incompatible.getRequiredVersion());
        assertEquals("1.30", incompatible.getFoundVersion());
        assertFalse(incompatible.isRecoverable());

        RuntimeException cause = new RuntimeException("x");
        DependencyException init = DependencyException.initializationFailed("init broke", cause);
        assertEquals(DependencyException.ErrorCodes.INITIALIZATION_FAILED, init.getErrorCode());
        assertSame(cause, init.getCause());
        assertTrue(init.isRecoverable());

        DependencyException circular = DependencyException.circularDependency("a -> b -> a");
        assertEquals(DependencyException.ErrorCodes.CIRCULAR_DEPENDENCY, circular.getErrorCode());
        assertFalse(circular.isRecoverable());

        DependencyException permission = DependencyException.permissionDenied("curl", "execute");
        assertEquals(DependencyException.ErrorCodes.PERMISSION_DENIED, permission.getErrorCode());
        assertEquals("curl", permission.getToolName());
        assertTrue(permission.isRecoverable());
        assertTrue(permission.getDetailedMessage().contains("curl"));
    }

    @Test
    @DisplayName("DownloadException factories carry download identity and recovery flags")
    void downloadExceptionFactories() {
        IOException cause = new IOException("unreachable");
        DownloadException network = DownloadException.networkError("connect failed", cause, "d1", "http://x/y");
        assertEquals(DownloadException.ErrorCodes.NETWORK_ERROR, network.getErrorCode());
        assertEquals("d1", network.getDownloadId());
        assertEquals("http://x/y", network.getDownloadUrl());
        assertTrue(network.isRecoverable());
        assertTrue(network.getDetailedMessage().contains("d1"));
        assertTrue(network.getDetailedMessage().contains("http://x/y"));

        DownloadException fs = DownloadException.fileSystemError("no space", null, "d2");
        assertEquals(DownloadException.ErrorCodes.FILE_SYSTEM_ERROR, fs.getErrorCode());
        assertEquals("d2", fs.getDownloadId());
        assertTrue(fs.isRecoverable());

        DownloadException invalidUrl = DownloadException.invalidUrl("not a url", "notaurl");
        assertEquals(DownloadException.ErrorCodes.INVALID_URL, invalidUrl.getErrorCode());
        assertFalse(invalidUrl.isRecoverable());

        DownloadException timeout = DownloadException.timeout("too slow", "d3", "http://x/z");
        assertEquals(DownloadException.ErrorCodes.TIMEOUT, timeout.getErrorCode());
        assertTrue(timeout.isRecoverable());

        DownloadException auth = DownloadException.authenticationFailed("401", "http://x/auth");
        assertEquals(DownloadException.ErrorCodes.AUTHENTICATION_FAILED, auth.getErrorCode());
        assertFalse(auth.isRecoverable());
    }

    @Test
    @DisplayName("message-only constructors fall back to GENERAL_ERROR and unrecoverable")
    void generalErrorDefaults() {
        DownloadManagerException ex = new DownloadManagerException("boom");
        assertEquals("GENERAL_ERROR", ex.getErrorCode());
        assertFalse(ex.isRecoverable());
        assertEquals("[GENERAL_ERROR] boom", ex.getDetailedMessage());
        assertTrue(ex.toString().contains("DownloadManagerException"));
    }
}
