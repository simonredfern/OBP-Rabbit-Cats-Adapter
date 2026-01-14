/*
 * Copyright (c) 2025 TESOBE
 *
 * This file is part of OBP-Rabbit-Cats-Adapter.
 */

package com.tesobe.obp.adapter

import cats.effect.{ExitCode, IO, IOApp}
import com.tesobe.obp.adapter.cbs.implementations.MockCBSConnector
import com.tesobe.obp.adapter.config.Config
import com.tesobe.obp.adapter.telemetry.ConsoleTelemetry

object AdapterMain extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val banner = 
      """
        |╔═══════════════════════════════════════════════════════════╗
        |║     OBP Rabbit Cats Adapter                              ║
        |║     Version 1.0.0-SNAPSHOT                               ║
        |╚═══════════════════════════════════════════════════════════╝
        |""".stripMargin
    
    for {
      _ <- IO.println(banner)
      
      // Load configuration
      _ <- IO.println("📝 Loading configuration...")
      config <- Config.load
      _ <- IO.println(s"✅ Configuration loaded")
      _ <- IO.println(s"   RabbitMQ: ${config.rabbitmq.host}:${config.rabbitmq.port}")
      _ <- IO.println(s"   Request Queue: ${config.queue.requestQueue}")
      _ <- IO.println(s"   Response Queue: ${config.queue.responseQueue}")
      _ <- IO.println(s"   CBS Base URL: ${config.cbs.baseUrl}")
      _ <- IO.println("")
      
      // Validate configuration
      _ <- IO.println("🔍 Validating configuration...")
      _ <- Config.validate(config)
      _ <- IO.println("✅ Configuration valid")
      _ <- IO.println("")
      
      // Create telemetry
      telemetry = new ConsoleTelemetry()
      _ <- IO.println("📊 Telemetry initialized (Console mode)")
      _ <- IO.println("")
      
      // Create CBS connector
      _ <- IO.println("🏦 Initializing CBS connector...")
      connector = new MockCBSConnector(telemetry)
      _ <- IO.println(s"✅ CBS Connector: ${connector.name} v${connector.version}")
      _ <- IO.println("")
      
      // Test CBS health
      _ <- IO.println("🏥 Checking CBS health...")
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
          IO.println("✅ CBS is healthy")
        case com.tesobe.obp.adapter.interfaces.CBSResponse.Error(code, msg, _) =>
          IO.println(s"⚠️  CBS health check failed: $code - $msg")
      }
      _ <- IO.println("")
      
      // TODO: Start RabbitMQ consumer here
      _ <- IO.println("🐰 RabbitMQ consumer not yet implemented")
      _ <- IO.println("   The adapter will run but won't process messages yet")
      _ <- IO.println("")
      
      _ <- IO.println("✅ Adapter started successfully!")
      _ <- IO.println("   Press Ctrl+C to stop")
      _ <- IO.println("")
      
      // Keep running
      _ <- IO.never
      
    } yield ExitCode.Success
  }
}
