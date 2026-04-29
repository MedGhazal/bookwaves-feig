package de.bookwaves;

import de.feig.fedm.Config;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration class representing an RFID reader.
 */
public class ReaderConfig {
    private static Logger log() {
        return LoggerFactory.getLogger(ReaderConfig.class);
    }

    public enum ReaderType {
        GENERIC,
        MRU400
    }

    private String name;
    private String address;
    private int port;
    private Integer listenerPort;
    private String mode;
    private String protocol;
    private List<Integer> antennas = new ArrayList<>();
    private List<Integer> rssiFilters = new ArrayList<>();
    private List<Double> outputPowers = new ArrayList<>();
    private ReaderType type = ReaderType.GENERIC;

    public ReaderConfig() {
        // Default constructor for YAML deserialization
    }

    public ReaderConfig(String name, String address, int port, Integer listenerPort, String mode, List<Integer> antennas) {
        this.name = name;
        this.address = address;
        this.port = port;
        this.listenerPort = listenerPort;
        this.mode = mode;
        this.type = ReaderType.GENERIC;
        setAntennas(antennas);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Integer getListenerPort() {
        return listenerPort;
    }

    public void setListenerPort(Integer listenerPort) {
        this.listenerPort = listenerPort;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getProtocol() {
        if (protocol == null || protocol.isBlank()) {
            return "uhf";
        }
        return protocol.trim().toLowerCase();
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public ReaderType getType() {
        return type; 
    }

    public void setType(ReaderType type) {
        this.type = type != null ? type : ReaderType.GENERIC;
    }

    public boolean isHfProtocol() {
        return "hf".equalsIgnoreCase(getProtocol());
    }

    public List<Integer> getAntennas() {
        return antennas;
    }

    public void setAntennas(List<Integer> antennas) {
        this.antennas = antennas == null ? new ArrayList<>() : new ArrayList<>(antennas);
    }

    public List<Integer> getRssiFilters() {
        return rssiFilters; 
    }

    public void setRssiFilters(List<Integer> rssiFilters) {
        this.rssiFilters = rssiFilters == null ? new ArrayList<>() : new ArrayList<>(rssiFilters);
    }

    public List<Double> getOutputPowers() {
        return outputPowers; 
    }

    public void setOutputPowers(List<Double> outputPowers) {
        this.outputPowers = outputPowers == null ? new ArrayList<>() : new ArrayList<>(outputPowers);
    }


    public synchronized int applyConfig(Config readerConfig) {
        if (getType() == ReaderType.GENERIC) {
            log().info("Generic Reader; no configuration applied.");
        }
        return 0;
    }

    /**
     * Computes the antenna bitmask from the list of antenna numbers.
     * Antenna 1 = 0x01, Antenna 2 = 0x02, Antenna 3 = 0x04, etc.
     * Multiple antennas are combined with bitwise OR.
     * 
     * @return the antenna bitmask as a byte
     */
    public byte getAntennaMask() {
        int mask = 0;
        for (Integer antenna : antennas) {
            if (antenna == null) {
                log().warn("Ignoring null antenna index for reader {}", name);
                continue;
            }
            if (antenna >= 1 && antenna <= 8) {
                mask |= (1 << (antenna - 1));
            } else {
                log().warn("Ignoring invalid antenna index {} for reader {}", antenna, name);
            }
        }
        log().debug("Computed antenna mask 0x{} for reader {} from {}", String.format("%02X", mask), name, antennas);
        return (byte) mask;
    }

    /**
     * Returns the effective antenna mask with protocol-specific behavior.
     * HF readers currently run on antenna 1 only.
     */
    public byte getEffectiveAntennaMask() {
        if (isHfProtocol()) {
            return 0x01;
        }
        return getAntennaMask();
    }
}
