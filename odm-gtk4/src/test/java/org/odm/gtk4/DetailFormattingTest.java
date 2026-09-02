package org.odm.gtk4;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class DetailFormattingTest {

    @Test
    void percentEncodedPeerIdsExposeTheClientPrefixAndEscapeBinaryBytes() {
        assertEquals("-UT360W-|\\xB8\\x94\\x90xlr\\x8F\\xFD\\xC6z^",
                DetailTabsPresenter.displayPeerId(
                        "%2DUT360W%2D%7C%B8%94%90xlr%8F%FD%C6z%5E"));
        assertEquals("-qB5220-)ist0:2kCWUT",
                DetailTabsPresenter.displayPeerId(
                        "%2DqB5220%2D%29ist0%3A2kCWUT"));
        assertEquals("—", DetailTabsPresenter.displayPeerId(null));
        assertEquals("—", DetailTabsPresenter.displayPeerId(
                "%00%00%00%00%00%00%00%00%00%00%00%00%00%00%00%00%00%00%00%00"));
        assertEquals("—", DetailTabsPresenter.displayPeerId("\0\0\0"));
    }

    @Test
    void fileTabPathsResolveAgainstTheDownloadDestination() {
        Path destination = Path.of("/tmp/downloads");
        assertEquals(Path.of("/tmp/downloads/release/file.bin"),
                FileManagerSupport.resolveDetailPath(destination, "release/file.bin"));
        assertEquals(Path.of("/var/tmp/file.bin"),
                FileManagerSupport.resolveDetailPath(destination, "/var/tmp/file.bin"));
        assertNull(FileManagerSupport.resolveDetailPath(destination, "—"));
    }
}
