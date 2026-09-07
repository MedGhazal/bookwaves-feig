package de.bookwaves;

import de.feig.fedm.Connector;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.RequestMode;

import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * The readers a hardware run is pointed at.
 *
 * <p>Described by a gitignored {@code config.hardware.yaml} in the same shape as
 * {@code config.yaml}. A missing file, or a reader that does not answer, skips the run
 * rather than failing it.
 */
final class HardwareReaders {

    /** Where the run reads its readers from. */
    static final String CONFIG_FILE = "config.hardware.yaml";

    private static final int CONNECT_TIMEOUT_MS = 5000;

    private static ConfigLoader.Configuration parsed;

    private HardwareReaders() {
    }

    /** The synchronised readers in {@link #CONFIG_FILE}, or a skipped run when there are none. */
    static List<ReaderConfig> managed() throws Exception {
        List<ReaderConfig> readers = configuration().getReaders();
        if (readers == null) {
            Assumptions.abort(CONFIG_FILE + " declares no readers");
        }

        ConfigLoader.validateReaderConfigurations(readers);
        List<ReaderConfig> managed = readers.stream().filter(ReaderConfig::isManaged).toList();
        if (managed.isEmpty()) {
            Assumptions.abort(CONFIG_FILE + " has no reader with a type; nothing to synchronise");
        }
        return managed;
    }

    /**
     * The address readers should send notifications to, or null when the file does not
     * set one. Null leaves each reader's own target untouched.
     */
    static String hostName() throws Exception {
        return configuration().getHostName();
    }

    private static synchronized ConfigLoader.Configuration configuration() throws Exception {
        if (parsed != null) {
            return parsed;
        }
        File file = new File(CONFIG_FILE);
        if (!file.isFile()) {
            Assumptions.abort("No " + CONFIG_FILE + "; copy config.example.yaml to it and"
                + " point it at the readers to test");
        }
        try (InputStream stream = new FileInputStream(file)) {
            parsed = ConfigLoader.parse(stream);
        }
        return parsed;
    }

    /**
     * A connection to {@code config}, or a skipped test when the reader does not answer.
     *
     * <p>Credentials go on the connector, as the service sets them.
     */
    static ReaderModule connect(ReaderConfig config) {
        try {
            return open(config);
        } catch (LinkageError e) {
            // Without the native library no reader is reachable: environment, not failure.
            return Assumptions.abort("The FEIG SDK native library is not on"
                + " java.library.path, so no reader can be reached: " + e.getMessage());
        }
    }

    private static ReaderModule open(ReaderConfig config) {
        ReaderModule module = new ReaderModule(RequestMode.UniDirectional);
        Connector connector = Connector.createTcpConnector(config.getAddress(), config.getPort());
        connector.setTcpConnectTimeout(CONNECT_TIMEOUT_MS);
        if (config.hasCredentials()) {
            connector.setAuthentication(config.getUsername(), config.getPassword());
        }

        int state = module.connect(connector);
        if (state != ErrorCode.Ok) {
            String detail = module.lastErrorStatusText();
            close(module);
            Assumptions.abort("Reader " + config.getName() + " at " + config.getAddress()
                + " did not answer (" + detail + "); powered down or unreachable");
        }
        return module;
    }

    static void close(ReaderModule module) {
        if (module == null) {
            return;
        }
        try {
            if (module.isConnected()) {
                module.disconnect();
            }
            module.close();
        } catch (Exception e) {
            // A reader that has already gone away has nothing left to release.
        }
    }
}
