package org.manager.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.Executors;
import org.httrack.HttrackToolManager;
import org.manager.GlobalSettings;
import org.tor.TorToolManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Recommended configuration maps shipped by the tool managers. */
@DisplayName("Tool manager recommended configurations")
class RecommendedConfigTest {

    @Test
    @DisplayName("httrack recommends asset include patterns and engine defaults")
    void httrackRecommendedConfig() {
        HttrackToolManager manager = new HttrackToolManager(new GlobalSettings(), Executors.newCachedThreadPool());
        Map<String, String> config = manager.getRecommendedConfig();
        assertNotNull(config);
        assertTrue(config.containsKey("include-images"));
        assertTrue(config.get("include-images").contains("*.png"));
        assertTrue(config.containsKey("include-styles"));
        assertTrue(config.containsKey("include-scripts"));
    }

    @Test
    @DisplayName("tor recommends a socks port when the feature check runs")
    void torRecommendedConfig() {
        TorToolManager manager = new TorToolManager(new GlobalSettings(), Executors.newCachedThreadPool());
        Map<String, String> config = manager.getRecommendedConfig();
        assertNotNull(config);
    }
}
