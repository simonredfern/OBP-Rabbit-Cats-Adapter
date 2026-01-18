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

package com.tesobe.obp.adapter.cbs.implementations

import cats.effect.IO
import com.tesobe.obp.adapter.interfaces._
import com.tesobe.obp.adapter.models._
import com.tesobe.obp.adapter.telemetry.Telemetry
import io.circe._

/**
 * Mock CBS Connector for testing and development.
 * 
 * This demonstrates the JSON-based approach where:
 * 1. We receive JSON data from OBP message
 * 2. Extract fields we need
 * 3. Call CBS (mocked here)
 * 4. Return JSON matching OBP message docs format
 * 
 * Banks should implement similar logic but calling their real CBS API.
 */
class MockCBSConnector(telemetry: Telemetry) extends CBSConnector {

  override def name: String = "Mock-CBS-Connector"
  override def version: String = "1.0.0"

  override def handleMessage(
    process: String,
    data: JsonObject,
    callContext: CallContext
  ): IO[CBSResponse] = {
    
    process match {
      case "obp.getAdapterInfo" => getAdapterInfo(callContext)
      case "obp.getBank" => getBank(data, callContext)
      case "obp.getBankAccount" => getBankAccount(data, callContext)
      case "obp.getTransaction" => getTransaction(data, callContext)
      case "obp.getTransactions" => getTransactions(data, callContext)
      case "obp.checkFundsAvailable" => checkFundsAvailable(data, callContext)
      case "obp.makePayment" => makePayment(data, callContext)
      case _ => handleUnsupported(process, callContext)
    }
  }

  override def checkHealth(callContext: CallContext): IO[CBSResponse] = {
    IO.pure(
      CBSResponse.success(
        JsonObject(
          "status" -> Json.fromString("healthy"),
          "message" -> Json.fromString("Mock CBS is operational"),
          "timestamp" -> Json.fromLong(System.currentTimeMillis())
        )
      )
    )
  }

  override def getAdapterInfo(callContext: CallContext): IO[CBSResponse] = {
    IO.pure(
      CBSResponse.success(
        JsonObject(
          "name" -> Json.fromString("OBP-Rabbit-Cats-Adapter"),
          "version" -> Json.fromString("1.0.0-SNAPSHOT"),
          "description" -> Json.fromString("Functional RabbitMQ adapter for Open Bank Project"),
          "connector" -> Json.fromString(name),
          "connector_version" -> Json.fromString(version),
          "scala_version" -> Json.fromString("2.13.15"),
          "cats_effect_version" -> Json.fromString("3.5.7"),
          "rabbitmq_client" -> Json.fromString("amqp-client 5.20.0"),
          "http_server_port" -> Json.fromInt(8090),
          "repository" -> Json.fromString("https://github.com/OpenBankProject/OBP-Rabbit-Cats-Adapter"),
          "license" -> Json.fromString("Apache License 2.0"),
          "built_with" -> Json.fromString("Scala, Cats Effect, fs2, http4s, Circe")
        )
      )
    )
  }

  // ==================== EXAMPLE IMPLEMENTATIONS ====================

  private def getBank(data: JsonObject, callContext: CallContext): IO[CBSResponse] = {
    // Extract bankId from the data payload
    val bankId = data("bankId").flatMap(_.asString).getOrElse("unknown")
    
    telemetry.debug(s"Getting bank: $bankId", Some(callContext.correlationId)) *>
    IO.pure(
      CBSResponse.success(
        JsonObject(
          "bankId" -> Json.fromString(bankId),
          "shortName" -> Json.fromString("Mock Bank"),
          "fullName" -> Json.fromString("Mock Bank for Testing"),
          "logoUrl" -> Json.fromString("https://static.openbankproject.com/images/sandbox/bank_x.png"),
          "websiteUrl" -> Json.fromString("https://www.example.com")
        )
      )
    )
  }

  private def getBankAccount(data: JsonObject, callContext: CallContext): IO[CBSResponse] = {
    val bankId = data("bankId").flatMap(_.asString).getOrElse("unknown")
    val accountId = data("accountId").flatMap(_.asString).getOrElse("unknown")
    
    telemetry.debug(s"Getting account: $accountId at bank: $bankId", Some(callContext.correlationId)) *>
    IO.pure(
      CBSResponse.success(
        JsonObject(
          "bankId" -> Json.fromString(bankId),
          "accountId" -> Json.fromString(accountId),
          "accountType" -> Json.fromString("CURRENT"),
          "accountRoutings" -> Json.arr(
            Json.obj(
              "scheme" -> Json.fromString("IBAN"),
              "address" -> Json.fromString("GB33BUKB20201555555555")
            )
          ),
          "branchId" -> Json.fromString("branch-123"),
          "label" -> Json.fromString("Mock Checking Account"),
          "currency" -> Json.fromString("EUR"),
          "balance" -> Json.obj(
            "currency" -> Json.fromString("EUR"),
            "amount" -> Json.fromString("1000.50")
          )
        )
      )
    )
  }

