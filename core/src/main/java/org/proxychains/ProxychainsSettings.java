package org.proxychains;

import java.util.Map;
import org.manager.ApplicationContext;
import org.manager.download.DownloadSettings;
import org.manager.tools.ToolManagerFactory;
import org.proxychains.ProxychainsToolManager;

/**
 * Settings specific to proxychains downloads. Provides configuration options
 * for downloads that go through proxychains.
 */
public class ProxychainsSettings extends DownloadSettings {

    private String configFile = null;
    private boolean quiet = false;
    private String program = ApplicationContext.getToolPath("aria2"); // aria2c, curl, or custom
    private String customProgram = null;
    private boolean forceV4 = false;
    private boolean forceV6 = false;
    private int randomChain = 0; // 0 = off, >0 = number of proxies to use
    private boolean randomize = false;
    private boolean chainLen = false;
    private int chainLength = 1;
    private boolean torMode = false;
    private boolean strictChain = false;

    /**
     * Gets the proxychains config file path.
     *
     * @return The proxychains config file path
     */
    public String getConfigFile() {
        return configFile;
    }

    /**
     * Sets the proxychains config file path.
     *
     * @param configFile The proxychains config file path
     * @return This settings object for chaining
     */
    public ProxychainsSettings setConfigFile(String configFile) {
        this.configFile = configFile;
        return this;
    }

    /**
     * Checks if quiet mode is enabled.
     *
     * @return true if quiet mode is enabled, false otherwise
     */
    public boolean isQuiet() {
        return quiet;
    }

    /**
     * Sets whether to enable quiet mode.
     *
     * @param quiet true to enable quiet mode, false otherwise
     * @return This settings object for chaining
     */
    public ProxychainsSettings setQuiet(boolean quiet) {
        this.quiet = quiet;
        return this;
    }

    /**
     * Gets the program to run through proxychains.
     *
     * @return The program to run through proxychains
     */
    public String getProgram() {
        return program;
    }

    /**
     * Sets the program to run through proxychains. Valid values: "aria2c",
     * "curl", or custom
     *
     * @param program The program to run through proxychains
     * @return This settings object for chaining
     */
    public ProxychainsSettings setProgram(String program) {
        this.program = program;
        return this;
    }

    /**
     * Gets the custom program to run through proxychains.
     *
     * @return The custom program to run through proxychains
     */
    public String getCustomProgram() {
        return customProgram;
    }

    /**
     * Sets the custom program to run through proxychains. This is used when
     * program is set to a custom value.
     *
     * @param customProgram The custom program to run through proxychains
     * @return This settings object for chaining
     */
    public ProxychainsSettings setCustomProgram(String customProgram) {
        this.customProgram = customProgram;
        return this;
    }

    /**
     * Checks if IPv4 should be forced.
     *
     * @return true if IPv4 should be forced, false otherwise
     */
    public boolean isForceV4() {
        return forceV4;
    }

    /**
     * Sets whether to force IPv4.
     *
     * @param forceV4 true to force IPv4, false otherwise
     * @return This settings object for chaining
     */
    public ProxychainsSettings setForceV4(boolean forceV4) {
        this.forceV4 = forceV4;
        return this;
    }

    /**
     * Checks if IPv6 should be forced.
     *
     * @return true if IPv6 should be forced, false otherwise
     */
    public boolean isForceV6() {
        return forceV6;
    }

    /**
     * Sets whether to force IPv6.
     *
     * @param forceV6 true to force IPv6, false otherwise
     * @return This settings object for chaining
     */
    public ProxychainsSettings setForceV6(boolean forceV6) {
        this.forceV6 = forceV6;
        return this;
    }

    /**
     * Gets the random chain setting. 0 means off, >0 means number of proxies to
     * use in random chain.
     *
     * @return The random chain setting
     */
    public int getRandomChain() {
        return randomChain;
    }

    /**
     * Sets the random chain setting. Set to 0 to disable, >0 to specify number
     * of proxies to use in random chain.
     *
     * @param randomChain The random chain setting
     * @return This settings object for chaining
     */
    public ProxychainsSettings setRandomChain(int randomChain) {
        this.randomChain = randomChain;
        return this;
    }

