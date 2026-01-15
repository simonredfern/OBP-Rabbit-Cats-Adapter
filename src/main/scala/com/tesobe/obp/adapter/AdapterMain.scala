/*
 * Copyright (c) 2025 TESOBE
 *
 * This file is part of OBP-Rabbit-Cats-Adapter.
 */

package com.tesobe.obp.adapter

import cats.effect.{ExitCode, IO, IOApp}
import com.tesobe.obp.adapter.cbs.implementations.MockCBSConnector
import com.tesobe.obp.adapter.config.Config
import com.tesobe.obp.adapter.http.DiscoveryServer
import com.tesobe.obp.adapter.messaging.{RabbitMQClient, RabbitMQConsumer}
import com.tesobe.obp.adapter.telemetry.ConsoleTelemetry

object AdapterMain extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val banner = 
      """
        |===============================================================
        |     OBP Rabbit Cats Adapter                              
        |     Version 1.0.0-SNAPSHOT                               
        |===============================================================
        |""".stripMargin
    
    for {
      _ <- IO.println(banner)
      
      // Load configuration
      _ <- IO.println("[CONFIG] Loading configuration...")
      config <- Config.load
      _ <- IO.println(s"[OK] Configuration loaded")
      _ <- IO.println(s"   HTTP Server: ${config.http.host}:${config.http.port}")
      _ <- IO.println(s"   RabbitMQ: ${config.rabbitmq.host}:${config.rabbitmq.port}")
      _ <- IO.println(s"   Request Queue: ${config.queue.requestQueue}")
      _ <- IO.println(s"   Response Queue: ${config.queue.responseQueue}")
      _ <- IO.println(s"   CBS Base URL: ${config.cbs.baseUrl}")
      _ <- IO.println("")
      
      // Validate configuration
      _ <- IO.println("[CONFIG] Validating configuration...")
      _ <- Config.validate(config)
      _ <- IO.println("[OK] Configuration valid")
      _ <- IO.println("")
      
      // Create telemetry
      telemetry = new ConsoleTelemetry()
      _ <- IO.println("[TELEMETRY] Initialized (Console mode)")
      _ <- IO.println("")
      
      // Create CBS connector
      _ <- IO.println("[CBS] Initializing CBS connector...")
      connector = new MockCBSConnector(telemetry)
      _ <- IO.println(s"[OK] CBS Connector: ${connector.name} v${connector.version}")
      _ <- IO.println("")
      
      // Test CBS health
      _ <- IO.println("[HEALTH] Checking CBS health...")
      healthResult <- connector.checkHealth(
        com.tesobe.obp.adapter.models.CallContext(
          correlationId = "startup-health-check",
          sessionId = "startup",
          userId = None,
          username = None,
          consumerId = None,
          generalContext = Map.empty
        )
      )
      _ <- healthResult match {
        case com.tesobe.obp.adapter.interfaces.CBSResponse.Success(data, _) =>
          IO.println("[OK] CBS is healthy")
        case com.tesobe.obp.adapter.interfaces.CBSResponse.Error(code, msg, _) =>
          IO.println(s"[WARNING] CBS health check failed: $code - $msg")
      }
      _ <- IO.println("")
      
      // Initialize RabbitMQ client for test messages
      rabbitClient = RabbitMQClient(config)
      _ <- IO(DiscoveryServer.setRabbitClient(rabbitClient))
      
      // Start HTTP discovery server and RabbitMQ consumer concurrently
      _ <- IO.println("[STARTUP] Starting services...")
      _ <- IO.println("")
      
      exitCode <- (
        if (config.http.enabled) {
          DiscoveryServer.start(config).use { server =>
            IO.println(s"[HTTP] Discovery server started at http://${server.address.getHostString}:${server.address.getPort}") *>
            IO.println(s"[INFO] Visit http://localhost:${config.http.port} to see service info") *>
            IO.println("") *>
            RabbitMQConsumer.run(config, connector, telemetry)
          }
        } else {
          IO.println("[INFO] HTTP server disabled") *>
          IO.println("") *>
          RabbitMQConsumer.run(config, connector, telemetry)
        }
      ).as(ExitCode.Success).handleErrorWith { error =>
        IO.println(s"[FATAL] Fatal error: ${error.getMessage}") *>
        IO(error.printStackTrace()) *>
        IO.pure(ExitCode.Error)
      }
      
    } yield exitCode
  }
}
