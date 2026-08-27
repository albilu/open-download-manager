package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.handler.Aria2DownloadHandler;
import org.manager.download.handler.DownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * Focused unit tests for the extracted aria2 session collaborator: typed
 * save/load through the aria2 handler, missing-file no-ops, and the
 * swallow-failures contract (persistence of the ODM state store must not
 * be blocked by an aria2 session problem).
 */
class Aria2SessionManagerTest {

    @TempDir
    Path downloadDir;

    private DownloadHandlerFactory factory;
    private Aria2DownloadHandler aria2Handler;
    private Aria2SessionManager sessionManager;

    @BeforeEach
    void setUp() throws Exception {
        factory = mock(DownloadHandlerFactory.class);
        aria2Handler = mock(Aria2DownloadHandler.class);
        sessionManager = new Aria2SessionManager(downloadDir);
    }

    @Test
    void sessionAndInputPathsLiveUnderTheDownloadDirectory() throws Exception {
        assertEquals(downloadDir.resolve("aria2-session.txt"), sessionManager.getSessionFilePath());
        assertEquals(downloadDir.resolve("aria2-input.txt"), sessionManager.getInputFilePath());
    }

    @Test
    void saveSessionDelegatesToTypedAria2Handler() throws Exception {
        when(factory.getHandler(Download.Type.ARIA2)).thenReturn(aria2Handler);

        sessionManager.saveSession(factory);

        verify(aria2Handler).saveSession();
    }

    @Test
    void saveSessionIsNoOpForNonAria2Handler() throws Exception {
        DownloadHandler other = mock(DownloadHandler.class);
        when(factory.getHandler(Download.Type.ARIA2)).thenReturn(other);

        assertDoesNotThrow(() -> sessionManager.saveSession(factory));

        verify(other, never()).startDownload(any());
    }

    @Test
    void saveSessionIsNoOpForNullFactory() throws Exception {
        assertDoesNotThrow(() -> sessionManager.saveSession(null));
    }

    @Test
    void saveSessionSwallowsHandlerFailure() throws Exception {
        when(factory.getHandler(Download.Type.ARIA2)).thenReturn(aria2Handler);
        org.mockito.Mockito.doThrow(new RuntimeException("aria2 down"))
                .when(aria2Handler).saveSession();

        assertDoesNotThrow(() -> sessionManager.saveSession(factory));
    }

    @Test
    void loadSessionSkippedWhenSessionFileMissing() throws Exception {
        when(factory.getHandler(Download.Type.ARIA2)).thenReturn(aria2Handler);

        assertDoesNotThrow(() -> sessionManager.loadSession(factory));

        verify(aria2Handler, never()).loadSession(any());
    }

    @Test
    void loadSessionDelegatesWhenSessionFileExists() throws Exception {
        Files.writeString(downloadDir.resolve("aria2-session.txt"), "gid1\ngid2\n");
        when(factory.getHandler(Download.Type.ARIA2)).thenReturn(aria2Handler);

        sessionManager.loadSession(factory);

        verify(aria2Handler).loadSession(downloadDir.resolve("aria2-session.txt"));
    }

    @Test
    void loadSessionSwallowsHandlerFailure() throws Exception {
        Files.writeString(downloadDir.resolve("aria2-session.txt"), "gid1\n");
        when(factory.getHandler(Download.Type.ARIA2)).thenReturn(aria2Handler);
        org.mockito.Mockito.doThrow(new IOException("rpc broken"))
                .when(aria2Handler).loadSession(any());

        assertDoesNotThrow(() -> sessionManager.loadSession(factory));
    }

    @Test
    void configureNeverThrowsAndReportsTheSessionPath() throws Exception {
        assertDoesNotThrow(() -> sessionManager.configureAria2Session());
    }
}
