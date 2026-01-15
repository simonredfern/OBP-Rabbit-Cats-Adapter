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
import com.comcast.ip4s._
import com.tesobe.obp.adapter.config.AdapterConfig
import com.tesobe.obp.adapter.messaging.RabbitMQClient
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.implicits._
import org.http4s.server.Server

/**
 * Simple HTTP server for service discovery
 * 
 * Provides a web page showing URLs for:
 * - Health check
 * - Metrics (if enabled)
 * - RabbitMQ management UI
 * - OpenTelemetry endpoints
 * - Configuration info
 */
object DiscoveryServer {

  /**
   * Shared RabbitMQ client for sending test messages
   */
  private var rabbitClient: Option[RabbitMQClient] = None
  
  /**
   * Cache for test message responses (correlationId -> response JSON)
   */
  private val responseCache = scala.collection.concurrent.TrieMap[String, String]()
  
  def setRabbitClient(client: RabbitMQClient): Unit = {
    rabbitClient = Some(client)
  }
  
  def cacheResponse(correlationId: String, response: String): Unit = {
    responseCache.put(correlationId, response)
  }
  
  def getResponse(correlationId: String): Option[String] = {
    responseCache.get(correlationId)
  }

  /**
   * HTTP routes for discovery endpoints
   */
  def routes(config: AdapterConfig): HttpRoutes[IO] = HttpRoutes.of[IO] {
    
    // Main discovery page
    case GET -> Root =>
      Ok(discoveryPage(config), `Content-Type`(MediaType.text.html))
    
    // Health check endpoint
    case GET -> Root / "health" =>
      Ok(s"""{
        |  "status": "healthy",
        |  "service": "OBP-Rabbit-Cats-Adapter",
        |  "version": "1.0.0-SNAPSHOT",
        |  "timestamp": "${System.currentTimeMillis()}"
        |}""".stripMargin)
        .map(_.withContentType(`Content-Type`(MediaType.application.json)))
    
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
        case Right(correlationId) =>
          Ok(s"""{
            |  "status": "success",
            |  "message": "Test message sent to RabbitMQ",
            |  "messageType": "obp.getAdapterInfo",
            |  "correlationId": "$correlationId",
            |  "queue": "${config.queue.requestQueue}"
            |}""".stripMargin)
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
          Ok(response).map(_.withContentType(`Content-Type`(MediaType.application.json)))
        case None =>
          NotFound(s"""{
            |  "status": "not_found",
            |  "message": "No response found for correlation ID: $correlationId"
            |}""".stripMargin)
            .map(_.withContentType(`Content-Type`(MediaType.application.json)))
      }
  }

  /**
   * Send a test message to RabbitMQ
   */
  private def sendTestMessage(config: AdapterConfig, messageType: String): IO[Either[String, String]] = {
    import java.util.UUID
    import io.circe.syntax._
    import io.circe.JsonObject
    
    rabbitClient match {
      case None =>
        IO.pure(Left("RabbitMQ client not initialized"))
      
      case Some(client) =>
        val correlationId = UUID.randomUUID().toString
        val testMessage = JsonObject(
          "messageType" -> messageType.asJson,
          "data" -> JsonObject.empty.asJson,
          "outboundAdapterCallContext" -> JsonObject(
            "correlationId" -> correlationId.asJson,
            "sessionId" -> "test-session".asJson,
            "generalContext" -> List.empty[String].asJson
          ).asJson
        ).asJson.noSpaces
        
        // Actually publish to RabbitMQ
        client.createConnection.use { connection =>
          client.createChannel(connection).use { channel =>
            for {
              _ <- client.declareQueue(channel, config.queue.requestQueue)
              _ <- client.publishMessage(channel, config.queue.requestQueue, testMessage)
              _ <- IO.println(s"[TEST] Sent message: $messageType with correlation ID: $correlationId")
            } yield Right(correlationId)
          }
        }.handleErrorWith { error =>
          IO.pure(Left(s"Failed to publish message: ${error.getMessage}"))
        }
    }
  }

  /**
   * Generate HTML discovery page
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
       |            max-width: 1200px;
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
       |            <!-- Health & Status -->
       |            <div class="card">
       |                <h2>Health & Status</h2>
       |                <ul class="link-list">
       |                    <li><a href="$serverUrl/health">Health Check</a></li>
       |                    <li><a href="$serverUrl/ready">Readiness Check</a></li>
       |                    <li><a href="$serverUrl/info">Service Info (JSON)</a></li>
       |                </ul>
       |            </div>
       |
       |            <!-- RabbitMQ -->
       |            <div class="card">
       |                <h2>RabbitMQ</h2>
       |                <ul class="link-list">
       |                    <li><a href="$rabbitmqManagementUrl" target="_blank" class="external-link">Management UI</a></li>
       |                </ul>
       |                <table class="info-table">
       |                    <tr>
       |                        <td>Host:</td>
       |                        <td><code>${config.rabbitmq.host}:${config.rabbitmq.port}</code></td>
       |                    </tr>
       |                    <tr>
       |                        <td>Request Queue:</td>
       |                        <td><code>${config.queue.requestQueue}</code></td>
       |                    </tr>
       |                    <tr>
       |                        <td>Response Queue:</td>
       |                        <td><code>${config.queue.responseQueue}</code></td>
       |                    </tr>
       |                </table>
       |            </div>
       |
       |            <!-- Observability -->
       |            <div class="card">
       |                <h2>Observability</h2>
       |                <table class="info-table">
       |                    <tr>
       |                        <td>Metrics:</td>
       |                        <td>${if (config.enableMetrics) "[Enabled]" else "[Disabled]"}</td>
       |                    </tr>
       |                    <tr>
       |                        <td>Log Level:</td>
       |                        <td><code>${config.logLevel}</code></td>
       |                    </tr>
       |                </table>
       |                <p style="margin-top: 1rem; color: #666; font-size: 0.9rem;">
       |                    Note: OpenTelemetry metrics are logged via the telemetry interface
       |                </p>
       |            </div>
       |
       |            <!-- CBS Configuration -->
       |            <div class="card">
       |                <h2>Core Banking System</h2>
       |                <table class="info-table">
       |                    <tr>
       |                        <td>Base URL:</td>
       |                        <td><code>${config.cbs.baseUrl}</code></td>
       |                    </tr>
       |                    <tr>
       |                        <td>Auth Type:</td>
       |                        <td><code>${config.cbs.authType}</code></td>
       |                    </tr>
       |                    <tr>
       |                        <td>Timeout:</td>
       |                        <td><code>${config.cbs.timeout.toSeconds}s</code></td>
       |                    </tr>
       |                    <tr>
       |                        <td>Max Retries:</td>
       |                        <td><code>${config.cbs.maxRetries}</code></td>
       |                    </tr>
       |                </table>
       |            </div>
       |
       |            <!-- Documentation -->
       |            <div class="card">
       |                <h2>Documentation</h2>
       |                <ul class="link-list">
       |                    <li><a href="https://apiexplorer.openbankproject.com" target="_blank" class="external-link">API Explorer</a></li>
       |                </ul>
       |                <p style="margin-top: 1rem; color: #666; font-size: 0.9rem;">
       |                    Check the README and ARCHITECTURE.md in your project
       |                </p>
       |            </div>
       |
       |            <!-- Quick Actions -->
       |            <div class="card">
       |                <h2>Quick Info</h2>
       |                <table class="info-table">
       |                    <tr>
       |                        <td>Version:</td>
       |                        <td><code>1.0.0-SNAPSHOT</code></td>
       |                    </tr>
       |                    <tr>
       |                        <td>HTTP Server:</td>
       |                        <td><code>${config.http.host}:${config.http.port}</code></td>
       |                    </tr>
       |                    <tr>
       |                        <td>Prefetch Count:</td>
       |                        <td><code>${config.queue.prefetchCount}</code></td>
       |                    </tr>
       |                </table>
       |            </div>
       |        </div>

            <!-- Test Messages -->
            <div class="card">
                <h2>Test Messages</h2>
                <p style="margin-bottom: 1rem; color: #666; font-size: 0.9rem;">
                    Send test messages to RabbitMQ to verify the adapter is working
                </p>
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
            async function sendTestMessage() {
                const resultDiv = document.getElementById('test-result');
                resultDiv.style.display = 'block';
                resultDiv.style.background = '#f3f4f6';
                resultDiv.style.color = '#666';
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
                        resultDiv.innerHTML = `
                            <strong>Success!</strong><br>
                            Message Type: $${data.messageType}<br>
                            Correlation ID: $${data.correlationId}<br>
                            Queue: $${data.queue}<br>
                            <em style="font-size: 0.85rem; margin-top: 0.5rem; display: block;">
                                Waiting for response...
                            </em>
                        `;
                        
                        // Poll for response
                        pollForResponse(data.correlationId, resultDiv);
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
            
            async function pollForResponse(correlationId, resultDiv) {
                let attempts = 0;
                const maxAttempts = 30; // 30 seconds max
                
                const poll = async () => {
                    try {
                        const response = await fetch(`/test/response/$${correlationId}`);
                        
                        if (response.ok) {
                            const responseData = await response.json();
                            resultDiv.style.background = '#d1fae5';
                            resultDiv.style.color = '#065f46';
                            resultDiv.innerHTML = `
                                <strong>Response Received!</strong><br>
                                <div style="margin-top: 0.5rem; padding: 0.5rem; background: white; border-radius: 4px; color: #333;">
                                    <strong>Data:</strong><br>
                                    <pre style="margin: 0.5rem 0; font-size: 0.85rem; white-space: pre-wrap; word-wrap: break-word;">$${JSON.stringify(responseData.data, null, 2)}</pre>
                                </div>
                                <div style="margin-top: 0.5rem; font-size: 0.85rem;">
                                    <strong>Status:</strong> $${responseData.status.errorCode || 'SUCCESS'}<br>
                                    <strong>Correlation ID:</strong> $${responseData.inboundAdapterCallContext.correlationId}
                                </div>
                            `;
                        } else if (attempts < maxAttempts) {
                            attempts++;
                            setTimeout(poll, 1000); // Poll every second
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

  /**
   * Generate JSON info response
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
       |  "cbs": {
       |    "baseUrl": "${config.cbs.baseUrl}",
       |    "authType": "${config.cbs.authType}",
       |    "timeout": "${config.cbs.timeout.toSeconds}s",
       |    "maxRetries": ${config.cbs.maxRetries}
       |  },
       |  "observability": {
       |    "metricsEnabled": ${config.enableMetrics},
       |    "logLevel": "${config.logLevel}"
       |  }
       |}""".stripMargin
  }

  /**
   * Start the HTTP server
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

  /**
   * Run the server (for standalone use)
   */
  def run(config: AdapterConfig): IO[Unit] = {
    start(config).use { server =>
      IO.println(s"[HTTP] Discovery server started at http://${server.address.getHostString}:${server.address.getPort}") *>
      IO.println(s"[INFO] Visit http://localhost:${config.http.port} to see service info") *>
      IO.never
    }
  }
}