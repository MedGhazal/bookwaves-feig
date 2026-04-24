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

    private int setReaderMode(Config readerConfig) {
        String param = "ReaderConfig.OperatingMode";
        return switch (getMode()) {
            case "host"         -> readerConfig.setConfigPara(param, 0x00);
            case "notification" -> readerConfig.setConfigPara(param, 0xC0);
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
}
