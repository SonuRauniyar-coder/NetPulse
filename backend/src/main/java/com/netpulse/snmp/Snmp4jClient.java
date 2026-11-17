package com.netpulse.snmp;

import com.netpulse.common.config.NetPulseProperties;
import org.snmp4j.CommunityTarget;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.*;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls the standard MIB-II objects over UDP/161.
 *
 * <p>v2c "authenticates" with a community string sent in clear text, and an agent silently ignores a wrong one, so
 * bad credentials look exactly like a timeout. v3 authPriv signs (HMAC) and encrypts (AES) each message.
 *
 * <p>Tables are read with GETBULK, which returns many rows per request instead of the one per GETNEXT round trip.
 *
 * <p>Each poll gets its own Snmp session and USM. SNMP4J's default USM is a process-wide singleton, and v3 engine
 * IDs and time windows must not leak between devices.
 */
@Component
public class Snmp4jClient implements SnmpClient {
    private static final OID SYS_DESCR = new OID("1.3.6.1.2.1.1.1.0");
    private static final OID SYS_UPTIME = new OID("1.3.6.1.2.1.1.3.0");
    private static final OID SYS_NAME = new OID("1.3.6.1.2.1.1.5.0");
    private static final OID SYS_LOCATION = new OID("1.3.6.1.2.1.1.6.0");

    private static final String IF = "1.3.6.1.2.1.2.2.1.";       // ifTable
    private static final String IFX = "1.3.6.1.2.1.31.1.1.1.";   // ifXTable: 64-bit counters and high-speed gauge
    private static final OID[] IF_COLUMNS = {
            new OID(IF + "2"), new OID(IF + "5"), new OID(IF + "7"), new OID(IF + "8"),   // descr, speed, admin, oper
            new OID(IF + "10"), new OID(IF + "14"), new OID(IF + "16"), new OID(IF + "20")};  // inOctets, inErrors, outOctets, outErrors
    private static final OID[] IFX_COLUMNS = {new OID(IFX + "6"), new OID(IFX + "10"), new OID(IFX + "15")};  // HCIn, HCOut, highSpeed

    private final NetPulseProperties.Snmp cfg;

    public Snmp4jClient(NetPulseProperties props) {
        this.cfg = props.snmp();
    }

    @Override
    public SnmpSnapshot poll(InetAddress address, int port, SnmpVersion version, SnmpCredentials c) {
        try (DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping()) {
            MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
            dispatcher.addMessageProcessingModel(new MPv2c());
            USM usm = null;
            if (version == SnmpVersion.V3) {
                usm = new USM(securityProtocols(), new OctetString(MPv3.createLocalEngineID()), 0);
                dispatcher.addMessageProcessingModel(new MPv3(usm));
            }
            try (Snmp snmp = new Snmp(dispatcher, transport)) {
                if (usm != null) usm.addUser(new OctetString(c.username()), usmUser(c));
                snmp.listen();
                Target<UdpAddress> target = target(new UdpAddress(address, port), version, c);
                return read(snmp, target, version);
            }
        } catch (IOException e) {
            throw new SnmpPollException("SNMP transport error: " + e.getMessage());
        }
    }

    /** SNMP4J's default set leaves out SHA-1, which most deployed agents still use, so the supported ones are named here. */
    private static SecurityProtocols securityProtocols() {
        SecurityProtocols p = SecurityProtocols.getInstance();
        p.addAuthenticationProtocol(new AuthSHA());
        p.addAuthenticationProtocol(new AuthHMAC192SHA256());
        p.addPrivacyProtocol(new PrivAES128());
        return p;
    }

    private static UsmUser usmUser(SnmpCredentials c) {
        OID auth = "SHA256".equals(c.authProtocol()) ? AuthHMAC192SHA256.ID : AuthSHA.ID;
        return new UsmUser(new OctetString(c.username()), auth, new OctetString(c.authPassword()), PrivAES128.ID, new OctetString(c.privPassword()));
    }

    private Target<UdpAddress> target(UdpAddress address, SnmpVersion version, SnmpCredentials c) {
        Target<UdpAddress> t;
        if (version == SnmpVersion.V3) {
            UserTarget<UdpAddress> u = new UserTarget<>();
            u.setSecurityLevel(SecurityLevel.AUTH_PRIV);
            u.setSecurityName(new OctetString(c.username()));
            u.setVersion(SnmpConstants.version3);
            t = u;
        } else {
            CommunityTarget<UdpAddress> v2 = new CommunityTarget<>();
            v2.setCommunity(new OctetString(c.community()));
            v2.setVersion(SnmpConstants.version2c);
            t = v2;
        }
        t.setAddress(address);
        t.setTimeout(cfg.timeout().toMillis());
        t.setRetries(cfg.retries());
        return t;
    }

