/**
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 1.0 which is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package org.eclipse.hono.adapter.mqtt;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.MessageSender;
import org.eclipse.hono.config.ProtocolAdapterProperties;
import org.eclipse.hono.service.AbstractProtocolAdapterBase;
import org.eclipse.hono.service.auth.device.Device;
import org.eclipse.hono.service.auth.device.DeviceCredentials;
import org.eclipse.hono.service.auth.device.UsernamePasswordCredentials;
import org.eclipse.hono.service.registration.RegistrationAssertionHelperImpl;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.ResourceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.mqtt.MqttAuth;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import io.vertx.mqtt.messages.MqttPublishMessage;
import io.vertx.proton.ProtonHelper;

/**
 * A base class for implementing Vert.x based Hono protocol adapters
 * for publishing events &amp; telemetry data using MQTT.
 * 
 * @param <T> The type of configuration properties this adapter supports/requires.
 */
public abstract class AbstractVertxBasedMqttProtocolAdapter<T extends ProtocolAdapterProperties> extends AbstractProtocolAdapterBase<T> {

    private static final int IANA_MQTT_PORT = 1883;
    private static final int IANA_SECURE_MQTT_PORT = 8883;

    /**
     * A logger to be used by concrete subclasses.
     */
    protected final Logger LOG = LoggerFactory.getLogger(getClass());

    private MqttServer server;
    private MqttServer insecureServer;
    private Map<MqttEndpoint, String> registrationAssertions = new HashMap<>();

    @Override
    public int getPortDefaultValue() {
        return IANA_SECURE_MQTT_PORT;
    }

    @Override
    public int getInsecurePortDefaultValue() {
        return IANA_MQTT_PORT;
    }

    @Override
    protected final int getActualPort() {
        return (server != null ? server.actualPort() : Constants.PORT_UNCONFIGURED);
    }

    @Override
    protected final int getActualInsecurePort() {
        return (insecureServer != null ? insecureServer.actualPort() : Constants.PORT_UNCONFIGURED);
    }

    /**
     * Sets the MQTT server to use for handling secure MQTT connections.
     * 
     * @param server The server.
     * @throws NullPointerException if the server is {@code null}.
     */
    public void setMqttSecureServer(final MqttServer server) {
        Objects.requireNonNull(server);
        if (server.actualPort() > 0) {
            throw new IllegalArgumentException("MQTT server must not be started already");
        } else {
            this.server = server;
        }
    }

    /**
     * Sets the MQTT server to use for handling non-secure MQTT connections.
     * 
     * @param server The server.
     * @throws NullPointerException if the server is {@code null}.
     */
    public void setMqttInsecureServer(final MqttServer server) {
        Objects.requireNonNull(server);
        if (server.actualPort() > 0) {
            throw new IllegalArgumentException("MQTT server must not be started already");
        } else {
            this.insecureServer = server;
        }
    }

    private Future<Void> bindSecureMqttServer() {

        if (isSecurePortEnabled()) {
            MqttServerOptions options = new MqttServerOptions();
            options
                .setHost(getConfig().getBindAddress())
                .setPort(determineSecurePort())
                .setMaxMessageSize(getConfig().getMaxPayloadSize());
            addTlsKeyCertOptions(options);
            addTlsTrustOptions(options);

            return bindMqttServer(options, server).map(s -> {
                server = s;
                return (Void) null;
            }).recover(t -> {
                return Future.failedFuture(t);
            });
        } else {
            return Future.succeededFuture();
        }
    }

    private Future<Void> bindInsecureMqttServer() {

        if (isInsecurePortEnabled()) {
            MqttServerOptions options = new MqttServerOptions();
            options
                .setHost(getConfig().getInsecurePortBindAddress())
                .setPort(determineInsecurePort())
                .setMaxMessageSize(getConfig().getMaxPayloadSize());

            return bindMqttServer(options, insecureServer).map(server -> {
                insecureServer = server;
                return (Void) null;
            }).recover(t -> {
                return Future.failedFuture(t);
            });
        } else {
            return Future.succeededFuture();
        }
    }

