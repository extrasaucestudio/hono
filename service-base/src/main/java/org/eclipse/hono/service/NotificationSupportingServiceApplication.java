/**
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
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


package org.eclipse.hono.service;

import org.eclipse.hono.client.amqp.config.ClientConfigProperties;
import org.eclipse.hono.client.amqp.connection.HonoConnection;
import org.eclipse.hono.client.notification.amqp.ProtonBasedNotificationReceiver;
import org.eclipse.hono.client.notification.kafka.KafkaBasedNotificationReceiver;
import org.eclipse.hono.client.notification.kafka.NotificationKafkaConsumerConfigProperties;
import org.eclipse.hono.client.util.ServiceClient;
import org.eclipse.hono.notification.NotificationConstants;
import org.eclipse.hono.notification.NotificationEventBusSupport;
import org.eclipse.hono.notification.NotificationReceiver;
import org.eclipse.hono.service.util.ServiceClientAdapter;

/**
 * A service application that supports receiving notifications.
 *
 */
public abstract class NotificationSupportingServiceApplication extends AbstractServiceApplication {

    /**
     * Creates a notification receiver for configuration properties.
     *
     * @param kafkaNotificationConfig The Kafka connection properties.
     * @param amqpNotificationConfig The AMQP 1.0 connection properties.
     * @return the receiver.
     * @throws IllegalStateException if both AMQP and Kafka based messaging have been disabled explicitly.
     */
    protected NotificationReceiver notificationReceiver(
            final NotificationKafkaConsumerConfigProperties kafkaNotificationConfig,
            final ClientConfigProperties amqpNotificationConfig) {

        final NotificationReceiver notificationReceiver;
        if (!appConfig.isKafkaMessagingDisabled() && kafkaNotificationConfig.isConfigured()) {
            notificationReceiver = new KafkaBasedNotificationReceiver(vertx, kafkaNotificationConfig);
        } else if (!appConfig.isAmqpMessagingDisabled() && amqpNotificationConfig.isHostConfigured()) {
            final var notificationConfig = new ClientConfigProperties(amqpNotificationConfig);
            notificationConfig.setServerRole("Notification");
            notificationReceiver = new ProtonBasedNotificationReceiver(
                    HonoConnection.newConnection(vertx, notificationConfig, tracer));
        } else {
            throw new IllegalStateException("at least one of Kafka or AMQP messaging must be configured");
        }
        if (notificationReceiver instanceof ServiceClient serviceClient) {
            healthCheckServer.registerHealthCheckResources(ServiceClientAdapter.forClient(serviceClient));
        }
        final var notificationSender = NotificationEventBusSupport.getNotificationSender(vertx);
        NotificationConstants.DEVICE_REGISTRY_NOTIFICATION_TYPES.forEach(notificationType -> {
            notificationReceiver.registerConsumer(notificationType, notificationSender::handle);
        });
        return notificationReceiver;
    }
}