    private SnmpSnapshot read(Snmp snmp, Target<UdpAddress> target, SnmpVersion version) throws IOException {
        PDU get = version == SnmpVersion.V3 ? new ScopedPDU() : new PDU();
        get.setType(PDU.GET);
        for (OID oid : new OID[]{SYS_NAME, SYS_DESCR, SYS_LOCATION, SYS_UPTIME}) get.add(new VariableBinding(oid));
        ResponseEvent<UdpAddress> event = snmp.send(get, target);
        PDU response = event.getResponse();
        if (response == null) {
            throw new SnmpPollException(version == SnmpVersion.V3
                    ? "No response on UDP " + target.getAddress().getPort() + " (agent unreachable, or SNMPv3 user rejected)"
                    : "No response on UDP " + target.getAddress().getPort() + " (agent unreachable, blocked, or wrong community)");
        }
        if (response.getType() == PDU.REPORT) throw new SnmpPollException("SNMPv3 authentication failed (" + response.get(0).getOid() + ")");
        if (response.getErrorStatus() != 0) throw new SnmpPollException("Agent returned an error: " + response.getErrorStatusText());
        Variable uptime = response.getVariable(SYS_UPTIME);
        if (uptime == null || uptime.isException()) throw new SnmpPollException("Agent did not return sysUpTime");

        return new SnmpSnapshot(text(response.getVariable(SYS_NAME)), text(response.getVariable(SYS_DESCR)), text(response.getVariable(SYS_LOCATION)),
                uptime.toLong(), readInterfaces(snmp, target));
    }

    private List<SnmpSnapshot.Interface> readInterfaces(Snmp snmp, Target<UdpAddress> target) {
        TableUtils tables = new TableUtils(snmp, new DefaultPDUFactory(PDU.GETBULK));
        tables.setMaxNumRowsPerPDU(20);
        Map<Integer, Variable[]> base = table(tables, target, IF_COLUMNS, true);
        Map<Integer, Variable[]> extended = table(tables, target, IFX_COLUMNS, false);   // absent on some small or old devices

        List<SnmpSnapshot.Interface> out = new ArrayList<>();
        base.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(cfg.maxInterfaces()).forEach(e -> {
            Variable[] b = e.getValue();
            Variable[] x = extended.getOrDefault(e.getKey(), new Variable[IFX_COLUMNS.length]);
            boolean hc = present(x[0]) && present(x[1]);
            // ifSpeed is a 32-bit gauge that saturates at about 4.29 Gbit/s; ifHighSpeed is in Mbit/s.
            long speed = present(x[2]) && x[2].toLong() > 0 ? x[2].toLong() * 1_000_000 : present(b[1]) ? b[1].toLong() : 0;
            out.add(new SnmpSnapshot.Interface(e.getKey(), text(b[0]), intOf(b[2]), intOf(b[3]), speed,
                    hc ? counter(x[0]) : counter(b[4]), hc ? counter(x[1]) : counter(b[6]), hc ? 64 : 32, counter(b[5]), counter(b[7])));
        });
        out.sort(Comparator.comparingInt(SnmpSnapshot.Interface::index));
        return out;
    }

    private static Map<Integer, Variable[]> table(TableUtils tables, Target<UdpAddress> target, OID[] columns, boolean required) {
        Map<Integer, Variable[]> rows = new HashMap<>();
        List<TableEvent> events = tables.getTable(target, columns, null, null);
        for (TableEvent ev : events) {
            if (ev.isError()) {
                if (required) throw new SnmpPollException("Could not read the interface table: " + ev.getErrorMessage());
                return Map.of();
            }
            VariableBinding[] vbs = ev.getColumns();
            if (vbs == null || ev.getIndex() == null) continue;
            Variable[] row = new Variable[columns.length];
            for (int i = 0; i < Math.min(vbs.length, row.length); i++) {
                if (vbs[i] != null) row[i] = vbs[i].getVariable();
            }
            rows.put(ev.getIndex().last(), row);
        }
        return rows;
    }

    private static boolean present(Variable v) {
        return v != null && !v.isException();
    }

    private static Integer intOf(Variable v) {
        return present(v) ? v.toInt() : null;
    }

    private static BigInteger counter(Variable v) {
        if (!present(v)) return null;
        return v instanceof Counter64 ? new BigInteger(Long.toUnsignedString(v.toLong())) : BigInteger.valueOf(v.toLong());
    }

    private static String text(Variable v) {
        if (!present(v)) return null;
        String s = v instanceof OctetString o && o.isPrintable() ? o.toString() : v.toString();
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