  private def getTransaction(data: JsonObject, callContext: CallContext): IO[CBSResponse] = {
    val transactionId = data("transactionId").flatMap(_.asString).getOrElse("unknown")
    
    telemetry.debug(s"Getting transaction: $transactionId", Some(callContext.correlationId)) *>
    IO.pure(
      CBSResponse.success(
        JsonObject(
          "transactionId" -> Json.fromString(transactionId),
          "accountId" -> data("accountId").getOrElse(Json.fromString("account-123")),
          "amount" -> Json.fromString("50.00"),
          "currency" -> Json.fromString("EUR"),
          "description" -> Json.fromString("Mock transaction"),
          "posted" -> Json.fromString("2025-01-14T10:30:00Z"),
          "completed" -> Json.fromString("2025-01-14T10:30:00Z"),
          "newBalance" -> Json.fromString("1000.50"),
          "type" -> Json.fromString("DEBIT")
        )
      )
    )
  }

  private def getTransactions(data: JsonObject, callContext: CallContext): IO[CBSResponse] = {
    val accountId = data("accountId").flatMap(_.asString).getOrElse("unknown")
    
    telemetry.debug(s"Getting transactions for account: $accountId", Some(callContext.correlationId)) *>
    IO.pure(
      CBSResponse.success(
        JsonObject(
          "transactions" -> Json.arr(
            Json.obj(
              "transactionId" -> Json.fromString("tx-001"),
              "accountId" -> Json.fromString(accountId),
              "amount" -> Json.fromString("50.00"),
              "currency" -> Json.fromString("EUR"),
              "description" -> Json.fromString("Payment to merchant"),
              "posted" -> Json.fromString("2025-01-14T10:30:00Z"),
              "type" -> Json.fromString("DEBIT")
            ),
            Json.obj(
              "transactionId" -> Json.fromString("tx-002"),
              "accountId" -> Json.fromString(accountId),
              "amount" -> Json.fromString("100.00"),
              "currency" -> Json.fromString("EUR"),
              "description" -> Json.fromString("Salary payment"),
              "posted" -> Json.fromString("2025-01-13T09:00:00Z"),
              "type" -> Json.fromString("CREDIT")
            )
          )
        )
      )
    )
  }

  private def checkFundsAvailable(data: JsonObject, callContext: CallContext): IO[CBSResponse] = {
    val amount = data("amount").flatMap(_.asString).getOrElse("0")
    val currency = data("currency").flatMap(_.asString).getOrElse("EUR")
    
    telemetry.debug(s"Checking funds: $amount $currency", Some(callContext.correlationId)) *>
    IO.pure(
      CBSResponse.success(
        JsonObject(
          "available" -> Json.fromBoolean(true),
          "amount" -> Json.fromString(amount),
          "currency" -> Json.fromString(currency)
        )
      )
    )
  }

  private def makePayment(data: JsonObject, callContext: CallContext): IO[CBSResponse] = {
    val amount = data("amount").flatMap(_.asString).getOrElse("0")
    val currency = data("currency").flatMap(_.asString).getOrElse("EUR")
    val description = data("description").flatMap(_.asString).getOrElse("Payment")
    
    telemetry.recordPaymentSuccess(
      bankId = "mock-bank",
      amount = BigDecimal(amount),
      currency = currency,
      correlationId = callContext.correlationId
    ) *>
    IO.pure(
      CBSResponse.success(
        JsonObject(
          "transactionId" -> Json.fromString(s"tx-${System.currentTimeMillis()}"),
          "amount" -> Json.fromString(amount),
          "currency" -> Json.fromString(currency),
          "description" -> Json.fromString(description),
          "status" -> Json.fromString("COMPLETED"),
          "posted" -> Json.fromString(java.time.Instant.now().toString)
        ),
        List(
          BackendMessage(
            source = "MockCBS",
            status = "success",
            errorCode = "",
            text = "Payment processed successfully",
            duration = Some("0.050")
          )
        )
      )
    )
  }

  private def handleUnsupported(process: String, callContext: CallContext): IO[CBSResponse] = {
    telemetry.warn(s"Unsupported message type: $process", Some(callContext.correlationId)) *>
    IO.pure(
      CBSResponse.error(
        code = "OBP-50000",
        message = s"Message type not implemented: $process",
        messages = List(
          BackendMessage(
            source = "MockCBS",
            status = "error",
            errorCode = "NOT_IMPLEMENTED",
            text = s"Message type $process is not implemented in MockCBSConnector",
            duration = None
          )
        )
      )
    )
  }
}

object MockCBSConnector {
  def apply(telemetry: Telemetry): MockCBSConnector = new MockCBSConnector(telemetry)
}