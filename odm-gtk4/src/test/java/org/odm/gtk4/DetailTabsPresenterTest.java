package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Plain unit tests for the detail-tab numeric helpers.
 */
class DetailTabsPresenterTest {

    @Test
    void parseLongToleratesNullAndGarbage() {
        assertEquals(42L, DetailTabsPresenter.parseLong(42L, 0));
        assertEquals(42L, DetailTabsPresenter.parseLong("42", 0));
        assertEquals(0L, DetailTabsPresenter.parseLong(null, 0));
        assertEquals(7L, DetailTabsPresenter.parseLong("not-a-number", 7));
        assertEquals(7L, DetailTabsPresenter.parseLong(null, 7));
    }

    @Test
    void progressPercentGuardsAgainstZeroTotal() {
        assertEquals(50.0, DetailTabsPresenter.progressPercent(5, 10));
        assertEquals(0.0, DetailTabsPresenter.progressPercent(5, 0));
    }
}
