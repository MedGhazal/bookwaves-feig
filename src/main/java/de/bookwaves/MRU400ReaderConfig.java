package de.bookwaves;

import de.bookwaves.ReaderManager.ReaderOperationException;

// import de.feig.fedm.Config;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.Connector;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.ReaderStatus;
import de.feig.fedm.types.BoolRef;
import de.feig.fedm.types.ByteRef;
import de.feig.fedm.types.LongRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static java.util.Map.entry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import java.lang.StringBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for FEIG MRU400 readers.
 * Extends ReaderConfig with MRU400-specific operating mode and RSSI filter settings.
 * Additional MRU400-specific parameters can be added in future contributions.
 */
public class MRU400ReaderConfig extends ReaderConfig {
    private static final Logger log = LoggerFactory.getLogger(MRU400ReaderConfig.class);

    private static final String MODE_PARAMETER = "OperatingMode.Mode";
    private static final String MULTIPLEXER_ACTIVATION_PARAMETER = "AirInterface.Multiplexer.Enable";
    private static final String PORT_NUMBER_PARAMETER = "HostInterface.LAN.Remote.Channel1.PortNumber";
    private static final String SELECTED_ANTENNAS_PARAMETER = "AirInterface.Multiplexer.UHF.Internal.SelectedAntennas";
    private static final String RSSI_FILTER_ANTENNA_TEMPLATE_PARAMETER = "AirInterface.Antenna.UHF.No%d.RSSIFilter";
    private static final String OUTPUT_POWER_ANTENNA_TEMPLATE_PARAMETER = "AirInterface.Antenna.UHF.No%d.OutputPower";
    private static final String DATA_SELECTOR_TEMPLATE_PARAMETER = "OperatingMode.AutoReadModes.DataSelector.%s";
    private static final String TRANSPONDER_VALID_TIME_PARAMETER = "OperatingMode.AutoReadModes.Filter.TransponderValidTime";
    private static final String CONNECTION_HOLD_TIME_PARAMETER = "HostInterface.LAN.Remote.Channel1.ConnectionHoldTime";
    // TODO: Check this parameter name in the documentation
    private static final String PERSISTANT_RESET_TIME_PARAMETER = "Transponder.PersistantReset";

    private static final int PERSISTANT_RESET_TIME = 1;
    private static final int TRANSPONDER_VALID_TIME = 1;
    private static final int CONNECTION_HOLD_TIME = 10000;

    private static String LOCALHOST = "127.0.0.1";
    private static int TIMEOUT = 2000; // 2 seconds

    private static final List<String> DATA_SELECTORS = List.of("Date", "Antenna", "UID", "Time");

    private static final Map<Double, Byte> OUTPUT_POWER_TO_HEX =
        Map.ofEntries(
            entry(0.1, (byte) 0x10),
            entry(0.2, (byte) 0x11),
            entry(0.3, (byte) 0x12),
            entry(0.4, (byte) 0x13),
            entry(0.5, (byte) 0x14),
            entry(0.6, (byte) 0x15),
            entry(0.7, (byte) 0x16),
            entry(0.8, (byte) 0x17),
            entry(0.9, (byte) 0x18),
            entry(1.0, (byte) 0x19)
        );
    private static final Map<String, Byte> MODE_TO_HEX =
        Map.ofEntries(
            entry("host", (byte) 0x00),
            entry("notification", (byte) 0xC0)
        );
    private static final Map<Integer, List<Integer>> SELECTED_ANTENNAS_TO_LIST =
        Map.ofEntries(
            entry(0x01, List.of(1)),
            entry(0x10, List.of(2)),
            entry(0x11, List.of(1, 2))
        );

    public MRU400ReaderConfig() {
        setType(ReaderType.MRU400);
    }

    @Override
    public ReaderType getType() {
        return ReaderType.MRU400;
    }

