package de.bookwaves;

import de.feig.fedm.Config;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for FEIG MRU400 readers.
 * Extends ReaderConfig with MRU400-specific operating mode and RSSI filter settings.
 * Additional MRU400-specific parameters can be added in future contributions.
 */
public class MRU400ReaderConfig extends ReaderConfig {
    private static Logger log() {
        return LoggerFactory.getLogger(MRU400ReaderConfig.class);
    }

    private List<Integer> rssiFilters = new ArrayList<>();
    private List<Integer> outputPowers = new ArrayList<>();

    public MRU400ReaderConfig() {}

    public MRU400ReaderConfig(
            String name, String address, int port,
            Integer listenerPort, String mode,
            List<Integer> antennas, List<Integer> rssiFilters) {
        super(name, address, port, listenerPort, mode, antennas);
        this.rssiFilters = rssiFilters != null ? new ArrayList<>(rssiFilters) : new ArrayList<>();
    }

    public List<Integer> getRssiFilters()                    { return rssiFilters; }
    public void setRssiFilters(List<Integer> rssiFilters) {
        this.rssiFilters = rssiFilters == null ? new ArrayList<>() : new ArrayList<>(rssiFilters);
    }

    @Override
    public ReaderType getType() {
        return ReaderType.MRU400;
    }

    @Override
    public synchronized int applyConfig(Config readerConfig) {
        int state = setReaderMode(readerConfig);
        if (state != 0) return state;

        if (!rssiFilters.isEmpty()) {
            state = setReaderRssiFilter(readerConfig);
            if (state != 0) return state;
        }

        return 0;
    }

    private int setConnectionHoldTime(Config readerConfig) {
        String param = "HostInterface.LAN.Remote.Channel1.ConnectionHoldTime";
        int state = readerConfig.setConfigPara(param, 10000);

        if (state != 0) {
            log().error("Reader '{}': failed to set connection hold time (error {})",
                getName(), state);
            return state;
        }

        return 0;
    }

    private int setTransponderValidTime(Config readerConfig) {
        String param = "OperatingMode.AutoReadModes.Filter.TransponderValidTime";
        int state = readerConfig.setConfigPara(param, 1);

        if (state != 0) {
            log().error("Reader '{}': failed to set transponder valid time (error {})",
                getName(), state);
            return state;
        }

        return 0;
    }

    private int activateDataSelector(Config readerConfig, String dataSelector) {
        String param = String.format("OperatingMode.AutoReadModes.DataSelector.%s", dataSelector);
        int state = readerConfig.setConfigPara(param, 0x1);
        if (state != 0) {
            log().error("Reader '{}': failed to set transmitted field {} (error {})",
                getName(), param, state);
            return state;
        }
        return 0;
    }

    private int setTransmittedFields(Config readerConfig) {
        List<String> dataSelectors = List.of("Date", "Antenna", "UID", "Time");

        for (String dataSelector: dataSelectors) {
            activateDataSelector(readerConfig, dataSelector);
        }

        return 0;
    }

    private int setSelectedAntennas(Config readerConfig) {
        String param = "AirInterface.Multiplexer.UHF.Internal.SelectedAntennas";
        byte value = getAntennaMask();

        int state = readerConfig.setConfigPara(param, value);
        if (state != 0) {
            log().error("Reader '{}': failed to set to select antennas (error {})",
                getName(), state);
            return state;
        }

        return 0;
    }

    private int enableMultiplexer(Config readerConfig) {
        String param = "AirInterface.Multiplexer.Enable";
        int state = readerConfig.setConfigPara(param, 0x1);
        if (state != 0) {
            log().error("Reader '{}': failed to enable multiplexer (error {})",
                getName(), state);
            return state;
        }
        return 0
    }

    private int setReaderMode(Config readerConfig) {
        String param = "ReaderConfig.OperatingMode";
        int state;
        return switch (getMode()) {
            case "host"         -> readerConfig.setConfigPara(param, 0x00);
            case "notification" -> {
                state = readerConfig.setConfigPara(param, 0xC0);
                if (state != 0) {
                    yield state;
                }
                state = setSelectedAntennas(readerConfig);
                if (state != 0) {
                    yield state;
                }
                state = setTransmittedFields(readerConfig);
                if (state != 0) {
                    yield state;
                }
                state = setConnectionHoldTime(readerConfig);
                if (state != 0) {
                    yield state;
                }
                state = setTransponderValidTime(readerConfig);
                if (state != 0) {
                    yield state;
                }
                yield 0;
            };
            default -> {
                log().error("Reader '{}' has unexpected mode '{}'", getName(), getMode());
                yield -1;
            }
        };
    }

    private int setReaderRssiFilter(Config readerConfig) {
        List<Integer> antennas = getAntennas();

        if (antennas.size() != rssiFilters.size()) {
            log().error("Reader '{}': antennas ({}) and rssiFilters ({}) must be the same length",
                getName(), antennas.size(), rssiFilters.size());
            return -1;
        }

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            int rssiVal = rssiFilters.get(i);

            if (antenna < 1 || antenna > 8) {
                log().warn("Reader '{}': ignoring invalid antenna index {}", getName(), antenna);
                continue;
            }

            String param = String.format(
                "ReaderConfig.AirInterface.Antenna.UHF.No%d.RSSI", antenna);
            int state = readerConfig.setConfigPara(param, rssiVal);
            if (state != 0) {
                log().error("Reader '{}': failed to set RSSI for antenna {} (error {})",
                    getName(), antenna, state);
                return state;
            }
        }

        return 0;
    }

    private int setReaderOutputPowers(Config readerConfig) {
        List<Integer> antennas = getAntennas();

        if (antennas.size() != outputPowers.size()) {
            log().error("Reader '{}': antennas ({}) and outputPowers ({}) must be the same length",
                getName(), antennas.size(), rssiFilters.size());
            return -1;
        }

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            int outputPowerVal = outputPowers.get(i);

            if (antenna < 1 || antenna > 8) {
                log().warn("Reader '{}': ignoring invalid antenna index {}", getName(), antenna);
                continue;
            }

            String param = String.format(
                "ReaderConfig.AirInterface.Antenna.UHF.No%d.OutputPower", antenna);
            int state = readerConfig.setConfigPara(param, outputPowerVal);
            if (state != 0) {
                log().error("Reader '{}': failed to set output power for antenna {} (error {})",
                    getName(), antenna, state);
                return state;
            }
        }

        return 0;
    }

    private int setChannelPortNumber(Config readerConfig) {
        String param = "HostInterface.LAN.Remote.Channel1.PortNumber";
        return switch (getMode()) {
            case "host"         -> {
                log().error("Configuration value not expected for 'reader {}' with mode '{}'", getName(), getMode());
                yield -1;
            };
            case "notification" -> readerConfig.setConfigPara(param, getListenerPort());
            default -> {
                log().error("Reader '{}' has unexpected mode '{}'", getName(), getMode());
                yield -1;
            }
        };
    }
}
