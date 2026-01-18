/*
 * Copyright (c) 2025 TESOBE
 *
 * This file is part of OBP-Rabbit-Cats-Adapter.
 *
 * OBP-Rabbit-Cats-Adapter is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License, Version 2.0.
 */

package com.tesobe.obp.adapter.messaging

import cats.effect.IO
import cats.syntax.either._
import com.tesobe.obp.adapter.config.AdapterConfig
import com.tesobe.obp.adapter.interfaces.CBSConnector
import com.tesobe.obp.adapter.models._
import com.tesobe.obp.adapter.telemetry.Telemetry
import com.tesobe.obp.adapter.http.DiscoveryServer
import io.circe.parser._
import io.circe.syntax._

import scala.concurrent.duration._

/** RabbitMQ consumer for OBP messages
  *
  * Consumes messages from the request queue, processes them via CBS connector,
  * and sends responses to the response queue.
  */
object RabbitMQConsumer {

  /** Run the consumer
    */
  def run(
      config: AdapterConfig,
      connector: CBSConnector,
      telemetry: Telemetry,
      redis: Option[dev.profunktor.redis4cats.RedisCommands[IO, String, String]]
  ): IO[Unit] = {
    val client = RabbitMQClient(config)

    client.createConnection
      .use { connection =>
        client.createChannel(connection).use { channel =>
          for {
            _ <- IO.println(
              s"[RabbitMQ] Connected to ${config.rabbitmq.host}:${config.rabbitmq.port}"
            )
            _ <- telemetry.recordRabbitMQConnected(
              config.rabbitmq.host,
              config.rabbitmq.port
            )

            // Declare queues
            _ <- client.declareQueue(channel, config.queue.requestQueue)
            _ <- client.declareQueue(channel, config.queue.responseQueue)
            _ <- IO.println(s"[Queue] Request: ${config.queue.requestQueue}")
            _ <- IO.println(s"[Queue] Response: ${config.queue.responseQueue}")

            _ <- telemetry.recordQueueConsumptionStarted(
              config.queue.requestQueue
            )
            _ <- IO.println(
              s"[OK] Consuming from queue: ${config.queue.requestQueue}"
            )
            _ <- IO.println("")

            // Start consuming messages
            _ <- client.consumeMessages(
              channel,
              config.queue.requestQueue,
              (message, routingKey) =>
                processMessage(
                  client,
                  channel,
                  message,
                  routingKey,
                  config,
                  connector,
                  telemetry,
                  redis
                )
            )

          } yield ()
        }
      }
      .handleErrorWith { error =>
        telemetry.recordRabbitMQConnectionError(error.getMessage) *>
          IO.println(s"[ERROR] RabbitMQ error: ${error.getMessage}") *>
          IO(error.printStackTrace()) *>
          IO.raiseError(error)
      }
  }