    public int checkReturnCode(int state, boolean change) throws ReaderOperationException {
        if (change) {
            if (state == 0) {
                log.info("Configuration of reader unchanged {}", getName());
            } else if (state == 1) {
                return ErrorCode.Ok;
            }
            if (state < ErrorCode.Ok) {
                throw new ReaderOperationException(
                    "Failed to change configuration of reader '" + getName() + " " + ErrorCode.toString(state)
                );
            } else if (state > ErrorCode.Ok) {
                throw new ReaderOperationException(
                    "Failed to read configuration of reader '" + getName() + " " + ReaderStatus.toString(state) 
                );
            }
        } else {
            if (state < ErrorCode.Ok) {
                throw new ReaderOperationException(
                    "Failed to read configuration of reader '" + getName() +
                    "' (error code: " + state + ")"
                );
            } else if (state > ErrorCode.Ok) {
                throw new ReaderOperationException(
                    "Failed to read configuration of reader '" + getName() +
                    "' (Reader code: " + state + ")"
                );
            }
        }
        return state;
    }

    public ConfigurationState checkReaderMode(ReaderModule readerModule) throws ReaderOperationException {
        ByteRef currentMode = new ByteRef ((byte) 0x0);
        int state = readerModule.config().getConfigPara(MODE_PARAMETER, currentMode);
        state = checkReturnCode(state, false);
        if (MODE_TO_HEX.get(getMode()) != currentMode.getValue()) return ConfigurationState.MISCONFIGURED;
        return ConfigurationState.CONFIGURED;
    }

    public ConfigurationState checkRSSIFilters(ReaderModule readerModule) throws ReaderOperationException {
        List<Integer> antennas = getAntennas();

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            LongRef configuredRssiFilter = new LongRef(getRssiFilters().get(i));
            LongRef currentRssiFilter = new LongRef();
            String param = String.format(RSSI_FILTER_ANTENNA_TEMPLATE_PARAMETER, antenna);
            log.debug("Reader {}: Checking parameter {}", getName(), param);
            int state = readerModule.config().getConfigPara(param, currentRssiFilter);
            state = checkReturnCode(state, false);
            if (currentRssiFilter.getValue() != configuredRssiFilter.getValue()) return ConfigurationState.MISCONFIGURED;
        }
        
