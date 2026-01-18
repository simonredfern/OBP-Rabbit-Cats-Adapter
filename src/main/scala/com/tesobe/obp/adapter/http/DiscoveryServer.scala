/*
 * Copyright (c) 2025 TESOBE
 *
 * This file is part of OBP-Rabbit-Cats-Adapter.
 *
 * OBP-Rabbit-Cats-Adapter is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License, Version 2.0.
 */

package com.tesobe.obp.adapter.http

import cats.effect._
import cats.implicits._
import com.comcast.ip4s._
import com.tesobe.obp.adapter.config.AdapterConfig
import com.tesobe.obp.adapter.messaging.{RabbitMQClient, RedisCounter}
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.implicits._
import org.http4s.server.Server

/** Simple HTTP server for service discovery
  *
  * Provides a web page showing URLs for:
  *   - Health check
  *   - Metrics (if enabled)
  *   - RabbitMQ management UI
  *   - OpenTelemetry endpoints
  *   - Configuration info
  */
object DiscoveryServer {

  /** Shared RabbitMQ client for sending test messages
    */
  private var rabbitClient: Option[RabbitMQClient] = None

  /** Redis commands for counters
    */
  private var redisCommands
      : Option[dev.profunktor.redis4cats.RedisCommands[IO, String, String]] =
    None

  /** Cache for test message responses (correlationId -> response JSON)
    */
  private val responseCache =
    scala.collection.concurrent.TrieMap[String, String]()

  def setRabbitClient(client: RabbitMQClient): Unit = {
    rabbitClient = Some(client)
  }

  def setRedisCommands(
      redis: dev.profunktor.redis4cats.RedisCommands[IO, String, String]
  ): Unit = {
    redisCommands = Some(redis)
  }

  def cacheResponse(correlationId: String, response: String): Unit = {
    responseCache.put(correlationId, response)
  }

  def getResponse(correlationId: String): Option[String] = {
    responseCache.get(correlationId)
  }

  /** HTTP routes for discovery endpoints
    */
  def routes(config: AdapterConfig): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // Main discovery page
    case GET -> Root =>
      Ok(discoveryPage(config), `Content-Type`(MediaType.text.html))

    // Messages monitoring page
    case GET -> Root / "messages" =>
      messagesPage(config).flatMap(html =>
        Ok(html, `Content-Type`(MediaType.text.html))
      )

    // Health check endpoint
    case GET -> Root / "health" =>
      Ok(s"""{
        |  "status": "healthy",
        |  "service": "OBP-Rabbit-Cats-Adapter",
        |  "version": "1.0.0-SNAPSHOT",
        |  "timestamp": "${System.currentTimeMillis()}"
        |}""".stripMargin)
        .map(_.withContentType(`Content-Type`(MediaType.application.json)))

    // Prometheus metrics endpoint
    case GET -> Root / "metrics" =>
      import com.tesobe.obp.adapter.telemetry.PrometheusMetrics
      Ok(PrometheusMetrics.getMetrics)
        .map(_.withContentType(`Content-Type`(MediaType.text.plain)))

    // Info endpoint (JSON)
    case GET -> Root / "info" =>
      Ok(infoJson(config))
        .map(_.withContentType(`Content-Type`(MediaType.application.json)))

    // Readiness check
    case GET -> Root / "ready" =>
      Ok(s"""{
        |  "ready": true,
        |  "service": "OBP-Rabbit-Cats-Adapter"
        |}""".stripMargin)
        .map(_.withContentType(`Content-Type`(MediaType.application.json)))

    // Test message endpoint
    case POST -> Root / "test" / "adapter-info" =>
      sendTestMessage(config, "obp.getAdapterInfo").flatMap {
        case Right((correlationId, outboundMessage)) =>
          import io.circe.syntax._
          import io.circe.JsonObject
          val response = JsonObject(
            "status" -> "success".asJson,
            "message" -> "Test message sent to RabbitMQ".asJson,
            "process" -> "obp.getAdapterInfo".asJson,
            "correlationId" -> correlationId.asJson,
            "queue" -> config.queue.requestQueue.asJson,
            "outboundMessage" -> outboundMessage.asJson
          ).asJson.noSpaces
          Ok(response)
            .map(_.withContentType(`Content-Type`(MediaType.application.json)))
        case Left(error) =>
          InternalServerError(s"""{
            |  "status": "error",
            |  "message": "$error"
            |}""".stripMargin)
            .map(_.withContentType(`Content-Type`(MediaType.application.json)))
      }

