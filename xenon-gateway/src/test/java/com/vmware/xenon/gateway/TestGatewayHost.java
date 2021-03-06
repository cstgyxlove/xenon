/*
 * Copyright (c) 2014-2016 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.xenon.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.store.LockObtainFailedException;
import org.junit.rules.TemporaryFolder;

import com.vmware.xenon.common.Utils;
import com.vmware.xenon.common.test.TestContext;
import com.vmware.xenon.common.test.TestNodeGroupManager;
import com.vmware.xenon.gateway.hosts.GatewayConfigHost;
import com.vmware.xenon.gateway.hosts.GatewayDispatchHost;
import com.vmware.xenon.gateway.hosts.GatewayHost;

public class TestGatewayHost extends GatewayHost {

    private int timeoutSeconds = 30;

    private TemporaryFolder folder;
    private GatewayHost.Arguments arguments;
    private List<TestGatewayHost> peerGateways;
    private TestNodeGroupManager ngManager;

    public void startSynchronously(GatewayHost.Arguments args) throws Throwable {
        // Create a temp folder that will be used as the sandbox
        // directory for the app.
        if (this.folder == null) {
            this.folder = new TemporaryFolder();
            this.folder.create();
        }

        // Update the arguments with the sandbox temp directory.
        if (this.arguments == null) {
            args.sandbox = this.folder.getRoot().toPath();
            this.arguments = args;
        }

        final TestContext ctx = TestContext.create(1,
                TimeUnit.SECONDS.toMicros(this.timeoutSeconds));
        start(args, (t) -> {
            if (t != null) {
                ctx.failIteration(t);
                return;
            }
            ctx.completeIteration();
        });
        ctx.await();

        if (this.ngManager == null) {
            this.ngManager = new TestNodeGroupManager();
            this.ngManager.addHost(this.configHost);
        }

        if (this.peerGateways == null) {
            this.peerGateways = new ArrayList<>();
        }
    }

    @Override
    public void stop() {
        // Stop the hosts by calling stop on the
        // base class.
        super.stop();

        // Delete the temporary folder we created
        if (this.folder != null) {
            this.folder.delete();
            this.folder = null;
        }

        this.ngManager = null;
        this.arguments = null;

        // Call stop on all peer gateways.
        if (this.peerGateways != null) {
            this.peerGateways.forEach(g -> g.stop());
            this.peerGateways = null;
        }
    }

    public void restart() throws Throwable {
        this.configHost.log(Level.INFO, "Restarting gateway ...");

        long exp = Utils.fromNowMicrosUtc(this.configHost.getOperationTimeoutMicros());

        // Skip deleting the temporary folder by calling
        // stop on the base class. This is because
        // when the host restarts, we want it to re-hydrate
        // its configuration from the existing index.
        super.stop();

        do {
            Thread.sleep(2000);
            try {
                startSynchronously(this.arguments);
                break;
            } catch (Throwable e) {
                Logger.getAnonymousLogger().warning(String
                        .format("exception on gateway restart: %s", e.getMessage()));
                try {
                    super.stop();
                } catch (Throwable e1) {
                }
                if (e instanceof LockObtainFailedException) {
                    Logger.getAnonymousLogger()
                            .warning("Lock held exception on gateway restart, retrying ...");
                    continue;
                }
                throw e;
            }
        } while (Utils.getSystemNowMicrosUtc() < exp);
        this.configHost.log(Level.INFO, "Gateway restarted successfully ...");
    }

    public GatewayDispatchHost getDispatchHost() {
        return this.dispatchHost;
    }

    public GatewayConfigHost getConfigHost() {
        return this.configHost;
    }

    public List<TestGatewayHost> getPeerGateways() {
        return this.peerGateways;
    }

    public void addPeerGateway(TestGatewayHost peer) {
        this.peerGateways.add(peer);

        this.ngManager.addHost(peer.configHost);
        this.ngManager.joinNodeGroupAndWaitForConvergence();
    }
}
