package de.bookwaves;

// import de.feig.fedm.readerConfig;

import de.feig.fedm.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for FEIG MRU400 readers.
 * Extends ReaderConfig with MRU400-specific operating mode and RSSI filter settings.
 * Additional MRU400-specific parameters can be added in future contributions.
 */
public class MRU400ReaderConfig extends ReaderConfig {
    private static final Logger log = LoggerFactory.getLogger(MRU400ReaderConfig.class);

    private List<Integer> rssiFilters = new ArrayList<>();
    private List<Double> outputPowers = new ArrayList<>();

    private static final Map<Double, Integer> OUTPUT_POWER_TO_HEX =
        Map.of(
            0.1, 0x10,
            0.2, 0x11,
            0.3, 0x12,
            0.4, 0x13,
            0.5, 0x14,
            0.6, 0x15,
            0.7, 0x16,
            0.8, 0x17,
            0.9, 0x18,
            1.0, 0x19
        );

    public MRU400ReaderConfig() {}

    public MRU400ReaderConfig(
            String name, String address, int port,
            Integer listenerPort, String mode,
            List<Integer> antennas, List<Integer> rssiFilters, List<Double> outputPowers) {
        super(name, address, port, listenerPort, mode, antennas);
        this.rssiFilters = rssiFilters != null ? new ArrayList<>(rssiFilters) : new ArrayList<>();
        this.outputPowers = outputPowers != null ? new ArrayList<>(outputPowers) : new ArrayList<>();
        setType(ReaderType.MRU400);
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
        log.info("Reader {}: configuring MRU400 reader.", getName());
        int state = setReaderMode(readerConfig);
        if (state != 0) return state;

        // if (!rssiFilters.isEmpty()) {
        //     state = setReaderRssiFilter(readerConfig);
        //     if (state != 0) return state;
        // }

        readerConfig.applyConfiguration(true);

        return 0;
    }

    private int setConnectionHoldTime(Config readerConfig) {
        String param = "HostInterface.LAN.Remote.Channel1.ConnectionHoldTime";
        log.info("Reader {}: setting parameter {} to {}", getName(), param, 1000);
        int state = readerConfig.setConfigPara(param, 10000);

        if (state != 0) {
            log.error("Reader {}: failed to set connection hold time (error {})",
                getName(), state);
            return state;
        }

        return 0;
    }

    private int setTransponderValidTime(Config readerConfig) {
        String param = "OperatingMode.AutoReadModes.Filter.TransponderValidTime";
        log.info("Reader {}: setting parameter {} to {}", getName(), param, 1);
        int state = readerConfig.setConfigPara(param, 1);

        if (state != 0) {
            log.error("Reader {}: failed to set transponder valid time (error {})",
                getName(), state);
            return state;
        }

        return 0;
    }

    private int activateDataSelector(Config readerConfig, String dataSelector) {
        String param = String.format("OperatingMode.AutoReadModes.DataSelector.%s", dataSelector);
        log.info("Reader {}: activating data selector {} to {}", getName(), param, 0x1);
        int state = readerConfig.setConfigPara(param, 0x1);
        if (state != 0) {
            log.error("Reader {}: failed to set transmitted field {} (error {})",
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
        log.info("Reader {}: setting parameter {} to {}", getName(), param, value);

        int state = readerConfig.setConfigPara(param, value);
        if (state != 0) {
            log.error("Reader {}: failed to set to select antennas (error {})",
                getName(), state);
            return state;
        }

        return 0;
    }

    private int enableMultiplexer(Config readerConfig) {
        String param = "AirInterface.Multiplexer.Enable";
        int state = readerConfig.setConfigPara(param, 0x1);
        log.info("Reader {}: enabling parameter {} to {}", getName(), param, 0x1);
        if (state != 0) {
            log.error("Reader {}: failed to enable multiplexer (error {})",
                getName(), state);
            return state;
        }
        return 0;
    }

    private int setReaderMode(Config readerConfig) {
        String param = "OperatingMode.Mode";
        log.info("Reader {}: setting operating mode to {} mode", getName(), getMode());
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
            }
            default -> {
                log.error("Reader {} has unexpected mode {}", getName(), getMode());
                yield -1;
            }
        };
    }

    private int setReaderRssiFilter(Config readerConfig) {
        List<Integer> antennas = getAntennas();
        log.info("Reader {}: setting configured RSSI filters", getName());

        if (antennas.size() != rssiFilters.size()) {
            log.error("Reader {}: antennas ({}) and rssiFilters ({}) must be the same length",
                getName(), antennas.size(), rssiFilters.size());
            return -1;
        }

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            int rssiVal = rssiFilters.get(i);

            if (antenna < 1 || antenna > 8) {
                log.warn("Reader {}: ignoring invalid antenna index {}", getName(), antenna);
                continue;
            }

            String param = String.format(
                "ReaderConfig.AirInterface.Antenna.UHF.No%d.RSSI", antenna);
            log.info("Reader {}: setting parameter {} to {}", getName(), param, rssiVal);
            int state = readerConfig.setConfigPara(param, rssiVal);
            if (state != 0) {
                log.error("Reader {}: failed to set RSSI for antenna {} (error {})",
                    getName(), antenna, state);
                return state;
            }
        }

        return 0;
    }

    private int setReaderOutputPowers(Config readerConfig) {
        List<Integer> antennas = getAntennas();
        log.info("Reader {}: setting configured output powers of the antennas", getName());

        if (antennas.size() != outputPowers.size()) {
            log.error("Reader {}: antennas ({}) and outputPowers ({}) must be the same length",
                getName(), antennas.size(), rssiFilters.size());
            return -1;
        }

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            double configuredOutputPowerValue = outputPowers.get(i);

            if (!OUTPUT_POWER_TO_HEX.containsKey(configuredOutputPowerValue)) {
                log.error("Reader {}: Output power value {} not possible, possible values are {}",
                        getName(), configuredOutputPowerValue, OUTPUT_POWER_TO_HEX.keySet()
                );
            }

            if (antenna < 1 || antenna > 8) {
                log.warn("Reader {}: ignoring invalid antenna index {}", getName(), antenna);
                continue;
            }

            int outputPowerHEXValue = OUTPUT_POWER_TO_HEX.get(configuredOutputPowerValue);

            String param = String.format(
                "ReaderConfig.AirInterface.Antenna.UHF.No%d.OutputPower", antenna);
            log.info("Reader {}: setting parameter {} to {}", getName(), param, outputPowerHEXValue);

            int state = readerConfig.setConfigPara(param, outputPowerHEXValue);
            if (state != 0) {
                log.error("Reader {}: failed to set output power for antenna {} (error {})",
                    getName(), antenna, state);
                return state;
            }
        }

        return 0;
    }

    private int setChannelPortNumber(Config readerConfig) {
        String param = "HostInterface.LAN.Remote.Channel1.PortNumber";
        log.info("Reader {}: setting parameter {} to {}", getName(), param, getListenerPort());
        return switch (getMode()) {
            case "host"         -> {
                log.error("Configuration value not expected for 'reader {}' with mode {}", getName(), getMode());
                yield -1;
            }
            case "notification" -> readerConfig.setConfigPara(param, getListenerPort());
            default -> {
                log.error("Reader {} has unexpected mode {}", getName(), getMode());
                yield -1;
            }
        };
    }
}
