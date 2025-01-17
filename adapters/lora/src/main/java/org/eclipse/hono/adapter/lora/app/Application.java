/**
 * Copyright (c) 2021, 2022 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hono.adapter.lora.app;

import java.util.ArrayList;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.eclipse.hono.adapter.AbstractProtocolAdapterApplication;
import org.eclipse.hono.adapter.http.HttpAdapterMetrics;
import org.eclipse.hono.adapter.http.HttpProtocolAdapterProperties;
import org.eclipse.hono.adapter.lora.LoraProtocolAdapter;
import org.eclipse.hono.adapter.lora.providers.LoraProvider;

import io.vertx.ext.web.client.WebClient;

/**
 * The Hono Lora adapter main application class.
 */
@ApplicationScoped
public class Application extends AbstractProtocolAdapterApplication<HttpProtocolAdapterProperties> {

    private static final String CONTAINER_ID = "Hono Lora Adapter";

    @Inject
    HttpAdapterMetrics metrics;

    @Inject
    Instance<LoraProvider> loraProviders;

    /**
     * {@inheritDoc}
     */
    @Override
    public String getComponentName() {
        return CONTAINER_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected LoraProtocolAdapter adapter() {

        final List<LoraProvider> providers = new ArrayList<>();
        loraProviders.forEach(handler -> providers.add(handler));

        final LoraProtocolAdapter adapter = new LoraProtocolAdapter(WebClient.create(vertx));
        adapter.setConfig(protocolAdapterProperties);
        adapter.setLoraProviders(providers);
        adapter.setMetrics(metrics);
        setCollaborators(adapter);
        return adapter;
    }
}
