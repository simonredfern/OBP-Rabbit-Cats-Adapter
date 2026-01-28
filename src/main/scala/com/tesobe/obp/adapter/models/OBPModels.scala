/*
 * Copyright (c) 2025 TESOBE
 *
 * This file is part of OBP-Rabbit-Cats-Adapter.
 *
 * OBP-Rabbit-Cats-Adapter is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License, Version 2.0.
 */

package com.tesobe.obp.adapter.models

import io.circe._
import io.circe.generic.semiauto._

/**
 * OBP RabbitMQ Message Protocol Models
 *
 * Based on OBP Message Docs: /obp/v6.0.0/message-docs/rabbitmq_vOct2024
 *
 * This file contains ONLY the RabbitMQ message envelope format.
 * The actual data payloads are handled as JsonObject to match message docs exactly.
 */

// ==================== OUTBOUND (OBP → Adapter) ====================

/**
 * Message received from OBP-API via RabbitMQ
 */
case class OutboundMessage(
  outboundAdapterCallContext: OutboundAdapterCallContext,
)

object OutboundMessage {
  implicit val decoder: Decoder[OutboundMessage] = deriveDecoder
  implicit val encoder: Encoder[OutboundMessage] = deriveEncoder
}

/**
 * Context information for outbound messages
 * Note: sessionId is optional as it may not always be present in OBP messages
 */
case class OutboundAdapterCallContext(
  correlationId: String,
  sessionId: Option[String],
  consumerId: Option[String],
  generalContext: Option[List[KeyValue]],
  outboundAdapterAuthInfo: Option[OutboundAdapterAuthInfo],
  outboundAdapterConsenterInfo: Option[OutboundAdapterConsenterInfo]
)

object OutboundAdapterCallContext {
  implicit val decoder: Decoder[OutboundAdapterCallContext] = deriveDecoder
  implicit val encoder: Encoder[OutboundAdapterCallContext] = deriveEncoder
}

/**
 * Authentication information from OBP
 */
case class OutboundAdapterAuthInfo(
  userId: Option[String],
  username: Option[String],
  linkedCustomers: Option[List[LinkedCustomer]],
  userAuthContext: Option[List[KeyValue]],
  authViews: Option[List[AuthView]]
)

object OutboundAdapterAuthInfo {
  implicit val decoder: Decoder[OutboundAdapterAuthInfo] = deriveDecoder
  implicit val encoder: Encoder[OutboundAdapterAuthInfo] = deriveEncoder
}

/**
 * Consenter information (for consent-based access)
 */
case class OutboundAdapterConsenterInfo(
  userId: Option[String],
  username: Option[String],
  linkedCustomers: Option[List[LinkedCustomer]],
  userAuthContext: Option[List[KeyValue]],
  authViews: Option[List[AuthView]]
)

object OutboundAdapterConsenterInfo {
  implicit val decoder: Decoder[OutboundAdapterConsenterInfo] = deriveDecoder
  implicit val encoder: Encoder[OutboundAdapterConsenterInfo] = deriveEncoder
}

/**
 * Linked customer information
 */
case class LinkedCustomer(
  customerId: String,
  customerNumber: String,
  legalName: String
)

object LinkedCustomer {
  implicit val decoder: Decoder[LinkedCustomer] = deriveDecoder
  implicit val encoder: Encoder[LinkedCustomer] = deriveEncoder
}

/**
 * Authorization view
 */
case class AuthView(
  view: View,
  account: Account
)

object AuthView {
  implicit val decoder: Decoder[AuthView] = deriveDecoder
  implicit val encoder: Encoder[AuthView] = deriveEncoder
}

case class View(
  id: String,
  name: String,
  description: String
)

object View {
  implicit val decoder: Decoder[View] = deriveDecoder
  implicit val encoder: Encoder[View] = deriveEncoder
}

case class Account(
  id: String,
  accountRoutings: List[AccountRouting],
  customerOwners: List[CustomerOwner],
  userOwners: List[UserOwner]
)

object Account {
  implicit val decoder: Decoder[Account] = deriveDecoder
  implicit val encoder: Encoder[Account] = deriveEncoder
}

case class AccountRouting(
  scheme: String,
  address: String
)

object AccountRouting {
  implicit val decoder: Decoder[AccountRouting] = deriveDecoder
  implicit val encoder: Encoder[AccountRouting] = deriveEncoder
}

case class CustomerOwner(
  bankId: String,
  customerId: String,
  customerNumber: String,
  legalName: String,
  dateOfBirth: Option[String]
)

object CustomerOwner {
  implicit val decoder: Decoder[CustomerOwner] = deriveDecoder
  implicit val encoder: Encoder[CustomerOwner] = deriveEncoder
}

