package de.bookwaves;

import de.bookwaves.sync.ParamSpec;
import de.bookwaves.sync.ParamType;
import de.bookwaves.sync.ParamValue;
import de.bookwaves.sync.ProtectedParameters;
import de.bookwaves.sync.ReaderProfile;
import de.bookwaves.sync.ReaderProfiles;

import de.feig.fedm.ConfigParamInfo;
import de.feig.fedm.Connector;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.ReaderStatus;
import de.feig.fedm.RequestMode;
import de.feig.fedm.types.BoolRef;
import de.feig.fedm.types.ByteRef;
import de.feig.fedm.types.LongRef;

import java.util.List;

/**
 * Reports what a physical reader actually supports, so the profiles can be checked
 * against hardware instead of against the SDK's parameter strings.
 *
 * <p>Read-only unless {@code --write} is given. The write phase steps one RSSI filter and
 * puts it back, unless {@code --leave-written} says to leave the change in place.
 * {@code --set} writes one numeric parameter to an exact value instead.
 *
 * <p>Every write is permanent, whatever a non-persistent apply is asked for or answers.
 *
 * <pre>
 *   java -cp ... de.bookwaves.ReaderProbe &lt;reader-name&gt; [--write [--leave-written]]
 *   java -cp ... de.bookwaves.ReaderProbe &lt;reader-name&gt; --set &lt;parameter&gt;=&lt;value&gt;
 * </pre>
 */
public final class ReaderProbe {

    /** Parameter names worth testing beyond those the profiles use. */
    private static final List<String> EXTRA_CANDIDATES = List.of(
        // Data selector names differ between generations; confirm which tree exists.
        "OperatingMode.AutoReadModes.DataSelector.Antenna",
        "OperatingMode.AutoReadModes.DataSelector.AntennaNo",
        "OperatingMode.AutoReadModes.DataSelector.IDD",
        "OperatingMode.AutoReadModes.DataSelector.UID",
        "OperatingMode.AutoReadModes.DataSelector.Date",
        "OperatingMode.AutoReadModes.DataSelector.Time",
        "OperatingMode.NotificationMode.DataSelector.Antenna",
        "OperatingMode.NotificationMode.DataSelector.AntennaNo",
        "OperatingMode.NotificationMode.DataSelector.IDD",
        "OperatingMode.NotificationMode.DataSelector.UID",
        "OperatingMode.NotificationMode.DataSelector.Date",
        "OperatingMode.NotificationMode.DataSelector.Time",
        // Each generation keeps its notification target in a different subtree.
        "HostInterface.LAN.Remote.Channel1.Address",
        "HostInterface.LAN.Remote.Channel1.PortNumber",
        "HostInterface.LAN.Remote.Channel1.ConnectionHoldTime",
        "OperatingMode.NotificationMode.Transmission.Destination.IPv4.IPAddress",
        "OperatingMode.NotificationMode.Transmission.Destination.PortNumber",
        "OperatingMode.NotificationMode.Transmission.Destination.ConnectionHoldTime",
        // Possible alternative spelling of the OldGen identifier selector.
        "OperatingMode.NotificationMode.DataSelector.EPC",
        "OperatingMode.AutoReadModes.Filter.TransponderValidTime",
        "OperatingMode.NotificationMode.Filter.TransponderValidTime",
        "Transponder.PersistenceReset.Mode",
        "AirInterface.Multiplexer.Enable",
        "AirInterface.Multiplexer.UHF.Internal.SelectedAntennas"
    );

    /** What {@code changeConfigPara} answers when it changed a value rather than matching it. */
    private static final int VALUE_CHANGED = 1;

    private ReaderProbe() {
    }

