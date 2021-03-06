/*
 * Copyright 2016-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.provider.lldpcommon;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.internal.StringUtil;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.ONOSLLDP;
import org.onlab.util.Timer;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link.Type;
import org.onosproject.net.LinkKey;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.link.DefaultLinkDescription;
import org.onosproject.net.link.LinkDescription;
import org.onosproject.net.link.ProbedLinkProvider;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.Map;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.onosproject.net.AnnotationKeys.PORT_NAME;
import static org.onosproject.net.PortNumber.portNumber;
import static org.onosproject.net.flow.DefaultTrafficTreatment.builder;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Run discovery process from a physical switch. Ports are initially labeled as
 * slow ports. When an LLDP is successfully received, label the remote port as
 * fast. Every probeRate milliseconds, loop over all fast ports and send an
 * LLDP, send an LLDP for a single slow port. Based on FlowVisor topology
 * discovery implementation.
 */
public class LinkDiscovery implements TimerTask {

    private final Logger log = getLogger(getClass());

    private final Device device;
    private final LinkDiscoveryContext context;

    private final Ethernet ethPacket;
    private final Ethernet bddpEth;

    private Timeout timeout;
    private volatile boolean isStopped;

    // Set of ports to be probed
    private final Map<Long, String> portMap = Maps.newConcurrentMap();
    // Set default management vlan
    private short default_vlan = 4092;
    /**
     * Instantiates discovery manager for the given physical switch. Creates a
     * generic LLDP packet that will be customized for the port it is sent out on.
     * Starts the the timer for the discovery process.
     *
     * @param device  the physical switch
     * @param context discovery context
     */
    public LinkDiscovery(Device device, LinkDiscoveryContext context) {
        this.device = device;
        this.context = context;

        ethPacket = new Ethernet();
	// must tag vlan when using the VPLS path
	ethPacket.setVlanID(Short.valueOf(default_vlan));
        ethPacket.setEtherType(Ethernet.TYPE_LLDP);
        ethPacket.setDestinationMACAddress(MacAddress.ONOS_LLDP);
        ethPacket.setPad(true);

        bddpEth = new Ethernet();
	// must tag vlan when using the VPLS path
	bddpEth.setVlanID(Short.valueOf(default_vlan));
        bddpEth.setEtherType(Ethernet.TYPE_BSN);
        bddpEth.setDestinationMACAddress(MacAddress.BROADCAST);
        bddpEth.setPad(true);

        isStopped = true;
        start();
        log.debug("Started discovery manager for switch {}", device.id());

    }

    public synchronized void stop() {
        if (!isStopped) {
            isStopped = true;
            timeout.cancel();
        } else {
            log.warn("LinkDiscovery stopped multiple times?");
        }
    }

    public synchronized void start() {
        if (isStopped) {
            isStopped = false;
            timeout = Timer.newTimeout(this, 0, MILLISECONDS);
        } else {
            log.warn("LinkDiscovery started multiple times?");
        }
    }

    public synchronized boolean isStopped() {
        return isStopped || timeout.isCancelled();
    }

    /**
     * Add physical port to discovery process.
     * Send out initial LLDP and label it as slow port.
     *
     * @param port the port
     */
    public void addPort(Port port) {
        Long portNum = port.number().toLong();
        String portName = port.annotations().value(PORT_NAME);
        if (portName == null) {
            portName = StringUtil.EMPTY_STRING;
        }

        boolean newPort = !containsPort(portNum);
        portMap.put(portNum, portName);

        boolean isMaster = context.mastershipService().isLocalMaster(device.id());
        if (newPort && isMaster) {
            log.debug("Sending initial probe to port {}@{}", port.number().toLong(), device.id());
            sendProbes(portNum, portName);
        }
    }

    /**
     * removed physical port from discovery process.
     * @param port the port number
     */
    public void removePort(PortNumber port) {
        portMap.remove(port.toLong());
    }

