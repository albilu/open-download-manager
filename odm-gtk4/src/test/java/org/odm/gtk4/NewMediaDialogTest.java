package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("New Media playlist selection")
class NewMediaDialogTest {

    @Test
    @DisplayName("Automatic format remains the first per-record choice")
    void automaticAndConcreteFormatSelectionUseTheExpectedIds() {
        org.ytdlp.YtDlpClient.VideoFormat first = new org.ytdlp.YtDlpClient.VideoFormat();
        first.setFormatId("137");

        assertEquals("", NewMediaDialog.selectedFormatId(0, java.util.List.of(first)));
        assertEquals("137", NewMediaDialog.selectedFormatId(1, java.util.List.of(first)));
    }

    @Test
    @DisplayName("list, range and stepped-range expressions select preview rows")
    void playlistExpressionMatchesPreviewRows() {
        String expression = "1:5:2,8,10:12";

        assertTrue(NewMediaDialog.playlistExpressionContains(expression, 1, 20));
        assertTrue(NewMediaDialog.playlistExpressionContains(expression, 3, 20));
        assertTrue(NewMediaDialog.playlistExpressionContains(expression, 8, 20));
        assertTrue(NewMediaDialog.playlistExpressionContains(expression, 11, 20));
        assertFalse(NewMediaDialog.playlistExpressionContains(expression, 2, 20));
        assertFalse(NewMediaDialog.playlistExpressionContains(expression, 9, 20));
    }

    @Test
    @DisplayName("open-ended ranges use the playlist bounds")
    void openRangesUsePreviewBounds() {
        assertTrue(NewMediaDialog.playlistExpressionContains(":3", 1, 10));
        assertTrue(NewMediaDialog.playlistExpressionContains("8:", 10, 10));
        assertFalse(NewMediaDialog.playlistExpressionContains(":3", 4, 10));
    }

    @Test
    @DisplayName("negative indices and reverse ranges follow yt-dlp semantics")
    void negativeAndReverseRangesUsePreviewBounds() {
        assertTrue(NewMediaDialog.playlistExpressionContains("-1", 10, 10));
        assertTrue(NewMediaDialog.playlistExpressionContains("-5::-2", 6, 10));
        assertTrue(NewMediaDialog.playlistExpressionContains("-5::-2", 2, 10));
        assertFalse(NewMediaDialog.playlistExpressionContains("-5::-2", 5, 10));
    }
}
