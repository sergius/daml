// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.dao

import java.time.Instant

import anorm.{Row, RowParser, SimpleSql, SqlParser, SqlStringInterpolation, ~}
import com.daml.ledger.participant.state.v1.{Offset, SubmitterInfo, TransactionId}
import com.daml.lf.data.Ref
import com.daml.ledger.{ApplicationId, CommandId}
import com.daml.ledger.api.v1.command_completion_service.CompletionStreamResponse
import com.daml.ledger.api.v1.completion.Completion
import com.daml.lf.data.Ref.Party
import com.daml.platform.store.CompletionFromTransaction.toApiCheckpoint
import com.daml.platform.store.Conversions._
import com.google.rpc.status.Status

object CommandCompletionsTable {

  import SqlParser.{date, int, str}

  private val acceptedCommandParser: RowParser[CompletionStreamResponse] =
    offset("completion_offset") ~ date("record_time") ~ str("command_id") ~ str("transaction_id") map {
      case offset ~ recordTime ~ commandId ~ transactionId =>
        CompletionStreamResponse(
          checkpoint = toApiCheckpoint(recordTime.toInstant, offset),
          completions = Seq(Completion(commandId, Some(Status()), transactionId)))
    }

  private val rejectedCommandParser: RowParser[CompletionStreamResponse] =
    offset("completion_offset") ~ date("record_time") ~ str("command_id") ~ int("status_code") ~ str(
      "status_message") map {
      case offset ~ recordTime ~ commandId ~ statusCode ~ statusMessage =>
        CompletionStreamResponse(
          checkpoint = toApiCheckpoint(recordTime.toInstant, offset),
          completions = Seq(Completion(commandId, Some(Status(statusCode, statusMessage)))))
    }

  private val checkpointParser: RowParser[CompletionStreamResponse] =
    offset("completion_offset") ~ date("record_time") map {
      case offset ~ recordTime =>
        CompletionStreamResponse(
          checkpoint = toApiCheckpoint(recordTime.toInstant, offset),
          completions = Seq())
    }

  val parser: RowParser[CompletionStreamResponse] =
    acceptedCommandParser | rejectedCommandParser | checkpointParser

  // TODO The query has to account for checkpoint, which is why it
  // TODO returns rows there the application_id and submitting_party
  // TODO are null. Remove as soon as checkpoints are gone.
  def prepareGet(
      startExclusive: Offset,
      endInclusive: Offset,
      applicationId: ApplicationId,
      parties: Set[Ref.Party]): SimpleSql[Row] =
    SQL"""select
            completion_offset,
            record_time,
            application_id,
            submitting_party,
            command_id,
            transaction_id,
            status_code,
            status_message
          from participant_command_completions
          where
            completion_offset > $startExclusive and completion_offset <= $endInclusive and
            (
              (application_id is null and submitting_party is null)
              or
              (application_id = $applicationId and submitting_party in ($parties))
            )
          order by completion_offset asc"""

  def prepareCompletionInsert(
      submitterInfo: SubmitterInfo,
      offset: Offset,
      transactionId: TransactionId,
      ledgerEffectiveTime: Instant,
  ): SimpleSql[Row] =
    SQL"insert into participant_command_completions(completion_offset, record_time, application_id, submitting_party, command_id, transaction_id) values ($offset, $ledgerEffectiveTime, ${submitterInfo.applicationId}, ${submitterInfo.submitter}, ${submitterInfo.commandId}, $transactionId)"

  def prepareRejectionInsert(
      submitterInfo: SubmitterInfo,
      offset: Offset,
      ledgerEffectiveTime: Instant,
      statusCode: Int,
      statusMessage: String,
  ): SimpleSql[Row] =
    SQL"insert into participant_command_completions(completion_offset, record_time, application_id, submitting_party, command_id, status_code, status_message) values ($offset, $ledgerEffectiveTime, ${submitterInfo.applicationId}, ${submitterInfo.submitter}, ${submitterInfo.commandId}, $statusCode, $statusMessage)"

}