    private Future<MqttServer> bindMqttServer(final MqttServerOptions options, final MqttServer mqttServer) {

        final Future<MqttServer> result = Future.future();
        final MqttServer createdMqttServer =
                (mqttServer == null ? MqttServer.create(this.vertx, options) : mqttServer);

        createdMqttServer
            .endpointHandler(this::handleEndpointConnection)
            .listen(done -> {

                if (done.succeeded()) {
                    LOG.info("MQTT server running on {}:{}", getConfig().getBindAddress(), createdMqttServer.actualPort());
                    result.complete(createdMqttServer);
                } else {
                    LOG.error("error while starting up MQTT server", done.cause());
                    result.fail(done.cause());
                }
            });
        return result;
    }

    @Override
    public void doStart(final Future<Void> startFuture) {

        LOG.info("limiting size of inbound message payload to {} bytes", getConfig().getMaxPayloadSize());
        if (!getConfig().isAuthenticationRequired()) {
            LOG.warn("authentication of devices turned off");
        }
        checkPortConfiguration()
        .compose(v -> bindSecureMqttServer())
        .compose(s -> bindInsecureMqttServer())
        .compose(t -> {
            connectToMessaging(null);
            connectToDeviceRegistration(null);
            startFuture.complete();
        }, startFuture);
    }

    @Override
    public void doStop(final Future<Void> stopFuture) {

        Future<Void> shutdownTracker = Future.future();
        shutdownTracker.setHandler(done -> {
            if (done.succeeded()) {
                LOG.info("adapter has been shut down successfully");
                stopFuture.complete();
            } else {
                LOG.info("error while shutting down adapter", done.cause());
                stopFuture.fail(done.cause());
            }
        });

        Future<Void> serverTracker = Future.future();
        if (this.server != null) {
            this.server.close(serverTracker.completer());
        } else {
            serverTracker.complete();
        }

        Future<Void> insecureServerTracker = Future.future();
        if (this.insecureServer != null) {
            this.insecureServer.close(insecureServerTracker.completer());
        } else {
            insecureServerTracker.complete();
        }

        CompositeFuture.all(serverTracker, insecureServerTracker)
            .compose(d -> {
                closeClients(shutdownTracker.completer());
            }, shutdownTracker);
    }