case class UserOwner(
  userId: String,
  emailAddress: String,
  name: String
)

object UserOwner {
  implicit val decoder: Decoder[UserOwner] = deriveDecoder
  implicit val encoder: Encoder[UserOwner] = deriveEncoder
}

/**
 * Generic key-value pair for context
 */
case class KeyValue(
  key: String,
  value: String
)

object KeyValue {
  implicit val decoder: Decoder[KeyValue] = deriveDecoder
  implicit val encoder: Encoder[KeyValue] = deriveEncoder
}

// ==================== INBOUND (Adapter → OBP) ====================

/**
 * Response message sent back to OBP-API via RabbitMQ
 * Matches OBP's InBoundTrait structure
 */
case class InboundMessage(
  inboundAdapterCallContext: InboundAdapterCallContext,
  status: Status,
  data: Option[JsonObject]
)

object InboundMessage {
  implicit val decoder: Decoder[InboundMessage] = deriveDecoder
  implicit val encoder: Encoder[InboundMessage] = deriveEncoder

  /**
   * Create success response
   */
  def success(
    correlationId: String,
    sessionId: Option[String],
    data: JsonObject,
    generalContext: List[KeyValue] = Nil,
    backendMessages: List[BackendMessage] = Nil
  ): InboundMessage = InboundMessage(
    inboundAdapterCallContext = InboundAdapterCallContext(
      correlationId = correlationId,
      sessionId = sessionId,
      generalContext = if (generalContext.isEmpty) None else Some(generalContext)
    ),
    status = Status(
      errorCode = "",
      backendMessages = if (backendMessages.isEmpty) None else Some(backendMessages)
    ),
    data = Some(data)
  )

  /**
   * Create error response
   */
  def error(
    correlationId: String,
    sessionId: Option[String],
    errorCode: String,
    errorMessage: String,
    generalContext: List[KeyValue] = Nil,
    backendMessages: List[BackendMessage] = Nil
  ): InboundMessage = InboundMessage(
    inboundAdapterCallContext = InboundAdapterCallContext(
      correlationId = correlationId,
      sessionId = sessionId,
      generalContext = if (generalContext.isEmpty) None else Some(generalContext)
    ),
    status = Status(
      errorCode = errorCode,
      backendMessages = Some(
        BackendMessage(
          source = "adapter",
          status = "error",
          errorCode = errorCode,
          text = errorMessage,
          duration = None
        ) :: backendMessages
      )
    ),
    data = None
  )
}

/**
 * Context for inbound messages - matches OBP's InboundAdapterCallContext
 */
case class InboundAdapterCallContext(
  correlationId: String,
  sessionId: Option[String],
  generalContext: Option[List[KeyValue]]
)

object InboundAdapterCallContext {
  implicit val decoder: Decoder[InboundAdapterCallContext] = deriveDecoder
  implicit val encoder: Encoder[InboundAdapterCallContext] = deriveEncoder
}

/**
 * Status information in response
 */
case class Status(
  errorCode: String,
  backendMessages: Option[List[BackendMessage]]
)

object Status {
  implicit val decoder: Decoder[Status] = deriveDecoder
  implicit val encoder: Encoder[Status] = deriveEncoder
}

/**
 * Backend diagnostic message
 */
case class BackendMessage(
  source: String,
  status: String,
  errorCode: String,
  text: String,
  duration: Option[String]
)

object BackendMessage {
  implicit val decoder: Decoder[BackendMessage] = deriveDecoder
  implicit val encoder: Encoder[BackendMessage] = deriveEncoder
}

// ==================== CALL CONTEXT (Simplified) ====================

/**
 * Simplified context passed to CBS connector
 * Contains essential information needed for CBS calls
 */
case class CallContext(
  correlationId: String,
  sessionId: Option[String],
  userId: Option[String],
  username: Option[String],
  consumerId: Option[String],
  generalContext: Map[String, String]
)

object CallContext {

  /**
   * Extract CallContext from OutboundMessage
   */
  def fromOutbound(msg: OutboundMessage): CallContext = {
    val ctx = msg.outboundAdapterCallContext
    CallContext(
      correlationId = ctx.correlationId,
      sessionId = ctx.sessionId,
      userId = ctx.outboundAdapterAuthInfo.flatMap(_.userId),
      username = ctx.outboundAdapterAuthInfo.flatMap(_.username),
      consumerId = ctx.consumerId,
      generalContext = ctx.generalContext
        .getOrElse(Nil)
        .map(kv => kv.key -> kv.value)
        .toMap
    )
  }
}