    public static void main(String[] args) {
        if (args.length < 1 || args[0].startsWith("--")) {
            System.err.println("usage: ReaderProbe <reader-name> [--write [--leave-written]] [--set <param>=<value>]");
            System.exit(2);
        }
        String readerName = args[0];
        boolean write = List.of(args).contains("--write");
        boolean leaveWritten = List.of(args).contains("--leave-written");
        String assignment = valueAfter(args, "--set");

        try {
            ReaderConfig config = findReader(readerName);
            run(config, write, leaveWritten, assignment);
        } catch (UnsatisfiedLinkError e) {
            System.out.println("PROBE FAILED: the FEIG native library could not be loaded.");
            System.out.println("It is linux.x64 only, and needs LD_LIBRARY_PATH pointing at");
            System.out.println("native/linux.x64. Use ./probe.sh, which sets it.");
            System.out.println();
            System.out.println(e);
            System.exit(1);
        } catch (ConfigProblem e) {
            // config.yaml is wrong; the message says how, and a stack trace would only bury it.
            System.out.println("PROBE FAILED: " + e.getMessage());
            System.exit(2);
        } catch (Throwable t) {
            System.out.println("PROBE FAILED: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    /**
     * Fails before the reader is touched if the config lacks the per-antenna values the
     * profiles need. Both profiles are reported whatever the reader's {@code type} is, so
     * a {@code GENERIC} reader needs them too.
     */
    private static void requireAntennaParameters(ReaderConfig config) {
        List<Integer> antennas = config.getAntennas();
        if (antennas.isEmpty()) {
            throw new ConfigProblem("Reader '" + config.getName()
                + "' has no antennas configured; the probe needs at least one to report the"
                + " UHF profiles.");
        }

        requireOnePerAntenna(config, "rssiFilters", config.getRssiFilters().size(), antennas.size());
        requireOnePerAntenna(config, "outputPowers", config.getOutputPowers().size(), antennas.size());
    }

    private static void requireOnePerAntenna(ReaderConfig config, String key, int actual, int expected) {
        if (actual == expected) {
            return;
        }
        throw new ConfigProblem("Reader '" + config.getName() + "' has " + expected
            + " antenna(s) but " + actual + " " + key + "; the probe reports both UHF profiles"
            + " whatever the type is, so config.yaml needs one " + key + " entry per antenna,"
            + " in the same order. See config.example.yaml.");
    }

    /** The argument following {@code flag}, or {@code null} when it is absent. */
    private static String valueAfter(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return null;
    }

    /** A problem in {@code config.yaml} the operator can fix; reported without a stack trace. */
    private static final class ConfigProblem extends RuntimeException {
        ConfigProblem(String message) {
            super(message);
        }
    }

    private static ReaderConfig findReader(String name) throws Exception {
        List<ReaderConfig> readers = ConfigLoader.loadReaders();
        for (ReaderConfig reader : readers) {
            if (reader.getName().equals(name)) {
                return reader;
            }
        }
        List<String> names = readers.stream().map(ReaderConfig::getName).toList();
        throw new IllegalArgumentException("No reader named '" + name + "' in config.yaml; found " + names);
    }

    private static void run(ReaderConfig config, boolean write, boolean leaveWritten, String assignment)
            throws Exception {
        heading("PROBE " + config.getName());
        System.out.println("configured type   : " + config.getType());
        System.out.println("configured mode   : " + config.getMode());
        System.out.println("address           : " + config.getAddress() + ":" + config.getPort());
        System.out.println("antennas          : " + config.getAntennas());
        System.out.println("credentials set   : " + config.hasCredentials());
        System.out.println("write phase       : " + write);
        if (assignment != null) {
            System.out.println("set               : " + assignment);
        }

        requireAntennaParameters(config);

        ReaderModule module = connectAndAuthenticate(config);
        try {
            reportIdentity(module);

            int state = module.config().readCompleteConfiguration();
            heading("READ COMPLETE CONFIGURATION");
            System.out.println(state == ErrorCode.Ok
                ? "ok"
                : "FAILED: " + describe(state) + " - everything below is unreliable");

            reportProfile(module, config, ReaderProfiles.NEW_GEN);
            reportProfile(module, config, ReaderProfiles.OLD_GEN);
            reportExtraCandidates(module);

            if (assignment != null) {
                setParameter(module, assignment);
            } else if (write) {
                writeProbe(module, config, leaveWritten);
            } else {
                heading("WRITE PHASE");
                System.out.println("skipped; rerun with --write once the read report looks sane");
            }
        } finally {
            if (module.isConnected()) {
                module.disconnect();
            }
            module.close();
        }
        heading("END OF PROBE");
    }

    /**
     * Connects, then reports every login API the SDK offers. Every strategy is reported
     * even after one succeeds, so the report says which of them a reader accepts.
     *
     * @return the first connection whose configuration is readable, else the last one
     */
    private static ReaderModule connectAndAuthenticate(ReaderConfig config) throws Exception {
        heading("CONNECT");

        if (!config.hasCredentials()) {
            System.out.println("no credentials configured; connecting unauthenticated");
            ReaderModule module = connect(config, false);
            System.out.println("connect: ok");
            System.out.println("config readable: " + configReadable(module).text());
            return module;
        }

        System.out.println("-- strategy A: Connector.setAuthentication before connect --");
        ReaderModule authenticated = null;
        try {
            authenticated = connect(config, true);
            System.out.println("connect: ok");
            Readable readable = configReadable(authenticated);
            System.out.println("config readable: " + readable.text());
            if (readable.ok()) {
                System.out.println("=> strategy A is sufficient");
                return authenticated;
            }
        } catch (Exception e) {
            System.out.println("connect FAILED: " + e.getMessage());
        }
        close(authenticated);

        System.out.println();
        System.out.println("-- plain connect, no authentication --");
        ReaderModule module = connect(config, false);
        System.out.println("connect: ok");
        Readable plain = configReadable(module);
        System.out.println("config readable: " + plain.text());
        if (plain.ok()) {
            System.out.println("=> reads need no login; whether writes do is the write phase's job");
        }

        System.out.println();
        System.out.println("-- strategy B: config().readerLogin(password) --");
        System.out.println("readerLogin(password): " + describe(module.config().readerLogin(config.getPassword())));
        System.out.println("config readable: " + configReadable(module).text());

        System.out.println();
        System.out.println("-- strategy C: userMng().readerLogin(username, password) --");
        int login = module.userMng().readerLogin(config.getUsername(), config.getPassword());
        System.out.println("readerLogin(user, password): " + describe(login));
        Readable afterC = configReadable(module);
        System.out.println("config readable: " + afterC.text());

        if (!plain.ok() && !afterC.ok()) {
            System.out.println();
            System.out.println("=> no strategy made configuration readable; the report below will be empty");
        }
        return module;
    }

    private static void close(ReaderModule module) {
        if (module == null) {
            return;
        }
        if (module.isConnected()) {
            module.disconnect();
        }
        module.close();
    }

    private static ReaderModule connect(ReaderConfig config, boolean authenticateOnConnector) throws Exception {
        ReaderModule module = new ReaderModule(RequestMode.UniDirectional);
        Connector connector = Connector.createTcpConnector(config.getAddress(), config.getPort());
        connector.setTcpConnectTimeout(5000);
        if (authenticateOnConnector) {
            connector.setAuthentication(config.getUsername(), config.getPassword());
        }
        int state = module.connect(connector);
        if (state != ErrorCode.Ok) {
            String error = module.lastErrorStatusText();
            module.close();
            throw new Exception(describe(state) + " - " + error);
        }
        return module;
    }

    /** Whether configuration could be read, and what happened. */
    private record Readable(boolean ok, String text) {
    }

    /** Whether configuration can be read at all, which is what the login is for. */
    private static Readable configReadable(ReaderModule module) {
        int state = module.config().readCompleteConfiguration();
        if (state != ErrorCode.Ok) {
            return new Readable(false, "NO (readCompleteConfiguration: " + describe(state) + ")");
        }
        ByteRef ref = new ByteRef();
        state = module.config().getConfigPara("OperatingMode.Mode", ref);
        return state == ErrorCode.Ok
            ? new Readable(true, "yes (OperatingMode.Mode = " + hex(ref.getValue() & 0xFF) + ")")
            : new Readable(false, "NO (getConfigPara OperatingMode.Mode: " + describe(state) + ")");
    }

    private static void reportIdentity(ReaderModule module) {
        heading("READER IDENTITY");
        int state = module.readReaderInfo();
        if (state != ErrorCode.Ok) {
            System.out.println("readReaderInfo FAILED: " + describe(state));
            return;
        }
        System.out.println("reader type : " + module.info().readerTypeToString());
        System.out.println("device id   : " + module.info().deviceIdToHexString());
    }

    /**
     * For every parameter a profile would write: does the reader have it, what shape does
     * the reader declare, what does it currently hold, and does that match the profile.
     */
    private static void reportProfile(ReaderModule module, ReaderConfig config, ReaderProfile profile) {
        List<ParamSpec> specs;
        try {
            specs = profile.parametersFor(config, ConfigLoader.getHostName());
        } catch (IllegalArgumentException e) {
            // A config valid for one generation may not be expressible in the other.
            heading("PROFILE " + profile.id() + " - not applicable to this configuration");
            System.out.println(e.getMessage());
            System.out.println("The other profile's report is unaffected.");
            return;
        }

        heading("PROFILE " + profile.id() + " - " + specs.size() + " parameters");
        System.out.printf("%-62s %-8s %-22s %-16s %-16s %s%n",
            "PARAMETER", "PRESENT", "READER SHAPE", "CURRENT", "DESIRED", "MATCH");

        for (ParamSpec spec : specs) {
            ConfigParamInfo info = new ConfigParamInfo();
            boolean present = module.config().hasConfigPara(spec.name(), info);

            String current = "-";
            String match = "-";
            if (present) {
                ReadResult read = read(module, spec.name(), spec.type());
                current = read.text();
                if (read.value() != null) {
                    match = read.value().equals(spec.desired()) ? "yes" : "DRIFT";
                }
            }

            System.out.printf("%-62s %-8s %-22s %-16s %-16s %s%n",
                spec.name(),
                present ? "yes" : "NO",
                present ? shape(info) : "-",
                current,
                spec.desired().describe(),
                match);
        }
    }

    private static void reportExtraCandidates(ReaderModule module) {
        heading("OTHER CANDIDATE PARAMETERS");
        System.out.printf("%-62s %-8s %s%n", "PARAMETER", "PRESENT", "READER SHAPE");

        for (String name : EXTRA_CANDIDATES) {
            ConfigParamInfo info = new ConfigParamInfo();
            boolean present = module.config().hasConfigPara(name, info);
            System.out.printf("%-62s %-8s %s%n", name, present ? "yes" : "NO", present ? shape(info) : "-");
        }
    }

    /**
     * Steps one RSSI filter, checks the reader reports the new value, then puts the
     * original back unless {@code leaveWritten}. Exercises the write path end to end
     * without touching anything protected.
     */
    private static void writeProbe(ReaderModule module, ReaderConfig config, boolean leaveWritten) {
        heading("WRITE PHASE (permanent)");

        if (config.getAntennas().isEmpty()) {
            System.out.println("no antennas configured; nothing safe to write");
            return;
        }
        int antenna = config.getAntennas().get(0);
        String parameter = "AirInterface.Antenna.UHF.No" + antenna + ".RSSIFilter";

        if (ProtectedParameters.isProtected(parameter)) {
            System.out.println("refusing: " + parameter + " is protected");
            return;
        }
        if (!module.config().hasConfigPara(parameter, new ConfigParamInfo())) {
            System.out.println(parameter + " is not supported by this reader; nothing written");
            return;
        }

        ReadResult before = read(module, parameter, ParamType.BYTE);
        if (before.value() == null) {
            System.out.println("could not read " + parameter + ": " + before.text());
            return;
        }
        int original = (int) ((ParamValue.Numeric) before.value()).value();
        int probe = original == 0 ? 1 : original - 1;
        System.out.println("parameter : " + parameter);
        System.out.println("original  : " + original);
        System.out.println("probe     : " + probe);

        System.out.println();
        System.out.println("write probe value  : " + describe(module.config().changeConfigPara(parameter, (byte) probe)));
        System.out.println("apply(false)       : " + describe(module.config().applyConfiguration(false)));
        System.out.println("re-read            : " + describe(module.config().readCompleteConfiguration()));
        System.out.println("value now          : " + read(module, parameter, ParamType.BYTE).text()
            + "   (expected " + probe + ")");

        if (leaveWritten) {
            System.out.println();
            System.out.println("--leave-written given; NOT restoring " + original + ".");
            System.out.println("The reader is left holding " + probe + " on purpose, to find out whether a");
            System.out.println("write applied non-persistently survives a power cycle. Power cycle the");
            System.out.println("reader now, then run the probe again without --write:");
            System.out.println("  " + probe + " still there -> the write persisted despite apply(false)");
            System.out.println("  " + original + " back      -> it really was non-persistent");
            System.out.println("A later --write run will NOT undo this: it steps back from whatever it");
            System.out.println("reads at the start. If " + probe + " survives, set " + original + " deliberately.");
            System.out.println();
            System.out.println("It survived on both readers tested, whether apply(false) reported ok or");
            System.out.println("failed. Assume this reader is now permanently at " + probe + ".");
            return;
        }

        System.out.println();
        System.out.println("restore original   : " + describe(module.config().changeConfigPara(parameter, (byte) original)));
        System.out.println("apply(false)       : " + describe(module.config().applyConfiguration(false)));
        System.out.println("re-read            : " + describe(module.config().readCompleteConfiguration()));
        System.out.println("value now          : " + read(module, parameter, ParamType.BYTE).text()
            + "   (expected " + original + ")");
    }

    /** Writes one numeric parameter to an exact value and reports the round trip. */
    private static void setParameter(ReaderModule module, String assignment) {
        heading("SET ONE PARAMETER (permanent)");

        int equals = assignment.indexOf('=');
        if (equals < 1 || equals == assignment.length() - 1) {
            System.out.println("expected --set <parameter>=<value>, got: " + assignment);
            return;
        }
        String parameter = assignment.substring(0, equals).trim();
        String rawValue = assignment.substring(equals + 1).trim();

        if (ProtectedParameters.isProtected(parameter)) {
            System.out.println("refusing: " + ProtectedParameters.refusalMessage(parameter));
            return;
        }

        ConfigParamInfo info = new ConfigParamInfo();
        if (!module.config().hasConfigPara(parameter, info)) {
            System.out.println(parameter + " is not supported by this reader; nothing written");
            return;
        }

        ParamType type = numericTypeOf(info);
        if (type == null) {
            System.out.println(parameter + " is " + shape(info)
                + ", which this only handles for numeric parameters; nothing written");
            return;
        }

        long value;
        try {
            value = Long.decode(rawValue);
        } catch (NumberFormatException e) {
            System.out.println("not a number: " + rawValue + " (decimal, or 0x for hex); nothing written");
            return;
        }

        System.out.println("parameter : " + parameter);
        System.out.println("shape     : " + shape(info) + ", writing as " + type);
        System.out.println("before    : " + read(module, parameter, type).text());
        System.out.println("setting to: " + value);
        System.out.println();

        int written = switch (type) {
            case BOOL -> module.config().changeConfigPara(parameter, value != 0);
            case BYTE -> module.config().changeConfigPara(parameter, (byte) value);
            default -> module.config().changeConfigPara(parameter, value);
        };
        System.out.println("write              : " + describe(written));
        System.out.println("apply(true)        : " + describe(module.config().applyConfiguration(true)));
        System.out.println("re-read            : " + describe(module.config().readCompleteConfiguration()));
        System.out.println("value now          : " + read(module, parameter, type).text()
            + "   (expected " + value + ")");
    }

    /** The type to read and write a parameter as, or {@code null} if it is not numeric. */
    private static ParamType numericTypeOf(ConfigParamInfo info) {
        if (!info.isValid()) {
            return null;
        }
        if (info.isBool()) {
            return ParamType.BOOL;
        }
        if (info.isMultiBit() || info.isByte()) {
            return ParamType.BYTE;
        }
        return info.isMultiByte() ? ParamType.LONG : null;
    }

    /** A parameter read, holding either the value or the reason it could not be read. */
    private record ReadResult(ParamValue value, String text) {
    }

    private static ReadResult read(ReaderModule module, String parameter, ParamType type) {
        switch (type) {
            case BOOL -> {
                BoolRef ref = new BoolRef();
                int state = module.config().getConfigPara(parameter, ref);
                return state == ErrorCode.Ok
                    ? new ReadResult(ParamValue.bool(ref.getValue()), Boolean.toString(ref.getValue()))
                    : failed(state);
            }
            case BYTE -> {
                ByteRef ref = new ByteRef();
                int state = module.config().getConfigPara(parameter, ref);
                int unsigned = ref.getValue() & 0xFF;
                return state == ErrorCode.Ok
                    ? new ReadResult(ParamValue.ofByte(unsigned), hex(unsigned))
                    : failed(state);
            }
            case LONG -> {
                LongRef ref = new LongRef();
                int state = module.config().getConfigPara(parameter, ref);
                return state == ErrorCode.Ok
                    ? new ReadResult(ParamValue.ofLong(ref.getValue()), Long.toString(ref.getValue()))
                    : failed(state);
            }
            default -> {
                StringBuilder ref = new StringBuilder();
                int state = module.config().getConfigPara(parameter, ref);
                return state == ErrorCode.Ok
                    ? new ReadResult(ParamValue.text(ref.toString()), ref.toString())
                    : failed(state);
            }
        }
    }

    private static ReadResult failed(int state) {
        return new ReadResult(null, "ERR " + state);
    }

    /** The shape the reader itself declares for a parameter, to check against our type. */
    private static String shape(ConfigParamInfo info) {
        if (!info.isValid()) {
            return "invalid";
        }
        if (info.isBool()) {
            return "bool";
        }
        if (info.isMultiBit()) {
            return "multibit(" + info.bitLength() + ")";
        }
        if (info.isByte()) {
            return "byte";
        }
        if (info.isMultiByte()) {
            return "multibyte(" + info.byteLength() + ")";
        }
        return "unknown";
    }

    private static String describe(int state) {
        // changeConfigPara answers 0 when the value already matched and 1 when it
        // changed it. Both are successes.
        if (state == ErrorCode.Ok) {
            return "ok";
        }
        if (state == VALUE_CHANGED) {
            return "ok (value changed)";
        }
        // Negative is an SDK error code, anything else a status from the reader itself.
        return state < ErrorCode.Ok
            ? ErrorCode.toString(state) + " (error code " + state + ")"
            : ReaderStatus.toString(state) + " (reader status " + state + ")";
    }

    private static String hex(int value) {
        return String.format("0x%02X", value);
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println("=== " + title + " " + "=".repeat(Math.max(0, 72 - title.length())));
    }
}
