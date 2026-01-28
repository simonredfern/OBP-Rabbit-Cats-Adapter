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
import com.tesobe.obp.adapter.interfaces.LocalAdapter
import com.tesobe.obp.adapter.models._
import com.tesobe.obp.adapter.telemetry.Telemetry
import com.tesobe.obp.adapter.http.DiscoveryServer
import io.circe.parser._
import io.circe.syntax._

import scala.concurrent.duration._

/** RabbitMQ consumer for OBP messages
  *
  * Consumes messages from the request queue, processes them via local adapter,
  * and sends responses to the replyTo queue (RPC pattern) or fallback response queue.
  */
object RabbitMQConsumer {

  /** Run the consumer
    */
  def run(
      config: AdapterConfig,
      localAdapter: LocalAdapter,
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
            _ <- IO.println(s"[INFO] RabbitMQ connected: ${config.rabbitmq.host}:${config.rabbitmq.port}")
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
            _ <- IO.println(s"[INFO] Queue consumption started: ${config.queue.requestQueue}")
            _ <- IO.println(
              s"[OK] Consuming from queue: ${config.queue.requestQueue}"
            )
            _ <- IO.println("")

            // Start consuming messages with MessageEnvelope
            _ <- client.consumeMessages(
              channel,
              config.queue.requestQueue,
              envelope =>
                processMessage(
                  client,
                  channel,
                  envelope,
                  config,
                  localAdapter,
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
      envelope: MessageEnvelope,
      config: AdapterConfig,
      localAdapter: LocalAdapter,
      telemetry: Telemetry,
      redis: Option[dev.profunktor.redis4cats.RedisCommands[IO, String, String]]
  ): IO[Unit] = {
    val startTime = System.currentTimeMillis()
    val messageJson = envelope.body
    val process = envelope.messageId
    
    // Use replyTo from message properties if present, otherwise fall back to configured response queue
    val responseQueue = envelope.replyTo.getOrElse(config.queue.responseQueue)
    val rabbitCorrelationId = envelope.correlationId

    (for {
      // Log incoming message details
      _ <- IO.println(s"[DEBUG] Received message - process: $process, replyTo: ${envelope.replyTo}, correlationId: ${envelope.correlationId}")
      
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

      // Handle adapter-specific messages or delegate to local adapter
      adapterResponse <- process match {
        case "obp.getAdapterInfo" | "obp_get_adapter_info" =>
          handleGetAdapterInfo(localAdapter, callContext)
        case _ =>
          localAdapter.handleMessage(process, dataFields, callContext)
      }

      // Build inbound message
      inboundMsg <- buildInboundMessage(outboundMsg, adapterResponse)

      // Send response to replyTo queue with correlationId
      _ <- IO.println(s"[DEBUG] Sending response to queue: $responseQueue with correlationId: $rabbitCorrelationId")
      _ <- sendResponse(client, channel, responseQueue, inboundMsg, rabbitCorrelationId)

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
          process = process,
          correlationId = envelope.correlationId.getOrElse("unknown"),
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

  /** Handle getAdapterInfo - returns adapter information
    */
  private def handleGetAdapterInfo(
      localAdapter: LocalAdapter,
      callContext: CallContext
  ): IO[com.tesobe.obp.adapter.interfaces.LocalAdapterResult] = {
    import io.circe.Json
    import io.circe.JsonObject
    import scala.sys.process._

    val gitCommit =
      try {
        "git rev-parse HEAD".!!.trim
      } catch {
        case _: Exception => "unknown"
      }

    IO.pure(
      com.tesobe.obp.adapter.interfaces.LocalAdapterResult.success(
        JsonObject(
          "errorCode" -> Json.fromString(""),
          "backendMessages" -> Json.arr(),
          "name" -> Json.fromString(s"${localAdapter.name}"),
          "version" -> Json.fromString(localAdapter.version),
          "git_commit" -> Json.fromString(gitCommit),
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
      adapterResponse: com.tesobe.obp.adapter.interfaces.LocalAdapterResult
  ): IO[InboundMessage] = {
    val ctx = outboundMsg.outboundAdapterCallContext

    adapterResponse match {
      case com.tesobe.obp.adapter.interfaces.LocalAdapterResult
            .Success(data, messages) =>
        IO.pure(
          InboundMessage.success(
            correlationId = ctx.correlationId,
            sessionId = ctx.sessionId,
            data = data,
            backendMessages = messages
          )
        )

      case com.tesobe.obp.adapter.interfaces.LocalAdapterResult
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

  /** Send response message to response queue with correlationId for RPC pattern
    */
  private def sendResponse(
      client: RabbitMQClient,
      channel: com.rabbitmq.client.Channel,
      responseQueue: String,
      message: InboundMessage,
      correlationId: Option[String]
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

      // Publish message with correlationId for RPC response matching
      _ <- client.publishMessage(channel, responseQueue, json, correlationId = correlationId)
      _ <- IO.println(s"[DEBUG] Response published to $responseQueue")

    } yield ()
  }
}
