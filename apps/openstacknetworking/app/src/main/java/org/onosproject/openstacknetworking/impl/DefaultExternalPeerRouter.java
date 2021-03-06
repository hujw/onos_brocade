/*
 * Copyright 2018-present Open Networking Foundation
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
package org.onosproject.openstacknetworking.impl;

import com.google.common.base.MoreObjects;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.openstacknetworking.api.ExternalPeerRouter;

import java.util.Objects;

/**
 * Implementation of external peer router.
 */
public final class DefaultExternalPeerRouter implements ExternalPeerRouter {

    private final IpAddress externalPeerRouterIp;
    private final MacAddress externalPeerRouterMac;
    private final VlanId externalPeerRouterVlanId;

    public DefaultExternalPeerRouter(IpAddress externalPeerRouterIp,
                                     MacAddress externalPeerRouterMac,
                                     VlanId externalPeerRouterVlanId) {
        this.externalPeerRouterIp = externalPeerRouterIp;
        this.externalPeerRouterMac = externalPeerRouterMac;
        this.externalPeerRouterVlanId = externalPeerRouterVlanId;
    }

    @Override
    public IpAddress externalPeerRouterIp() {
        return this.externalPeerRouterIp;
    }
    @Override
    public MacAddress externalPeerRouterMac() {
        return this.externalPeerRouterMac;
    }
    @Override
    public VlanId externalPeerRouterVlanId() {
        return this.externalPeerRouterVlanId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof DefaultExternalPeerRouter) {
            DefaultExternalPeerRouter that = (DefaultExternalPeerRouter) obj;
            return Objects.equals(externalPeerRouterIp, that.externalPeerRouterIp) &&
                    Objects.equals(externalPeerRouterMac, that.externalPeerRouterMac) &&
                    Objects.equals(externalPeerRouterVlanId, that.externalPeerRouterVlanId);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(externalPeerRouterIp,
                externalPeerRouterMac,
                externalPeerRouterVlanId);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(getClass())
                .add("externalPeerRouterIp", externalPeerRouterIp)
                .add("externalPeerRouterMac", externalPeerRouterMac)
                .add("externalPeerRouterVlanId", externalPeerRouterVlanId)
                .toString();
    }
}