    final void handleEndpointConnection(final MqttEndpoint endpoint) {

        LOG.debug("connection request from client [clientId: {}]", endpoint.clientIdentifier());

        if (!isConnected()) {
            endpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);
            LOG.debug("connection request from client [clientId: {}] rejected: {}",
                    endpoint.clientIdentifier(), MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);

        } else {
            if (getConfig().isAuthenticationRequired()) {
                handleEndpointConnectionWithAuthentication(endpoint);
            } else {
                handleEndpointConnectionWithoutAuthentication(endpoint);
            }
        }
    }

    /**
     * Invoked when a client sends its <em>CONNECT</em> packet and client authentication has
     * been disabled by setting the {@linkplain ProtocolAdapterProperties#isAuthenticationRequired()
     * authentication required} configuration property to {@code false}.
     * <p>
     * Registers a close handler on the endpoint which first removes any registration assertion
     * cached for the client then delegates to {@link #onClose(MqttEndpoint)}.
     * Registers a publish handler on the endpoint which invokes
     * {@link #onUnauthenticatedMessage(MqttEndpoint, MqttPublishMessage)} for each message
     * being published by the client.
     * Accepts the connection request.
     * 
     * @param endpoint The MQTT endpoint representing the client.
     */
    private void handleEndpointConnectionWithoutAuthentication(final MqttEndpoint endpoint) {

        endpoint.closeHandler(v -> {
            LOG.debug("connection to unauthenticated device [clientId: {}] closed", endpoint.clientIdentifier());
            if (registrationAssertions.remove(endpoint) != null) {
                LOG.trace("removed registration assertion for device [clientId: {}]", endpoint.clientIdentifier());
            }
            onClose(endpoint);
        });
        endpoint.publishHandler(message -> onUnauthenticatedMessage(endpoint, message));

        LOG.debug("unauthenticated device [clientId: {}] connected", endpoint.clientIdentifier());
        endpoint.accept(false);
    }

    private void handleEndpointConnectionWithAuthentication(final MqttEndpoint endpoint) {

        if (endpoint.auth() == null) {
            endpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD);
            LOG.debug("connection request from device [clientId: {}] rejected,  [cause: {}]",
                    endpoint.clientIdentifier(), "device did not provide credentials in CONNECT packet");

        } else {

            final DeviceCredentials credentials = getCredentials(endpoint.auth());

            if (credentials == null) {
                endpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD);
                LOG.debug("cannot authenticate device [clientId: {}] rejected [cause: {}]",
                        endpoint.clientIdentifier(), MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD);
            } else {
                getCredentialsAuthProvider().authenticate(credentials, attempt -> {
                    if (attempt.failed()) {

                        LOG.debug("cannot authenticate device [tenant-id: {}, auth-id: {}], cause: {}",
                                credentials.getTenantId(), credentials.getAuthId(), attempt.cause().getMessage());
                        endpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED);

                    } else {

                        final Device authenticatedDevice = attempt.result();
                        LOG.debug("successfully authenticated device [tenant-id: {}, auth-id: {}, device-id: {}]",
                                authenticatedDevice.getTenantId(), credentials.getAuthId(), authenticatedDevice.getDeviceId());
                        onAuthenticationSuccess(endpoint, authenticatedDevice);
                    }
                });
                
            }
        }
    }

    private void onAuthenticationSuccess(final MqttEndpoint endpoint, final Device authenticatedDevice) {

        endpoint.closeHandler(v -> {
            LOG.debug("connection to device [tenant-id: {}, device-id: {}] closed",
                    authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId());
            if (registrationAssertions.remove(endpoint) != null) {
                LOG.trace("removed registration assertion for device [tenant-id: {}, device-id: {}]",
                        authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId());
            }
            onClose(endpoint);
        });

        endpoint.publishHandler(message -> onAuthenticatedMessage(endpoint, message, authenticatedDevice));
        endpoint.accept(false);
    }

    /**
     * Processes a message that has been published via an unauthenticated connection.
     * <ol>
     * <li>{@linkplain #getDownstreamMessage(MqttPublishMessage) Maps the message from the device to a message to be sent downstream}.</li>
     * <li>Forwards the message to the API endpoint corresponding to the downstream address.</li>
     * </ol>
     * 
     * @param endpoint The connection over which the message has been received.
     * @param messageFromDevice The message received over the connection.
     */
    protected final void onUnauthenticatedMessage(final MqttEndpoint endpoint, final MqttPublishMessage messageFromDevice) {

        LOG.trace("received message [topic: {}, QoS: {}] from unauthenticated device", messageFromDevice.topicName(), messageFromDevice.qosLevel());
        final Future<Message> messageTracker = getDownstreamMessage(messageFromDevice);
        messageTracker.compose(message -> authorize(message, null)).compose(authorizedAddress -> {
            return publishMessage(endpoint, messageFromDevice, messageTracker.result(), null).map(s -> {
                LOG.trace("successfully processed message [topic: {}, QoS: {}] from unauthenticated device", messageFromDevice.topicName(), messageFromDevice.qosLevel());
                onMessageSent(authorizedAddress);
                return (Void) null;
            }).recover(f -> {
                LOG.debug("error processing message [topic: {}, QoS: {}] from unauthenticated device: {}",
                        messageFromDevice.topicName(), messageFromDevice.qosLevel(), f.getMessage());
                if (!ClientErrorException.class.isInstance(f)) {
                    onMessageUndeliverable(authorizedAddress);
                }
                return Future.failedFuture(f);
            });
        });
    }

    /**
     * Processes a message that has been published via an authenticated connection.
     * <ol>
     * <li>{@linkplain #getDownstreamMessage(MqttPublishMessage, Device) Maps the message} from the device to a message
     * to be sent downstream.</li>
     * <li>Authorizes the message to be forwarded downstream.</li>
     * <li>Forwards the message to the API endpoint corresponding to the downstream address.</li>
     * </ol>
     * 
     * @param endpoint The connection over which the message has been received.
     * @param messageFromDevice The message received over the connection.
     * @param authenticatedDevice The authenticated device that has published the message.
     */
    protected final void onAuthenticatedMessage(final MqttEndpoint endpoint, final MqttPublishMessage messageFromDevice,
            final Device authenticatedDevice) {

        LOG.trace("received message [topic: {}, QoS: {}] from authenticated device [tenant-id: {}, device-id: {}]",
                messageFromDevice.topicName(), messageFromDevice.qosLevel(), authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId());

        final Future<Message> mappedMessage = getDownstreamMessage(messageFromDevice, authenticatedDevice);

        mappedMessage.compose(message -> authorize(message, authenticatedDevice)).compose(authorizedResource -> {

            return publishMessage(endpoint, messageFromDevice, mappedMessage.result(), authorizedResource).map(s -> {

                LOG.trace("successfully processed message [topic: {}, QoS: {}] from authenticated device [tenantId: {}, deviceId: {}]",
                        messageFromDevice.topicName(), messageFromDevice.qosLevel(), authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId());
                onMessageSent(authorizedResource);
                return (Void) null;

            }).recover(f -> {

                LOG.debug("error processing message [topic: {}, QoS: {}] from authenticated device [tenantId: {}, deviceId: {}]: {}",
                        messageFromDevice.topicName(), messageFromDevice.qosLevel(), authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId(), f.getMessage());
                if (!ClientErrorException.class.isInstance(f)) {
                    onMessageUndeliverable(authorizedResource);
                }
                return Future.failedFuture(f);
            });
        });
    }

    private Future<Void> publishMessage(final MqttEndpoint endpoint, final MqttPublishMessage messageFromDevice, final Message message, final ResourceIdentifier authorizedResource) {

        final Future<String> assertionTracker = getRegistrationAssertion(endpoint, authorizedResource.getTenantId(), authorizedResource.getResourceId());
        final Future<MessageSender> senderTracker = getSenderForEndpoint(authorizedResource.getEndpoint(), authorizedResource.getTenantId());

        return CompositeFuture.all(assertionTracker, senderTracker).recover(t -> {
            return Future.failedFuture(t);
        }).compose(ok -> {
            addProperties(messageFromDevice, message, assertionTracker.result());
            return doUploadMessage(message, endpoint, messageFromDevice, senderTracker.result());
        });
    }

    /**
     * Checks if a device is authorized to publish a message.
     * 
     * @param message The message to authorize.
     * @param authenticatedDevice The authenticated device or {@code null} if the device has not been authenticated.
     * @return A succeeded future containing a resource identifier for the authorized message. The future will
     *         be failed if the message does not contain a <em>device_id</em> application property or has a
     *         malformed address.
     */
    private Future<ResourceIdentifier> authorize(final Message message, final Device authenticatedDevice) {

        final String deviceId = MessageHelper.getDeviceId(message);
        final String to = message.getAddress();
        if (deviceId == null) {
            return Future.failedFuture(new IllegalArgumentException("message contains no device_id property"));
        } else if (to == null) {
                return Future.failedFuture(new IllegalArgumentException("message has no address"));
        } else {
            try {
                final ResourceIdentifier address = ResourceIdentifier.fromString(to + "/" + deviceId);
                if (authenticatedDevice == null) {
                    return Future.succeededFuture(address);
                } else if (validateCredentialsWithTopicStructure(address, authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId())) {
                    return Future.succeededFuture(address);
                } else {
                    LOG.debug("discarding message published by authenticated device [tenant-id: {}, device-id: {}] to unauthorized topic [{}]",
                            authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId(), address);
                    return Future.failedFuture(new SecurityException("device is not authorized to publish to topic"));
                }
            } catch (IllegalArgumentException e) {
                LOG.debug("discarding message mapped to malformed address [to: {}]", message.getAddress());
                return Future.failedFuture(e);
            }
        }
    }

    /**
     * Gets a sender for forwarding a message to a downstream API endpoint.
     * <p>
     * This method is invoked just before the message is forwarded downstream.
     * 
     * @param endpoint The API endpoint that the message should be forwarded to.
     * @param tenantId The tenant to which the device that has published the message belongs to.
     * @return A succeeded future containing an appropriate sender.
     *         A failed future if the QoS and endpoint do not pass verification.
     */
    private Future<MessageSender> getSenderForEndpoint(final String endpoint, final String tenantId) {

        if (endpoint.equals(TELEMETRY_ENDPOINT)) {
            return getTelemetrySender(tenantId);
        } else if (endpoint.equals(EVENT_ENDPOINT)) {
            return getEventSender(tenantId);
        } else {
            // MQTT client is trying to publish on a not supported endpoint
            LOG.debug("no such endpoint [{}]", endpoint);
            return Future.failedFuture("no such endpoint");
        }
    }

    private void addProperties(final MqttPublishMessage messageFromDevice, final Message message, final String registrationAssertion) {

        if (message.getContentType() == null) {
            message.setContentType(CONTENT_TYPE_OCTET_STREAM);
        }
        message.setBody(new Data(new Binary(messageFromDevice.payload().getBytes())));
        MessageHelper.addRegistrationAssertion(message, registrationAssertion);
        MessageHelper.addProperty(message, PROPERTY_HONO_ORIG_ADDRESS, messageFromDevice.topicName());
    }

    private Future<String> getRegistrationAssertion(final MqttEndpoint endpoint, final String tenantId, final String deviceId) {

        String token = registrationAssertions.get(endpoint);
        if (token != null && !RegistrationAssertionHelperImpl.isExpired(token, 10)) {
            return Future.succeededFuture(token);
        } else {
            registrationAssertions.remove(endpoint);
            return getRegistrationAssertion(tenantId, deviceId).map(t -> {
                // if the client closes the connection right after publishing the messages and before that
                // the registration assertion has been returned, avoid to put it into the map
                if (endpoint.isConnected()) {
                    LOG.trace("caching registration assertion for [tenantId: {}, deviceId: {}]",
                            tenantId, deviceId);
                    registrationAssertions.put(endpoint, t);
                }
                return t;
            });
        }
    }

    private Future<Void> doUploadMessage(final Message message, final MqttEndpoint endpoint, final MqttPublishMessage messageFromDevice,
            final MessageSender sender) {

        Future<Void> result = Future.future();
        boolean accepted = sender.send(message, (messageId, delivery) -> {
            LOG.trace("delivery state updated [message ID: {}, new remote state: {}]", messageId, delivery.getRemoteState());
            if (messageFromDevice.qosLevel() == MqttQoS.AT_MOST_ONCE) {
                result.complete();
            } else if (messageFromDevice.qosLevel() == MqttQoS.AT_LEAST_ONCE) {
                if (Accepted.class.isInstance(delivery.getRemoteState())) {
                    // check that the remote MQTT client is still connected before sending PUBACK
                    if (endpoint.isConnected()) {
                        endpoint.publishAcknowledge(messageFromDevice.messageId());
                    }
                    result.complete();
                } else {
                    result.fail("message not accepted by remote");
                }
            }
        });
        if (!accepted) {
            result.fail("no credit available for sending message");
        }
        return result;
    }

    /**
     * Closes a connection to a client.
     * 
     * @param endpoint The connection to close.
     */
    protected final void close(final MqttEndpoint endpoint) {
        registrationAssertions.remove(endpoint);
        if (endpoint.isConnected()) {
            LOG.debug("closing connection with client [client ID: {}]", endpoint.clientIdentifier());
            endpoint.close();
        } else {
            LOG.debug("client has already closed connection");
        }
    }

    /**
     * Creates a new AMQP 1.0 message for an address, device ID and content type.
     * 
     * @param address The receiver of the message.
     * @param deviceId The identifier of the device that the message originates from.
     * @param contentType The content type describing the message's payload.
     * @return The message.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    protected static Message newMessage(final String address, final String deviceId, final String contentType) {

        Objects.requireNonNull(address);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(contentType);

        final Message msg = ProtonHelper.message();
        msg.setAddress(address.toString());
        MessageHelper.addDeviceId(msg, deviceId);
        msg.setContentType(contentType);
        return msg;
    }

    /**
     * Invoked before the connection with a client is closed.
     * <p>
     * This default implementation does nothing.
     * 
     * @param endpoint The connection to be closed.
     */
    protected void onClose(final MqttEndpoint endpoint) {
        // empty default implementation
    }

    /**
     * Extracts credentials from a client's MQTT <em>CONNECT</em> packet.
     * <p>
     * This default implementation returns {@link UsernamePasswordCredentials} created
     * from the <em>username</em> and <em>password</em> fields of the <em>CONNECT</em> packet.
     * <p>
     * Subclasses should override this method if the device uses credentials that do not
     * comply with the format expected by {@link UsernamePasswordCredentials}.
     * 
     * @param authInfo The authentication info provided by the device.
     * @return The credentials or {@code null} if the information provided by the device
     *         can not be processed.
     */
    protected DeviceCredentials getCredentials(final MqttAuth authInfo) {
        return UsernamePasswordCredentials.create(authInfo.userName(), authInfo.password(),
                getConfig().isSingleTenant());
    }

    /**
     * Invoked when a message has been forwarded downstream successfully.
     * <p>
     * This default implementation does nothing.
     * <p>
     * Subclasses should override this method in order to e.g. update metrics counters.
     * 
     * @param downstreamAddress The address that the message has been forwarded to.
     */
    protected void onMessageSent(final ResourceIdentifier downstreamAddress) {
        // empty default implementation
    }

    /**
     * Invoked when a message could not be forwarded downstream.
     * <p>
     * This method will only be invoked if the failure to forward the
     * message has not been caused by the device that published the message.
     * In particular, this method will not be invoked for messages that cannot
     * be authorized or that are published to an unsupported/unrecognized topic.
     * Such messages will be silently discarded.
     * <p>
     * This default implementation does nothing.
     * <p>
     * Subclasses should override this method in order to e.g. update metrics counters.
     * 
     * @param downstreamAddress The address that the message has been forwarded to.
     */
    protected void onMessageUndeliverable(final ResourceIdentifier downstreamAddress) {
        // empty default implementation
    }

    /**
     * Maps an MQTT message received from an anonymous device to a corresponding AMQP 1.0 message
     * to be forwarded downstream.
     * <p>
     * This method is invoked for each message received. The returned AMQP message is required to
     * have its <em>to</em> property set to an address corresponding to the Hono API endpoint
     * the MQTT message should be forwarded to. The address must consist of three segments:
     * <ol>
     * <li>the endpoint name (either <em>telemetry</em> or <em>event</em>)</li>
     * <li>the tenant to which the device belongs</li>
     * <li>the device that the data contained in the MQTT message originates from</li>
     * </ol>
     * <p>
     * Implementors
     * <ul>
     * <li>should also set the <em>content-type</em> of the AMQP message to a value
     * that describes the payload of the message appropriately for consumers to identify
     * messages they might be interested in. Otherwise the content type will be set to
     * <em>application/octet-stream</em>.</li>
     * <li>may set arbitrary <em>application properties</em> on the AMQP message.</li>
     * <li>should not copy the MQTT message's payload to the returned AMQP message and
     * should also not set a <em>message-id</em>. Both is done by the <em>publishMessage</em>
     * method when the AMQP message is forwarded downstream.</li>
     * </ul>
     * 
     * @param messageFromDevice The MQTT QoS of the published message.
     * @return A succeeded future containing the corresponding AMQP 1.0 message or a failed future
     *         if the MQTT message could not be processed.
     */
    protected abstract Future<Message> getDownstreamMessage(final MqttPublishMessage messageFromDevice);

    /**
     * Maps an MQTT message received from an authenticated device to a corresponding AMQP 1.0 message
     * to be forwarded downstream.
     * <p>
     * This method is invoked for each message received. The returned AMQP message is required to
     * have its <em>to</em> property set to an address corresponding to the Hono API endpoint
     * the MQTT message should be forwarded to. The address must consist of three segments:
     * <ol>
     * <li>the endpoint name (either <em>telemetry</em> or <em>event</em>)</li>
     * <li>the tenant to which the device belongs</li>
     * <li>the device that the data contained in the MQTT message originates from</li>
     * </ol>
     * <p>
     * Implementors
     * <ul>
     * <li>should also set the <em>content-type</em> of the AMQP message to a value
     * that describes the payload of the message appropriately for consumers to identify
     * messages they might be interested in. Otherwise the content type will be set to
     * <em>application/octet-stream</em>.</li>
     * <li>may set arbitrary <em>application properties</em> on the AMQP message.</li>
     * <li>should not copy the MQTT message's payload to the returned AMQP message and
     * should also not set a <em>message-id</em>. Both is done by the <em>publishMessage</em>
     * method when the AMQP message is forwarded downstream.</li>
     * </ul>
     * 
     * @param messageFromDevice The MQTT QoS of the published message.
     * @param deviceIdentity The identity of the device. This information can be used by implementors to
     *        e.g. create the downstream message's address.
     * @return A succeeded future containing the corresponding AMQP 1.0 message or a failed future
     *         if the MQTT message could not be processed.
     */
    protected abstract Future<Message> getDownstreamMessage(final MqttPublishMessage messageFromDevice, final Device deviceIdentity);
}