        return ConfigurationState.CONFIGURED;
    }

    public ConfigurationState checkOutputPowers(ReaderModule readerModule) throws ReaderOperationException {
        List<Integer> antennas = getAntennas();

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            ByteRef configuredOutputPower = new ByteRef((byte) OUTPUT_POWER_TO_HEX.get(getOutputPowers().get(i)));
            ByteRef currentOutputPower = new ByteRef(); 
            String param = String.format(OUTPUT_POWER_ANTENNA_TEMPLATE_PARAMETER, antenna);
            int state = readerModule.config().getConfigPara(param, currentOutputPower);
            state = checkReturnCode(state, false);
            if (currentOutputPower.getValue() != configuredOutputPower.getValue()) return ConfigurationState.MISCONFIGURED;
        }
        
        return ConfigurationState.CONFIGURED;
    }

    @Override
    public synchronized ConfigurationState checkConfig(ReaderModule readerModule) throws ReaderOperationException { 
        log.info("Reader {}: Validating the configuration", getName());
        int state = readerModule.config().readCompleteConfiguration();
        state = checkReturnCode(state, false);

        ConfigurationState configState = checkReaderMode(readerModule);
        if (configState == ConfigurationState.MISCONFIGURED) return configState;

        log.info("Check RSSI filters");

        configState = checkRSSIFilters(readerModule);
        if (configState == ConfigurationState.MISCONFIGURED) return configState;
        
        configState = checkOutputPowers(readerModule);
        if (configState == ConfigurationState.MISCONFIGURED) return configState;

        return ConfigurationState.CONFIGURED;
    }

    @Override
    public synchronized int applyConfig(ReaderModule readerModule) throws ReaderOperationException {
        log.info("Reader {}: configuring MRU400 reader.", getName());
        // int state = readerModule.config().resetCompleteConfiguration();
        // checkReturnCode(state, false);

        int state = setReaderMode(readerModule);
        if (state != ErrorCode.Ok) return state;

        state = setReaderRSSIFilters(readerModule);
        if (state != ErrorCode.Ok) return state;

        state = setReaderOutputPowers(readerModule);
        if (state != ErrorCode.Ok) return state;

        state = readerModule.config().applyConfiguration(true);
        state = checkReturnCode(state, true);
        if (state != ErrorCode.Ok) return state;

        return ErrorCode.Ok;
    }

    private int setConnectionHoldTime(ReaderModule readerModule) {
        log.info("Reader {}: setting parameter {} to {}", getName(), CONNECTION_HOLD_TIME_PARAMETER, CONNECTION_HOLD_TIME);
        int state = readerModule.config().changeConfigPara(CONNECTION_HOLD_TIME_PARAMETER, CONNECTION_HOLD_TIME);

        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to set connection hold time (error {})", getName(), state);
            return state;
        }

        return ErrorCode.Ok;
    }

    private int setTransponderValidTime(ReaderModule readerModule) {
        log.info("Reader {}: setting parameter {} to {}", getName(), TRANSPONDER_VALID_TIME_PARAMETER, TRANSPONDER_VALID_TIME);
        int state = readerModule.config().changeConfigPara(TRANSPONDER_VALID_TIME_PARAMETER, TRANSPONDER_VALID_TIME);

        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to set transponder valid time (error {})", getName(), state);
            return state;
        }

        return ErrorCode.Ok;
    }

    private int activateDataSelector(ReaderModule readerModule, String dataSelector) {
        String param = String.format(DATA_SELECTOR_TEMPLATE_PARAMETER, dataSelector);
        log.info("Reader {}: activating data selector {} to {}", getName(), param, 0x1);
        int state = readerModule.config().changeConfigPara(param, 0x1);
        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to set transmitted field {} (error {})", getName(), param, state);
            return state;
        }
        return ErrorCode.Ok;
    }

    private int setTransmittedFields(ReaderModule readerModule) {
        for (String dataSelector: DATA_SELECTORS) activateDataSelector(readerModule, DATA_SELECTORS);
        return ErrorCode.Ok;
    }

    private int setSelectedAntennas(ReaderModule readerModule) {
        byte value = getAntennaMask();
        log.info("Reader {}: setting parameter {} to {}", getName(), SELECTED_ANTENNAS_PARAMETER, value);

        int state = readerModule.config().changeConfigPara(SELECTED_ANTENNAS_PARAMETER, value);
        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to set to select antennas (error {})", getName(), state);
            return state;
        }

        return ErrorCode.Ok;
    }

    private int enableMultiplexer(ReaderModule readerModule) {
        int state = readerModule.config().changeConfigPara(MULTIPLEXER_ACTIVATION_PARAMETER, true);
        log.info("Reader {}: enabling parameter {} to {}", getName(), MULTIPLEXER_ACTIVATION_PARAMETER, true);
        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to enable multiplexer (error {})", getName(), state);
            return state;
        }
        return ErrorCode.Ok;
    }

    private int setPersistenceResetTime(ReaderModule readerModule) {
        int state = readerModule.config().changeConfigPara(PERSISTANT_RESET_TIME_PARAMETER, PERSISTANT_RESET_TIME);
        log.info("Reader {}: setting optimal persistant reset time", getName());
        if (state != ErrorCode.Ok) {
            log.error("Reader {}: failed to sett optimat persistant reset time (error {})", getName(), state);
            return state;
        }
        return ErrorCode.Ok;
    }

    private int setReaderMode(ReaderModule readerModule) throws ReaderOperationException {
        StringBuilder currentMode = new StringBuilder();
        log.info("Reader {}: setting operating mode to {} mode", getName(), getMode());

        return switch (getMode()) {
            case "host"         -> {
                int state = readerModule.config().changeConfigPara(MODE_PARAMETER, MODE_TO_HEX.get("host"));
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                state = setPersistenceResetTime(readerModule);
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                yield ErrorCode.Ok;
            }
            case "notification" -> {
                int state = readerModule.config().changeConfigPara(MODE_PARAMETER, MODE_TO_HEX.get("notification"));
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                state = enableMultiplexer(readerModule);
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                state = setSelectedAntennas(readerModule);
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                state = setTransmittedFields(readerModule);
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                state = setConnectionHoldTime(readerModule);
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                state = setTransponderValidTime(readerModule);
                state = checkReturnCode(state, true);
                if (state != ErrorCode.Ok) yield state;
                yield ErrorCode.Ok;
            }
            default -> {
                log.error("Reader {} has unexpected mode {}", getName(), getMode());
                yield -1;
            }
        };
    }

    private int setReaderRSSIFilters(ReaderModule readerModule) throws ReaderOperationException{
        List<Integer> antennas = getAntennas();
        List<Integer> rssiFilters = getRssiFilters();
        log.info("Reader {}: setting configured RSSI filters", getName());

        if (antennas.size() != rssiFilters.size()) {
            log.error("Reader {}: antennas ({}) and rssiFilters ({}) must be the same length", getName(), antennas.size(), rssiFilters);
            return -1;
        }

        for (int i = 0; i < antennas.size(); i++) {
            int antenna = antennas.get(i);
            int rssiVal = rssiFilters.get(i);

            if (antenna < 1 || antenna > 4) {
                log.warn("Reader {}: ignoring invalid antenna index {}", getName(), antenna);
                continue;
            }

            String param = String.format(RSSI_FILTER_ANTENNA_TEMPLATE_PARAMETER, antenna);
            log.info("Reader {}: setting parameter {} to {}", getName(), param, rssiVal);
            int state = readerModule.config().changeConfigPara(param, rssiVal);
            state = checkReturnCode(state, true);
            if (state != ErrorCode.Ok) {
                log.error("Reader {}: failed to set RSSI for antenna {} (error {})",
                    getName(), antenna, state);
                return state;
            }
        }

        return ErrorCode.Ok;
    }

    private int setReaderOutputPowers(ReaderModule readerModule) throws ReaderOperationException {
        List<Integer> antennas = getAntennas();
        List<Double> outputPowers = getOutputPowers();
        log.info("Reader {}: setting configured output powers of the antennas", getName());

        if (antennas.size() != outputPowers.size()) {
            log.error("Reader {}: antennas ({}) and outputPowers ({}) must be the same length",
                getName(), antennas.size(), outputPowers.size());
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

            if (antenna < 1 || antenna > 4) {
                log.warn("Reader {}: ignoring invalid antenna index {}", getName(), antenna);
                continue;
            }

            int outputPowerHEXValue = OUTPUT_POWER_TO_HEX.get(configuredOutputPowerValue);

            String param = String.format(OUTPUT_POWER_ANTENNA_TEMPLATE_PARAMETER, antenna);
            log.info("Reader {}: setting parameter {} to {}", getName(), param, outputPowerHEXValue);

            int state = readerModule.config().changeConfigPara(param, (byte) outputPowerHEXValue);
            state = checkReturnCode(state, true);
            if (state != ErrorCode.Ok) {
                log.error("Reader {}: failed to set output power for antenna 0x{} to {} (error {})",
                    getName(), antenna, String.format("%02X", outputPowerHEXValue), state);
                return state;
            }
        }

        return ErrorCode.Ok;
    }

    private int setChannelPortNumber(ReaderModule readerModule) {
        log.debug("Reader {}: setting parameter {} to {}", getName(), PORT_NUMBER_PARAMETER, getListenerPort());
        return switch (getMode()) {
            case "host"         -> {
                log.error("Configuration value not expected for 'reader {}' with mode {}", getName(), getMode());
                yield -1;
            }
            case "notification" -> {
                int port = getListenerPort();
                if (isPortOpen(port)) { 
                    int state = readerModule.config().changeConfigPara(param, (long) getListenerPort());
                } else {
                    log.error("Open port {} to correctly use reader {} in notification mode", port, getName());
                    yield -1;
                }
                yield state;
            }
            default -> {
                log.error("Reader {} has unexpected mode {}", getName(), getMode());
                yield -1;
            }
        };
    }

    private boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOCALHOST, port), TIMEOUT);
            return true; 
        } catch (IOException e) {
            return false; 
        }
    }
}