    /**
     * Checks if randomize mode is enabled.
     *
     * @return true if randomize mode is enabled, false otherwise
     */
    public boolean isRandomize() {
        return randomize;
    }

    /**
     * Sets whether to enable randomize mode.
     *
     * @param randomize true to enable randomize mode, false otherwise
     * @return This settings object for chaining
     */
    public ProxychainsSettings setRandomize(boolean randomize) {
        this.randomize = randomize;
        return this;
    }

    /**
     * Checks if chain length specification is enabled.
     *
     * @return true if chain length specification is enabled, false otherwise
     */
    public boolean isChainLen() {
        return chainLen;
    }

    /**
     * Sets whether to specify chain length.
     *
     * @param chainLen true to specify chain length, false otherwise
     * @return This settings object for chaining
     */
    public ProxychainsSettings setChainLen(boolean chainLen) {
        this.chainLen = chainLen;
        return this;
    }

    /**
     * Gets the chain length.
     *
     * @return The chain length
     */
    public int getChainLength() {
        return chainLength;
    }

    /**
     * Sets the chain length.
     *
     * @param chainLength The chain length
     * @return This settings object for chaining
     */
    public ProxychainsSettings setChainLength(int chainLength) {
        this.chainLength = chainLength;
        return this;
    }

    /**
     * Checks if Tor mode is enabled.
     *
     * @return true if Tor mode is enabled, false otherwise
     */
    public boolean isTorMode() {
        return torMode;
    }

    /**
     * Sets whether to enable Tor mode.
     *
     * @param torMode true to enable Tor mode, false otherwise
     * @return This settings object for chaining
     */
    public ProxychainsSettings setTorMode(boolean torMode) {
        this.torMode = torMode;
        return this;
    }

    /**
     * Checks if strict chain mode is enabled.
     *
     * @return true if strict chain mode is enabled, false otherwise
     */
    public boolean isStrictChain() {
        return strictChain;
    }

    /**
     * Sets whether to enable strict chain mode.
     *
     * @param strictChain true to enable strict chain mode, false otherwise
     * @return This settings object for chaining
     */
    public ProxychainsSettings setStrictChain(boolean strictChain) {
        this.strictChain = strictChain;
        return this;
    }

    @Override
    public Map<String, String> toMap() {
        Map<String, String> map = super.toMap();

        if (configFile != null) {
            map.put("proxychains.config", configFile);
        }

        if (quiet) {
            map.put("proxychains.quiet", "true");
        }

        map.put("proxychains.program", program);

        if (customProgram != null) {
            map.put("proxychains.custom-program", customProgram);
        }

        if (forceV4) {
            map.put("proxychains.4", "true");
        }

        if (forceV6) {
            map.put("proxychains.6", "true");
        }

        if (randomChain > 0) {
            map.put("proxychains.random-chain", String.valueOf(randomChain));
        }

        if (randomize) {
            map.put("proxychains.random", "true");
        }

        if (chainLen) {
            map.put("proxychains.chain-len", String.valueOf(chainLength));
        }

        if (torMode) {
            map.put("proxychains.tor", "true");
        }

        if (strictChain) {
            map.put("proxychains.strict", "true");
        }

        return map;
    }

    @Override
    public DownloadSettings copy() {
        ProxychainsSettings copy = new ProxychainsSettings();

        // Copy base settings
        copy.setConnections(this.getConnections());
        copy.setUseProxy(this.isUseProxy());
        copy.setProxyAddress(this.getProxyAddress());

        // Copy Proxychains-specific settings
        copy.configFile = this.configFile;
        copy.quiet = this.quiet;
        copy.program = this.program;
        copy.customProgram = this.customProgram;
        copy.forceV4 = this.forceV4;
        copy.forceV6 = this.forceV6;
        copy.randomChain = this.randomChain;
        copy.randomize = this.randomize;
        copy.chainLen = this.chainLen;
        copy.chainLength = this.chainLength;
        copy.torMode = this.torMode;
        copy.strictChain = this.strictChain;

        return copy;
    }
}