    // Poll for response
    case GET -> Root / "test" / "response" / correlationId =>
      getResponse(correlationId) match {
        case Some(response) =>
          Ok(response).map(
            _.withContentType(`Content-Type`(MediaType.application.json))
          )
        case None =>
          NotFound(s"""{
            |  "status": "not_found",
            |  "message": "No response found for correlation ID: $correlationId"
            |}""".stripMargin)
            .map(_.withContentType(`Content-Type`(MediaType.application.json)))
      }

    // Get message schema from OBP message docs
    case GET -> Root / "test" / "schema" / process =>
      fetchMessageSchema(config, process).flatMap {
        case Right(schema) =>
          Ok(schema).map(
            _.withContentType(`Content-Type`(MediaType.application.json))
          )
        case Left(error) =>
          InternalServerError(s"""{
            |  "status": "error",
            |  "message": "$error"
            |}""".stripMargin)
            .map(_.withContentType(`Content-Type`(MediaType.application.json)))
      }

    // Get JSON Schema from new OBP endpoint
    case GET -> Root / "obp" / "v6.0.0" / "message-docs" / connector / "json-schema" =>
      fetchJsonSchema(config, connector).flatMap {
        case Right(schema) =>
          Ok(schema).map(
            _.withContentType(`Content-Type`(MediaType.application.json))
          )
        case Left(error) =>
          InternalServerError(s"""{
            |  "status": "error",
            |  "message": "$error"
            |}""".stripMargin)
            .map(_.withContentType(`Content-Type`(MediaType.application.json)))
      }
  }

  /** Fetch messages page with counts
    */
  private def messagesPage(config: AdapterConfig): IO[String] = {
    import io.circe.parser._

    val docsUrl =
      s"${config.http.obpApiUrl}/obp/v6.0.0/message-docs/rabbitmq_vOct2024"

    org.http4s.ember.client.EmberClientBuilder.default[IO].build.use { client =>
      client.expect[String](docsUrl).flatMap { jsonStr =>
        decode[io.circe.Json](jsonStr) match {
          case Right(json) =>
            val messageDocs = json.hcursor
              .downField("message_docs")
              .focus
              .flatMap(_.asArray)
              .getOrElse(Vector.empty)

            val processs = messageDocs
              .flatMap(_.hcursor.downField("process").as[String].toOption)
              .toList
              .sorted

            redisCommands match {
              case Some(redis) =>
                processs
                  .traverse { process =>
                    for {
                      outbound <- RedisCounter.getOutboundCount(
                        redis,
                        process
                      )
                      inbound <- RedisCounter.getInboundCount(
                        redis,
                        process
                      )
                    } yield (process, outbound, inbound)
                  }
                  .map { counts =>
                    renderMessagesPage(
                      processs.zip(counts.map(c => (c._2, c._3)))
                    )
                  }
              case None =>
                IO.pure(
                  renderMessagesPage(processs.map(mt => (mt, (0L, 0L))))
                )
            }
          case Left(error) =>
            IO.pure(
              s"""<html><body><h1>Error</h1><pre>${error.getMessage}</pre></body></html>"""
            )
        }
      }
    }
  }

  private def renderMessagesPage(
      messages: List[(String, (Long, Long))]
  ): String = {
    val rows = messages
      .map { case (process, (consumed, published)) =>
        val consumedColor =
          if (consumed > 0) "color: #22c55e; font-weight: bold;" else ""
        val publishedColor =
          if (published > 0 && published == consumed)
            "color: #22c55e; font-weight: bold;"
          else ""
        s"""<tr>
         |  <td>$process</td>
         |  <td style="$consumedColor">$consumed</td>
         |  <td style="$publishedColor">$published</td>
         |</tr>""".stripMargin
      }
      .mkString("\n")

    s"""<!DOCTYPE html>
       |<html>
       |<head>
       |  <title>OBP Messages</title>
       |  <style>
       |    body { font-family: monospace; margin: 20px; }
       |    table { border-collapse: collapse; width: 100%; }
       |    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
       |    th { background-color: #667eea; color: white; }
       |    tr:nth-child(even) { background-color: #f2f2f2; }
       |  </style>
       |</head>
       |<body>
       |  <div style="margin-bottom: 20px;">
       |    <a href="/" style="color: #667eea; text-decoration: none; margin-right: 20px;">Back to Home</a>
       |    <button onclick="location.reload()" style="background: #667eea; color: white; border: none; padding: 8px 16px; border-radius: 4px; cursor: pointer;">Refresh</button>
       |  </div>
       |  <h1>OBP Message Types</h1>
       |  <table>
       |    <tr>
       |      <th>Process</th>
       |      <th>Consumed from RabbitMQ</th>
       |      <th>Published to RabbitMQ</th>
       |    </tr>
       |    $rows
       |  </table>
       |</body>
       |</html>""".stripMargin
  }

  /** Send a test message to RabbitMQ
    */
  private def sendTestMessage(
      config: AdapterConfig,
      process: String
  ): IO[Either[String, (String, String)]] = {
    import java.util.UUID
    import io.circe.syntax._
    import io.circe.JsonObject

    rabbitClient match {
      case None =>
        IO.pure(Left("RabbitMQ client not initialized"))

      case Some(client) =>
        val correlationId = UUID.randomUUID().toString
        val testMessage = JsonObject(
          "outboundAdapterCallContext" -> JsonObject(
            "correlationId" -> correlationId.asJson,
            "sessionId" -> "test-session".asJson,
            "consumerId" -> "test-consumer".asJson,
            "generalContext" -> List.empty[String].asJson,
            "outboundAdapterAuthInfo" -> JsonObject(
              "userId" -> "test-user".asJson,
              "username" -> "test-username".asJson,
              "linkedCustomers" -> List.empty[String].asJson,
              "userAuthContext" -> List.empty[String].asJson,
              "authViews" -> List.empty[String].asJson
            ).asJson,
            "outboundAdapterConsenterInfo" -> JsonObject.empty.asJson
          ).asJson,
          "data" -> JsonObject.empty.asJson
        ).asJson.noSpaces
        client.createConnection
          .use { connection =>
            client.createChannel(connection).use { channel =>
              for {
                _ <- client.declareQueue(channel, config.queue.requestQueue)
                _ <- client.publishMessage(
                  channel,
                  config.queue.requestQueue,
                  testMessage,
                  Some(process)
                )
                _ <- IO.println(
                  s"[TEST] Sent message: $process (routing key) with correlation ID: $correlationId"
                )
              } yield Right((correlationId, testMessage))
            }
          }
          .handleErrorWith { error =>
            IO.pure(Left(s"Failed to publish message: ${error.getMessage}"))
          }
    }
  }

  /** Fetch JSON Schema from new OBP endpoint
    */
  private def fetchJsonSchema(
      config: AdapterConfig,
      connector: String
  ): IO[Either[String, String]] = {
    import org.http4s.client.Client
    import org.http4s.ember.client.EmberClientBuilder

    val schemaUrl =
      s"${config.http.obpApiUrl}/obp/v6.0.0/message-docs/$connector/json-schema"

    EmberClientBuilder
      .default[IO]
      .build
      .use { client =>
        client.expect[String](schemaUrl).map { jsonStr =>
          Right(jsonStr)
        }
      }
      .handleErrorWith { error =>
        IO.pure(
          Left(
            s"Failed to fetch JSON Schema from $schemaUrl: ${error.getMessage}"
          )
        )
      }
  }

  /** Get message schema from JSON Schema endpoint
    */
  private def fetchMessageSchema(
      config: AdapterConfig,
      process: String
  ): IO[Either[String, String]] = {
    import org.http4s.client.Client
    import org.http4s.ember.client.EmberClientBuilder
    import io.circe.parser._
    import io.circe.syntax._

    val schemaUrl =
      s"${config.http.obpApiUrl}/obp/v6.0.0/message-docs/rabbitmq_vOct2024/json-schema"

    EmberClientBuilder
      .default[IO]
      .build
      .use { client =>
        client.expect[String](schemaUrl).flatMap { jsonStr =>
          decode[io.circe.Json](jsonStr) match {
            case Right(json) =>
              // Extract outbound and inbound schemas for the process
              val definitions = json.hcursor.downField("definitions").focus

              definitions match {
                case Some(defs) =>
                  val outboundKey =
                    s"OutBound${process.split("\\.").last.capitalize}"
                  val inboundKey =
                    s"InBound${process.split("\\.").last.capitalize}"

                  val outboundSchema = defs.hcursor.downField(outboundKey).focus
                  val inboundSchema = defs.hcursor.downField(inboundKey).focus

                  val result = io.circe
                    .JsonObject(
                      "process" -> process.asJson,
                      "outboundSchema" -> outboundSchema
                        .getOrElse(io.circe.Json.Null),
                      "inboundSchema" -> inboundSchema
                        .getOrElse(io.circe.Json.Null)
                    )
                    .asJson
                    .noSpaces

                  IO.pure(Right(result))
                case None =>
                  IO.pure(Left("No definitions field found in JSON schema"))
              }
          }
        }
      }
      .handleErrorWith { error =>
        IO.pure(Left(s"Failed to fetch JSON schema: ${error.getMessage}"))
      }
  }

  /** Generate HTML discovery page
    */
  private def discoveryPage(config: AdapterConfig): String = {
    val serverUrl = s"http://localhost:${config.http.port}"
    val rabbitmqManagementUrl = s"http://${config.rabbitmq.host}:15672"

    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |    <meta charset="UTF-8">
       |    <meta name="viewport" content="width=device-width, initial-scale=1.0">
       |    <title>OBP Adapter Discovery</title>
       |    <style>
       |        * {
       |            margin: 0;
       |            padding: 0;
       |            box-sizing: border-box;
       |        }
       |        body {
       |            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
       |            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
       |            color: #333;
       |            min-height: 100vh;
       |            padding: 2rem;
       |        }
       |        .container {
       |            max-width: 1600px;
       |            margin: 0 auto;
       |        }
       |        header {
       |            background: white;
       |            border-radius: 12px;
       |            padding: 2rem;
       |            margin-bottom: 2rem;
       |            box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
       |        }
       |        h1 {
       |            color: #667eea;
       |            font-size: 2.5rem;
       |            margin-bottom: 0.5rem;
       |        }
       |        .subtitle {
       |            color: #666;
       |            font-size: 1.1rem;
       |        }
       |        .status-badge {
       |            display: inline-block;
       |            background: #10b981;
       |            color: white;
       |            padding: 0.25rem 1rem;
       |            border-radius: 20px;
       |            font-size: 0.9rem;
       |            margin-top: 1rem;
       |        }
       |        .grid {
       |            display: grid;
       |            grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
       |            gap: 1.5rem;
       |            margin-bottom: 2rem;
       |        }
       |        .card {
       |            background: white;
       |            border-radius: 12px;
       |            padding: 1.5rem;
       |            box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
       |            transition: transform 0.2s, box-shadow 0.2s;
       |        }
       |        .card:hover {
       |            transform: translateY(-4px);
       |            box-shadow: 0 8px 12px rgba(0, 0, 0, 0.15);
       |        }
       |        .card-full {
       |            grid-column: 1 / -1;
       |        }
       |        .card h2 {
       |            color: #667eea;
       |            font-size: 1.5rem;
       |            margin-bottom: 1rem;
       |            display: flex;
       |            align-items: center;
       |            gap: 0.5rem;
       |        }
       |        .card-icon {
       |            font-size: 1.8rem;
       |        }
       |        .link-list {
       |            list-style: none;
       |        }
       |        .link-list li {
       |            margin-bottom: 0.75rem;
       |        }
       |        .link-list a {
       |            color: #667eea;
       |            text-decoration: none;
       |            display: flex;
       |            align-items: center;
       |            gap: 0.5rem;
       |            padding: 0.5rem;
       |            border-radius: 6px;
       |            transition: background 0.2s;
       |        }
       |        .link-list a:hover {
       |            background: #f3f4f6;
       |        }
       |        .link-list a::before {
       |            content: "→";
       |            font-weight: bold;
       |        }
       |        .info-table {
       |            width: 100%;
       |            border-collapse: collapse;
       |        }
       |        .info-table td {
       |            padding: 0.5rem;
       |            border-bottom: 1px solid #e5e7eb;
       |        }
       |        .info-table td:first-child {
       |            font-weight: 600;
       |            color: #666;
       |            width: 40%;
       |        }
       |        .info-table tr:last-child td {
       |            border-bottom: none;
       |        }
       |        code {
       |            background: #f3f4f6;
       |            padding: 0.2rem 0.5rem;
       |            border-radius: 4px;
       |            font-family: 'Monaco', 'Courier New', monospace;
       |            font-size: 0.9rem;
       |        }
       |        footer {
       |            text-align: center;
       |            color: white;
       |            margin-top: 2rem;
       |            opacity: 0.8;
       |        }
       |        .external-link::after {
       |            content: "↗";
       |            font-size: 0.8rem;
       |            margin-left: 0.25rem;
       |        }
       |    </style>
       |</head>
       |<body>
       |    <div class="container">
       |        <header>
       |            <h1>OBP Rabbit Cats Adapter</h1>
       |            <p class="subtitle">Service Discovery & Monitoring Dashboard</p>
       |            <span class="status-badge">[Running]</span>
       |        </header>
       |
       |        <div class="grid">
       |            <!-- Health, Observability, Documentation & Quick Info -->
       |            <div class="card card-full">
       |                <h2>Health, Observability & Documentation</h2>
       |                <div style="display: grid; grid-template-columns: 2fr 1fr; gap: 2rem;">
       |                    <div>
       |                        <ul class="link-list">
                            <li><a href="$serverUrl/messages">Messages</a></li>
       |                            <li><a href="$serverUrl/health">Health Check</a></li>
       |                            <li><a href="$serverUrl/ready">Readiness Check</a></li>
       |                            <li><a href="$serverUrl/metrics">Prometheus Metrics</a></li>
       |                            <li><a href="$serverUrl/info">Service Info (JSON)</a></li>
       |                            <li><a href="$rabbitmqManagementUrl" class="external-link">RabbitMQ Management</a></li>
       |                            <li><a href="${config.http.apiExplorerUrl}/message-docs/rabbitmq_vOct2024" class="external-link">API Explorer</a></li>
       |                            <li><a href="${config.http.obpApiUrl}/obp/v6.0.0/message-docs/rabbitmq_vOct2024" class="external-link">Message Docs (API) (Source of Truth)</a></li>
       |                        </ul>

       |                    </div>
       |                    <div>
       |                        <h3 style="color: #667eea; font-size: 1.2rem; margin-bottom: 1rem;">Quick Info</h3>
       |                        <table class="info-table">
       |                            <tr>
       |                                <td>Version:</td>
       |                                <td><code>1.0.0-SNAPSHOT</code></td>
       |                            </tr>
       |                            <tr>
       |                                <td>HTTP Server:</td>
       |                                <td><code>${config.http.host}:${config.http.port}</code></td>
       |                            </tr>
       |                            <tr>
       |                                <td>Prefetch Count:</td>
       |                                <td><code>${config.queue.prefetchCount}</code></td>
       |                            </tr>
       |                            <tr>
       |                                <td>Metrics:</td>
       |                                <td>${if (config.enableMetrics)
        "[Enabled]"
      else "[Disabled]"}</td>
       |                            </tr>
       |                            <tr>
       |                                <td>Log Level:</td>
       |                                <td><code>${config.logLevel}</code></td>
       |                            </tr>
       |                        </table>
       |                    </div>
       |                </div>
       |            </div>
       |
       |            <!-- Test Messages -->
       |            <div class="card card-full">
                <!-- Test Messages -->
                <div class="card card-full">
                    <h2>Test Messages</h2>
                    <p style="margin-bottom: 1rem; color: #666; font-size: 0.9rem;">
                        Send test messages to RabbitMQ to verify that the adapter is consuming messages and replying. You can check the difference between the actual and expected messages below.
                    </p>

                    <!-- Expected Message Format -->
                    <div style="margin-bottom: 1.5rem; padding: 1rem; background: #f9fafb; border-radius: 8px; border: 1px solid #e5e7eb;">
                        <h3 style="color: #667eea; font-size: 1.1rem; margin-bottom: 1rem;">Expected Message Format (from Message Docs)</h3>

                        <div style="margin-bottom: 1rem;">
                            <strong style="color: #374151; display: block; margin-bottom: 0.5rem;">Description:</strong>
                            <p style="color: #6b7280; font-size: 0.9rem; margin: 0; line-height: 1.5;">
                                Get Adapter Info - Returns information about the adapter including name, version, and git commit details
                            </p>
                        </div>

                        <details style="margin-bottom: 1rem;">
                            <summary style="cursor: pointer; font-weight: bold; color: #374151; padding: 0.5rem; background: white; border-radius: 4px; border: 1px solid #e5e7eb;">Expected Outbound (source of truth)</summary>
                            <pre id="expected-outbound-display" style="margin: 0.75rem 0 0 0; font-size: 0.85rem; white-space: pre-wrap; word-wrap: break-word; overflow-x: auto; background: #1f2937; color: #f3f4f6; padding: 1rem; border-radius: 4px; line-height: 1.4;">Loading from Message Docs API...</pre>
                        </details>

                        <details>
                            <summary style="cursor: pointer; font-weight: bold; color: #374151; padding: 0.5rem; background: white; border-radius: 4px; border: 1px solid #e5e7eb;">Expected Inbound (source of truth)</summary>
                            <pre id="expected-inbound-display" style="margin: 0.75rem 0 0 0; font-size: 0.85rem; white-space: pre-wrap; word-wrap: break-word; overflow-x: auto; background: #1f2937; color: #f3f4f6; padding: 1rem; border-radius: 4px; line-height: 1.4;">Loading from Message Docs API...</pre>
                        </details>
                    </div>

                    <button onclick="sendTestMessage()" style="
                        background: #667eea;
                        color: white;
                        border: none;
                        padding: 0.75rem 1.5rem;
                        border-radius: 6px;
                        font-size: 1rem;
                        cursor: pointer;
                        width: 100%;
                        transition: background 0.2s;
                    " onmouseover="this.style.background='#5568d3'" onmouseout="this.style.background='#667eea'">
                        Send Get Adapter Info
                    </button>

                <div id="test-result" style="
                    margin-top: 1rem;
                    padding: 0.75rem;
                    border-radius: 6px;
                    display: none;
                    font-size: 0.9rem;
                "></div>
            </div>
       |        </div>
       |
       |        <footer>
       |            <p>OBP Rabbit Cats Adapter - Built with Scala, Cats Effect & fs2-rabbit</p>
       |            <p style="margin-top: 0.5rem; font-size: 0.9rem;">
       |                Copyright 2025 TESOBE - Apache License 2.0
       |            </p>
       |        </footer>
       |    </div>
       |</body>
        <script>
            // Expected message schemas - loaded from Message Docs API
            let expectedOutbound = null;
            let expectedInbound = null;
            let schemasLoaded = false;

            // Load schemas from Message Docs API on page load
            async function loadSchemas() {
                try {
                    const response = await fetch('/test/schema/obp.getAdapterInfo');
                    if (response.ok) {
                        const schema = await response.json();
                        expectedOutbound = schema.outboundSchema;
                        expectedInbound = schema.inboundSchema;
                        schemasLoaded = true;
                        console.log('[SCHEMA] Loaded JSON schemas from Message Docs API');

                        // Update the static display panels
                        const outboundDisplay = document.getElementById('expected-outbound-display');
                        const inboundDisplay = document.getElementById('expected-inbound-display');

                        if (outboundDisplay) {
                            outboundDisplay.textContent = JSON.stringify(expectedOutbound, null, 2);
                        }
                        if (inboundDisplay) {
                            inboundDisplay.textContent = JSON.stringify(expectedInbound, null, 2);
                        }
                    } else {
                        console.error('[SCHEMA] Failed to load schemas:', response.statusText);
                        document.getElementById('expected-outbound-display').textContent = 'Failed to load from API';
                        document.getElementById('expected-inbound-display').textContent = 'Failed to load from API';
                    }
                } catch (error) {
                    console.error('[SCHEMA] Error loading schemas:', error);
                    document.getElementById('expected-outbound-display').textContent = 'Error loading from API: ' + error.message;
                    document.getElementById('expected-inbound-display').textContent = 'Error loading from API: ' + error.message;
                }
            }

            // Load schemas when page loads
            loadSchemas();

            // Validate actual message against JSON schema
            function generateDiff(actual, schema, messageContext) {
                let diffHtml = '';
                let stats = { matches: 0, issues: 0, extra: 0 };
                const contextPrefix = 'In ' + messageContext + ': ';

                function getJsonType(value) {
                    if (value === null) return 'null';
                    if (Array.isArray(value)) return 'array';
                    return typeof value;
                }

                function validateAgainstSchema(actual, schema, path) {
                    let html = '';

                    if (!schema) {
                        return html;
                    }

                    const schemaType = schema.type;
                    const schemaProperties = schema.properties;
                    const required = schema.required || [];

                    // Check type
                    const actualType = getJsonType(actual);
                    if (schemaType && schemaType !== actualType) {
                        stats.issues++;
                        html += '<div style="background: #fee2e2; padding: 0.25rem 0.5rem; margin: 0.1rem 0; border-left: 3px solid #dc2626;">[TYPE] ' + contextPrefix + path + ': got ' + actualType + ', expected ' + schemaType + '</div>';
                        return html;
                    }

                    // Validate object properties
                    if (schemaType === 'object' && schemaProperties) {
                        const actualKeys = Object.keys(actual || {});
                        const schemaKeys = Object.keys(schemaProperties);

                        // Check required properties
                        for (const key of required) {
                            const currentPath = path ? path + '.' + key : key;
                            if (!(key in actual)) {
                                stats.issues++;
                                html += '<div style="background: #fee2e2; padding: 0.25rem 0.5rem; margin: 0.1rem 0; border-left: 3px solid #dc2626;">[MISSING] ' + contextPrefix + currentPath + ' (required)</div>';
                            }
                        }

                        // Check each property
                        for (const key of actualKeys) {
                            const currentPath = path ? path + '.' + key : key;
                            if (schemaProperties[key]) {
                                if (actual[key] !== undefined && actual[key] !== null) {
                                    html += validateAgainstSchema(actual[key], schemaProperties[key], currentPath);
                                    stats.matches++;
                                }
                            } else {
                                stats.extra++;
                                html += '<div style="background: #dbeafe; padding: 0.25rem 0.5rem; margin: 0.1rem 0; border-left: 3px solid #3b82f6;">[EXTRA] ' + contextPrefix + currentPath + ' (not in schema)</div>';
                            }
                        }
                    } else if (schemaType === 'array' && schema.items) {
                        if (Array.isArray(actual)) {
                            stats.matches++;
                        }
                    } else {
                        stats.matches++;
                    }

                    return html;
                }

                diffHtml = validateAgainstSchema(actual, schema, '');

                let summaryColor = stats.issues === 0 ? '#22c55e' : '#f59e0b';
                let summaryBg = stats.issues === 0 ? '#f0fdf4' : '#fef3c7';
                let summary = '<div style="background: ' + summaryBg + '; padding: 0.5rem; margin-bottom: 0.5rem; border-radius: 4px; border-left: 3px solid ' + summaryColor + '; font-weight: bold;">';
                summary += 'Schema Validation: ' + stats.matches + ' valid, ' + stats.issues + ' issues, ' + stats.extra + ' extra fields';
                summary += '</div>';

                return summary + (diffHtml || '<div style="padding: 0.5rem; color: #666;">Message validates against schema</div>');
            }

            async function sendTestMessage() {
                const resultDiv = document.getElementById('test-result');
                resultDiv.style.display = 'block';
                resultDiv.style.background = '#f3f4f6';
                resultDiv.style.color = '#666';

                if (!schemasLoaded) {
                    resultDiv.innerHTML = 'Loading schemas from Message Docs API...';
                    await loadSchemas();
                    if (!schemasLoaded) {
                        resultDiv.style.background = '#fee2e2';
                        resultDiv.style.color = '#991b1b';
                        resultDiv.innerHTML = '<strong>Error:</strong> Failed to load message schemas from API';
                        return;
                    }
                }

                resultDiv.innerHTML = 'Sending test message...';

                try {
                    const response = await fetch('/test/adapter-info', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json'
                        }
                    });

                    const data = await response.json();

                    if (response.ok) {
                        resultDiv.style.background = '#d1fae5';
                        resultDiv.style.color = '#065f46';
                        const outboundContent = `
                            <strong>Outbound Message Sent:</strong><br>
                            <div style="margin-top: 0.5rem; padding: 0.5rem; background: white; border-radius: 4px; color: #333;">
                                <strong>Process:</strong> $${data.process}<br>
                                <strong>Correlation ID:</strong> $${data.correlationId}<br>
                                <strong>Queue:</strong> $${data.queue}<br>
                                <details style="margin-top: 0.5rem;">
                                    <summary style="cursor: pointer; color: #667eea;">Show Outbound JSON</summary>
                                    <pre style="margin: 0.5rem 0; font-size: 0.85rem; white-space: pre-wrap; word-wrap: break-word; overflow-x: auto;">$${JSON.stringify(JSON.parse(data.outboundMessage), null, 2)}</pre>
                                </details>
                                <details style="margin-top: 0.5rem;">
                                    <summary style="cursor: pointer; color: #667eea; font-weight: bold;">Show Diff (Actual vs Expected)</summary>
                                    <div style="margin: 0.5rem 0; font-size: 0.85rem; font-family: monospace;">
                                        $${generateDiff(JSON.parse(data.outboundMessage), expectedOutbound, 'outbound message')}
                                    </div>
                                </details>
                            </div>
                        `;
                        resultDiv.innerHTML = outboundContent + `
                            <em style="font-size: 0.85rem; margin-top: 0.5rem; display: block; color: #065f46;">
                                Waiting for inbound response...
                            </em>
                        `;

                        // Poll for response
                        pollForResponse(data.correlationId, resultDiv, outboundContent);
                    } else {
                        resultDiv.style.background = '#fee2e2';
                        resultDiv.style.color = '#991b1b';
                        resultDiv.innerHTML = `<strong>Error:</strong> $${data.message}`;
                    }
                } catch (error) {
                    resultDiv.style.background = '#fee2e2';
                    resultDiv.style.color = '#991b1b';
                    resultDiv.innerHTML = `<strong>Error:</strong> $${error.message}`;
                }
            }

            async function pollForResponse(correlationId, resultDiv, outboundContent) {
                let attempts = 0;
                const maxAttempts = 30; // 30 seconds max

                const poll = async () => {
                    try {
                        const response = await fetch(`/test/response/$${correlationId}`);

                        if (response.ok) {
                            const responseData = await response.json();
                            resultDiv.innerHTML = outboundContent + `
                                <hr style="margin: 1rem 0; border: none; border-top: 1px solid #065f46;">
                                <strong>Inbound Message Received:</strong><br>
                                <div style="margin-top: 0.5rem; padding: 0.5rem; background: white; border-radius: 4px; color: #333;">
                                    <strong>Status:</strong> $${responseData.status.errorCode || 'SUCCESS'}<br>
                                    <strong>Correlation ID:</strong> $${responseData.inboundAdapterCallContext.correlationId}<br>
                                    <details style="margin-top: 0.5rem;">
                                        <summary style="cursor: pointer; color: #667eea;">Show Inbound JSON</summary>
                                        <pre style="margin: 0.5rem 0; font-size: 0.85rem; white-space: pre-wrap; word-wrap: break-word; overflow-x: auto;">$${JSON.stringify(responseData, null, 2)}</pre>
                                    </details>
                                    <details style="margin-top: 0.5rem;">
                                        <summary style="cursor: pointer; color: #667eea;">Show Data Only</summary>
                                        <pre style="margin: 0.5rem 0; font-size: 0.85rem; white-space: pre-wrap; word-wrap: break-word; overflow-x: auto;">$${JSON.stringify(responseData.data, null, 2)}</pre>
                                    </details>
                                    <details style="margin-top: 0.5rem;">
                                        <summary style="cursor: pointer; color: #667eea; font-weight: bold;">Show Diff (Actual vs Expected)</summary>
                                        <div style="margin: 0.5rem 0; font-size: 0.85rem; font-family: monospace;">
                                            $${generateDiff(responseData, expectedInbound, 'inbound message')}
                                        </div>
                                    </details>
                                </div>
                            `;
                        } else if (attempts < maxAttempts) {
                            attempts++;
                            setTimeout(poll, 1000);
                        } else {
                            resultDiv.style.background = '#fef3c7';
                            resultDiv.style.color = '#92400e';
                            resultDiv.innerHTML = `
                                <strong>Timeout</strong><br>
                                No response received after 30 seconds.<br>
                                Check adapter logs for details.
                            `;
                        }
                    } catch (error) {
                        if (attempts < maxAttempts) {
                            attempts++;
                            setTimeout(poll, 1000);
                        } else {
                            resultDiv.style.background = '#fee2e2';
                            resultDiv.style.color = '#991b1b';
                            resultDiv.innerHTML = `<strong>Error:</strong> Failed to get response`;
                        }
                    }
                };

                poll();
            }

        </script>
       |</html>""".stripMargin
  }

  /** Generate JSON info response
    */
  private def infoJson(config: AdapterConfig): String = {
    s"""{
       |  "service": "OBP-Rabbit-Cats-Adapter",
       |  "version": "1.0.0-SNAPSHOT",
       |  "status": "running",
       |  "endpoints": {
       |    "health": "http://localhost:${config.http.port}/health",
       |    "ready": "http://localhost:${config.http.port}/ready",
       |    "info": "http://localhost:${config.http.port}/info",
       |    "discovery": "http://localhost:${config.http.port}/"
       |  },
       |  "rabbitmq": {
       |    "host": "${config.rabbitmq.host}",
       |    "port": ${config.rabbitmq.port},
       |    "managementUI": "http://${config.rabbitmq.host}:15672",
       |    "requestQueue": "${config.queue.requestQueue}",
       |    "responseQueue": "${config.queue.responseQueue}",
       |    "prefetchCount": ${config.queue.prefetchCount}
       |  },
       |  "observability": {
       |    "metricsEnabled": ${config.enableMetrics},
       |    "logLevel": "${config.logLevel}"
       |  }
       |}""".stripMargin
  }

  /** Start the HTTP server
    */
  def start(config: AdapterConfig): Resource[IO, Server] = {
    val host = Host.fromString(config.http.host).getOrElse(host"0.0.0.0")
    val port = Port.fromInt(config.http.port).getOrElse(port"8080")

    EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(routes(config).orNotFound)
      .build
  }

  /** Run the server (for standalone use)
    */
  def run(config: AdapterConfig): IO[Unit] = {
    start(config).use { server =>
      val displayHost =
        if (config.http.host == "0.0.0.0") "localhost" else config.http.host
      IO.println(
        s"[HTTP] Discovery server started at http://$displayHost:${config.http.port}"
      ) *>
        IO.println(
          s"[INFO] Visit http://localhost:${config.http.port} to see service info"
        ) *>
        IO.never
    }
  }
}