  /** Process a single message
    */
  private def processMessage(
      client: RabbitMQClient,
      channel: com.rabbitmq.client.Channel,
      messageJson: String,
      process: String,
      config: AdapterConfig,
      connector: CBSConnector,
      telemetry: Telemetry,
      redis: Option[dev.profunktor.redis4cats.RedisCommands[IO, String, String]]
  ): IO[Unit] = {
    val startTime = System.currentTimeMillis()

    (for {
      // Increment outbound counter
      _ <- redis match {
        case Some(r) => RedisCounter.incrementOutbound(r, process)
        case None    => IO.unit
      }
      // Parse the message
      outboundMsg <- parseOutboundMessage(messageJson)

      // Parse raw JSON to extract additional data fields
      jsonObj <- IO.fromEither(
        io.circe.parser.parse(messageJson).flatMap(_.as[io.circe.JsonObject])
      )

      // Extract data fields (everything except outboundAdapterCallContext)
      dataFields = jsonObj.filterKeys(_ != "outboundAdapterCallContext")

      _ <- telemetry.recordMessageReceived(
        process,
        outboundMsg.outboundAdapterCallContext.correlationId,
        config.queue.requestQueue
      )

      // Extract call context
      callContext = CallContext.fromOutbound(outboundMsg)

      // Log message processing
      _ <- IO.println(s"[${callContext.correlationId}] Processing: $process")

      // Handle adapter-specific messages or delegate to CBS
      cbsResponse <- process match {
        case "obp.getAdapterInfo" =>
          handleGetAdapterInfo(connector, callContext)
        case _ =>
          connector.handleMessage(process, dataFields, callContext)
      }

      // Build inbound message
      inboundMsg <- buildInboundMessage(outboundMsg, cbsResponse)

      // Send response
      _ <- sendResponse(client, channel, config.queue.responseQueue, inboundMsg)

      // Increment inbound counter
      _ <- redis match {
        case Some(r) => RedisCounter.incrementInbound(r, process)
        case None    => IO.unit
      }

      // Record success
      duration = (System.currentTimeMillis() - startTime).millis
      _ <- telemetry.recordMessageProcessed(
        process,
        callContext.correlationId,
        duration
      )

      _ <- IO.println(
        s"[${callContext.correlationId}] [OK] Completed in ${duration.toMillis}ms"
      )

    } yield ()).handleErrorWith { error =>
      // Handle errors
      val duration = (System.currentTimeMillis() - startTime).millis
      for {
        _ <- telemetry.recordMessageFailed(
          process = "unknown",
          correlationId = "unknown",
          errorCode = "ADAPTER_ERROR",
          errorMessage = error.getMessage,
          duration = duration
        )
        _ <- IO.println(
          s"[ERROR] Error processing message: ${error.getMessage}"
        )
        _ <- IO(error.printStackTrace())
      } yield ()
    }
  }

  /** Handle getAdapterInfo - returns adapter information, not CBS information
    */
  private def handleGetAdapterInfo(
      connector: CBSConnector,
      callContext: CallContext
  ): IO[com.tesobe.obp.adapter.interfaces.CBSResponse] = {
    import io.circe.Json
    import io.circe.JsonObject

    IO.pure(
      com.tesobe.obp.adapter.interfaces.CBSResponse.success(
        JsonObject(
          "name" -> Json.fromString("OBP-Rabbit-Cats-Adapter"),
          "version" -> Json.fromString("1.0.0-SNAPSHOT"),
          "git_commit" -> Json.fromString("development"),
          "date" -> Json.fromString(java.time.Instant.now().toString)
        ),
        Nil
      )
    )
  }

  /** Parse JSON string to OutboundMessage
    */
  private def parseOutboundMessage(json: String): IO[OutboundMessage] = {
    IO.fromEither(
      decode[OutboundMessage](json)
        .leftMap(err =>
          new RuntimeException(
            s"Failed to parse outbound message: ${err.getMessage}"
          )
        )
    )
  }

  /** Build inbound response message
    */
  private def buildInboundMessage(
      outboundMsg: OutboundMessage,
      cbsResponse: com.tesobe.obp.adapter.interfaces.CBSResponse
  ): IO[InboundMessage] = {
    val ctx = outboundMsg.outboundAdapterCallContext

    cbsResponse match {
      case com.tesobe.obp.adapter.interfaces.CBSResponse
            .Success(data, messages) =>
        IO.pure(
          InboundMessage.success(
            correlationId = ctx.correlationId,
            sessionId = ctx.sessionId,
            data = data,
            backendMessages = messages
          )
        )

      case com.tesobe.obp.adapter.interfaces.CBSResponse
            .Error(code, message, messages) =>
        IO.pure(
          InboundMessage.error(
            correlationId = ctx.correlationId,
            sessionId = ctx.sessionId,
            errorCode = code,
            errorMessage = message,
            backendMessages = messages
          )
        )
    }
  }

  /** Send response message to response queue
    */
  private def sendResponse(
      client: RabbitMQClient,
      channel: com.rabbitmq.client.Channel,
      responseQueue: String,
      message: InboundMessage
  ): IO[Unit] = {
    for {
      // Convert to JSON
      json <- IO.pure(message.asJson.noSpaces)

      // Cache response for test messages (so web UI can retrieve it)
      _ <- IO(
        DiscoveryServer.cacheResponse(
          message.inboundAdapterCallContext.correlationId,
          json
        )
      )

      // Publish message
      _ <- client.publishMessage(channel, responseQueue, json)

    } yield ()
  }
}
