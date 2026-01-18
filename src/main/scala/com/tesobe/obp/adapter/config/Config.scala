/*
 * Copyright (c) 2025 TESOBE
 *
 * This file is part of OBP-Rabbit-Cats-Adapter.
 *
 * OBP-Rabbit-Cats-Adapter is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License, Version 2.0.
 *
 * OBP-Rabbit-Cats-Adapter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License, Version 2.0
 * along with OBP-Rabbit-Cats-Adapter. If not, see <http://www.apache.org/licenses/>.
 */

package com.tesobe.obp.adapter.config

import cats.effect.IO
import scala.concurrent.duration._

/** HTTP server configuration for discovery page */
case class HttpConfig(
    host: String,
    port: Int,
    enabled: Boolean,
    apiExplorerUrl: String,
    obpApiUrl: String
)

/** RabbitMQ connection configuration */
case class RabbitMQConfig(
    host: String,
    port: Int,
    virtualHost: String,
    username: String,
    password: String,
    connectionTimeout: FiniteDuration,
    requestedHeartbeat: FiniteDuration,
    automaticRecovery: Boolean
)

/** Queue configuration for request and response queues */
case class QueueConfig(
    requestQueue: String,
    responseQueue: String,
    prefetchCount: Int,
    durable: Boolean,
    autoDelete: Boolean
)

case class RedisConfig(
    host: String,
    port: Int,
    enabled: Boolean
)

/** Complete adapter configuration */
case class AdapterConfig(
    http: HttpConfig,
    rabbitmq: RabbitMQConfig,
    queue: QueueConfig,
    redis: RedisConfig,
    logLevel: String,
    enableMetrics: Boolean
)

object Config {

  /** Load configuration from environment variables */
  def load: IO[AdapterConfig] = IO {
    val httpConfig = HttpConfig(
      host = sys.env.getOrElse("HTTP_HOST", "0.0.0.0"),
      port = sys.env.getOrElse("HTTP_PORT", "8090").toInt,
      enabled = sys.env.getOrElse("HTTP_ENABLED", "true").toBoolean,
      apiExplorerUrl =
        sys.env.getOrElse("API_EXPLORER_URL", "http://localhost:5173"),
      obpApiUrl = sys.env.getOrElse("OBP_API_URL", "http://localhost:8080")
    )

    val rabbitmqConfig = RabbitMQConfig(
      host = sys.env.getOrElse("RABBITMQ_HOST", "localhost"),
      port = sys.env.getOrElse("RABBITMQ_PORT", "5672").toInt,
      virtualHost = sys.env.getOrElse("RABBITMQ_VIRTUAL_HOST", "/"),
      username = sys.env.getOrElse("RABBITMQ_USERNAME", "guest"),
      password = sys.env.getOrElse("RABBITMQ_PASSWORD", "guest"),
      connectionTimeout =
        sys.env.getOrElse("RABBITMQ_CONNECTION_TIMEOUT", "30").toInt.seconds,
      requestedHeartbeat =
        sys.env.getOrElse("RABBITMQ_HEARTBEAT", "60").toInt.seconds,
      automaticRecovery =
        sys.env.getOrElse("RABBITMQ_AUTOMATIC_RECOVERY", "true").toBoolean
    )

    val queueConfig = QueueConfig(
      requestQueue = sys.env.getOrElse("RABBITMQ_REQUEST_QUEUE", "obp.request"),
      responseQueue =
        sys.env.getOrElse("RABBITMQ_RESPONSE_QUEUE", "obp.response"),
      prefetchCount = sys.env.getOrElse("RABBITMQ_PREFETCH_COUNT", "10").toInt,
      durable = sys.env.getOrElse("RABBITMQ_QUEUE_DURABLE", "true").toBoolean,
      autoDelete =
        sys.env.getOrElse("RABBITMQ_QUEUE_AUTO_DELETE", "false").toBoolean
    )

    val redisConfig = RedisConfig(
      host = sys.env.getOrElse("REDIS_HOST", "localhost"),
      port = sys.env.getOrElse("REDIS_PORT", "6379").toInt,
      enabled = sys.env.getOrElse("REDIS_ENABLED", "true").toBoolean
    )

    AdapterConfig(
      http = httpConfig,
      rabbitmq = rabbitmqConfig,
      queue = queueConfig,
      redis = redisConfig,
      logLevel = sys.env.getOrElse("LOG_LEVEL", "INFO"),
      enableMetrics = sys.env.getOrElse("ENABLE_METRICS", "true").toBoolean
    )
  }

  /** Validate configuration */
  def validate(config: AdapterConfig): IO[Unit] = IO {
    require(
      config.http.port > 0 && config.http.port < 65536,
      "HTTP port must be between 1 and 65535"
    )
    require(config.rabbitmq.host.nonEmpty, "RabbitMQ host must not be empty")
    require(
      config.rabbitmq.port > 0 && config.rabbitmq.port < 65536,
      "RabbitMQ port must be between 1 and 65535"
    )
    require(
      config.queue.requestQueue.nonEmpty,
      "Request queue name must not be empty"
    )
    require(
      config.queue.responseQueue.nonEmpty,
      "Response queue name must not be empty"
    )
  }
}