    /**
     * Handles an incoming LLDP packet. Creates link in topology and adds the
     * link for staleness tracking.
     *
     * @param packetContext packet context
     * @return true if handled
     */
    public boolean handleLldp(PacketContext packetContext) {
        Ethernet eth = packetContext.inPacket().parsed();
        if (eth == null) {
            return false;
        }

        ONOSLLDP onoslldp = ONOSLLDP.parseONOSLLDP(eth);
        if (onoslldp != null) {
            Type lt;
            if (notMy(eth.getSourceMAC().toString())) {
                lt = Type.EDGE;
            } else {
                lt = eth.getEtherType() == Ethernet.TYPE_LLDP ?
                        Type.DIRECT : Type.INDIRECT;
            }

            PortNumber srcPort = portNumber(onoslldp.getPort());
            PortNumber dstPort = packetContext.inPacket().receivedFrom().port();

            String idString = onoslldp.getDeviceString();
            if (!isNullOrEmpty(idString)) {
                DeviceId srcDeviceId = DeviceId.deviceId(idString);
                DeviceId dstDeviceId = packetContext.inPacket().receivedFrom().deviceId();

                ConnectPoint src = new ConnectPoint(srcDeviceId, srcPort);
                ConnectPoint dst = new ConnectPoint(dstDeviceId, dstPort);

                LinkDescription ld = new DefaultLinkDescription(src, dst, lt);
                try {
                    context.providerService().linkDetected(ld);
                    context.touchLink(LinkKey.linkKey(src, dst));
                } catch (IllegalStateException e) {
                    return true;
                }
                return true;
            }
        }
        return false;
    }

    // true if *NOT* this cluster's own probe.
    private boolean notMy(String mac) {
        // if we are using DEFAULT_MAC, clustering hadn't initialized, so conservative 'yes'
        String ourMac = context.fingerprint();
        if (ProbedLinkProvider.defaultMac().equalsIgnoreCase(ourMac)) {
            return true;
        }
        return !mac.equalsIgnoreCase(ourMac);
    }

    /**
     * Execute this method every t milliseconds. Loops over all ports
     * labeled as fast and sends out an LLDP. Send out an LLDP on a single slow
     * port.
     *
     * @param t timeout
     */
    @Override
    public void run(Timeout t) {
        if (isStopped()) {
            return;
        }

        if (context.mastershipService().isLocalMaster(device.id())) {
            log.trace("Sending probes from {}", device.id());
            ImmutableMap.copyOf(portMap).forEach(this::sendProbes);
        }

        if (!isStopped()) {
            timeout = t.timer().newTimeout(this, context.probeRate(), MILLISECONDS);
        }
    }

    /**
     * Creates packet_out LLDP for specified output port.
     *
     * @param portNumber the port
     * @param portDesc the port description
     * @return Packet_out message with LLDP data
     */
    private OutboundPacket createOutBoundLldp(Long portNumber, String portDesc) {
        if (portNumber == null) {
            return null;
        }
        ONOSLLDP lldp = getLinkProbe(portNumber, portDesc);
        ethPacket.setSourceMACAddress(context.fingerprint()).setPayload(lldp);
        return new DefaultOutboundPacket(device.id(),
                                         builder().setOutput(portNumber(portNumber)).build(),
                                         ByteBuffer.wrap(ethPacket.serialize()));
    }

    /**
     * Creates packet_out BDDP for specified output port.
     *
     * @param portNumber the port
     * @param portDesc the port description
     * @return Packet_out message with LLDP data
     */
    private OutboundPacket createOutBoundBddp(Long portNumber, String portDesc) {
        if (portNumber == null) {
            return null;
        }
        ONOSLLDP lldp = getLinkProbe(portNumber, portDesc);
        bddpEth.setSourceMACAddress(context.fingerprint()).setPayload(lldp);
        return new DefaultOutboundPacket(device.id(),
                                         builder().setOutput(portNumber(portNumber)).build(),
                                         ByteBuffer.wrap(bddpEth.serialize()));
    }

    private ONOSLLDP getLinkProbe(Long portNumber, String portDesc) {
        return ONOSLLDP.onosLLDP(device.id().toString(), device.chassisId(), portNumber.intValue(), portDesc);
    }

    private void sendProbes(Long portNumber, String portDesc) {
        if (context.packetService() == null) {
            return;
        }
        log.trace("Sending probes out of {}@{}", portNumber, device.id());
        OutboundPacket pkt = createOutBoundLldp(portNumber, portDesc);
        context.packetService().emit(pkt);
        if (context.useBddp()) {
            OutboundPacket bpkt = createOutBoundBddp(portNumber, portDesc);
            context.packetService().emit(bpkt);
        }
    }

    public boolean containsPort(long portNumber) {
        return portMap.containsKey(portNumber);
    }
}
