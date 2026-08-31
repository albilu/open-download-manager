package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProgressPresentationTest {

    @Test
    void percentageAlwaysUsesTwoDecimalsAndLocaleIndependentPunctuation() {
        assertEquals("10.25%", ProgressPresentation.percentage(10.25));
        assertEquals("0.00%", ProgressPresentation.percentage(0));
        assertEquals("100.00%", ProgressPresentation.percentage(100));
    }

    @Test
    void percentageAndFillValuesAreClampedToAValidProgressRange() {
        assertEquals("0.00%", ProgressPresentation.percentage(-4.5));
        assertEquals("100.00%", ProgressPresentation.percentage(180));
        assertEquals("0.00%", ProgressPresentation.percentage(Double.NaN));
        assertEquals(10, ProgressPresentation.wholePercentage(10.25));
        assertEquals(0.1025, ProgressPresentation.fraction(10.25), 0.000001);
    }
}
