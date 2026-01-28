/*
 * Copyright (c) 2025 TESOBE
 *
 * This file is part of OBP-Rabbit-Cats-Adapter.
 *
 * OBP-Rabbit-Cats-Adapter is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License, Version 2.0.
 */

package com.tesobe.obp.adapter.messaging

import cats.effect.{IO, Resource}
import com.rabbitmq.client.{
  Channel,
  Connection,
  ConnectionFactory,
  DeliverCallback
}
import com.tesobe.obp.adapter.config.AdapterConfig
import java.nio.charset.StandardCharsets

/** Message envelope containing body and RabbitMQ properties
  */
case class MessageEnvelope(
  body: String,
  messageId: String,
  correlationId: Option[String],
  replyTo: Option[String]
)

/** Simple RabbitMQ client wrapper using the Java client library
  *
  * This provides basic publish/consume functionality without the complexity of
  * fs2-rabbit, making it easier to get started.
  */
class RabbitMQClient(config: AdapterConfig) {

  /** Create a RabbitMQ connection
    */
  def createConnection: Resource[IO, Connection] = {
    Resource.make(
      IO {
        val factory = new ConnectionFactory()
        factory.setHost(config.rabbitmq.host)
        factory.setPort(config.rabbitmq.port)
        factory.setVirtualHost(config.rabbitmq.virtualHost)
        factory.setUsername(config.rabbitmq.username)
        factory.setPassword(config.rabbitmq.password)
        factory.setConnectionTimeout(
          config.rabbitmq.connectionTimeout.toMillis.toInt
        )
        factory.setRequestedHeartbeat(
          config.rabbitmq.requestedHeartbeat.toSeconds.toInt
        )
        factory.setAutomaticRecoveryEnabled(config.rabbitmq.automaticRecovery)
        factory.newConnection()
      }
    )(conn => IO(conn.close()))
  }

  /** Create a channel from a connection
    */
  def createChannel(connection: Connection): Resource[IO, Channel] = {
    Resource.make(
      IO(connection.createChannel())
    )(channel => IO(channel.close()))
  }

  /** Declare a queue (idempotent)
    */
  def declareQueue(channel: Channel, queueName: String): IO[Unit] = {
    IO {
      channel.queueDeclare(
        queueName,
        config.queue.durable, // durable
        false, // exclusive
        config.queue.autoDelete, // autoDelete
        null // arguments
      )
    }.void
  }

  /** Publish a message to a queue with optional correlationId for RPC responses
    */
  def publishMessage(
      channel: Channel,
      queueName: String,
      message: String,
      process: Option[String] = None,
      correlationId: Option[String] = None
  ): IO[Unit] = {
    IO {
      var propsBuilder = new com.rabbitmq.client.AMQP.BasicProperties.Builder()
        .contentType("application/json")

      // Add process as messageId property (matching OBP-API behavior)
      process.foreach(p => propsBuilder = propsBuilder.messageId(p))
      
      // Add correlationId for RPC response matching
      correlationId.foreach(cid => propsBuilder = propsBuilder.correlationId(cid))

      val props = propsBuilder.build()

      channel.basicPublish(
        "", // exchange (empty = default)
        queueName, // routing key (queue name)
        props, // properties
        message.getBytes(StandardCharsets.UTF_8)
      )
    }
  }

  /** Consume messages from a queue with a callback Returns an IO that will run
    * forever, processing messages Handler receives: MessageEnvelope with body, messageId, correlationId, and replyTo
    */
  def consumeMessages(
      channel: Channel,
      queueName: String,
      handler: MessageEnvelope => IO[Unit]
  ): IO[Unit] = {
    IO {
      // Set prefetch count
      channel.basicQos(config.queue.prefetchCount)

      implicit val runtime: cats.effect.unsafe.IORuntime =
        cats.effect.unsafe.IORuntime.global

      val deliverCallback: DeliverCallback = (consumerTag, delivery) => {
        val message = new String(delivery.getBody, StandardCharsets.UTF_8)
        val props = delivery.getProperties

        // Extract properties from message
        val envelope = MessageEnvelope(
          body = message,
          messageId = Option(props.getMessageId).getOrElse("unknown"),
          correlationId = Option(props.getCorrelationId),
          replyTo = Option(props.getReplyTo)
        )

        val processAndAck = for {
          _ <- handler(envelope)
          _ <- IO(channel.basicAck(delivery.getEnvelope.getDeliveryTag, false))
        } yield ()

        // Run the handler asynchronously
        processAndAck
          .handleErrorWith { error =>
            IO.println(
              s"[ERROR] Failed to process message: ${error.getMessage}"
            ) *>
              IO(
                channel
                  .basicNack(delivery.getEnvelope.getDeliveryTag, false, false)
              )
          }
          .unsafeRunAndForget()
      }

      channel.basicConsume(
        queueName,
        false, // autoAck = false (manual ack)
        deliverCallback,
        consumerTag => {} // cancelCallback
      )
    } *> IO.never // Keep running forever
  }
}

object RabbitMQClient {
  def apply(config: AdapterConfig): RabbitMQClient = new RabbitMQClient(config)
}
