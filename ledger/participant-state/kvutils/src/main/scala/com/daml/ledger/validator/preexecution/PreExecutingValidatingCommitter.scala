// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.validator.preexecution

import java.time.Instant

import akka.stream.Materializer
import com.daml.caching.Cache
import com.daml.ledger.participant.state.kvutils.DamlKvutils.{DamlStateKey, DamlStateValue}
import com.daml.ledger.participant.state.kvutils.{Bytes, Fingerprint}
import com.daml.ledger.participant.state.v1.{ParticipantId, SubmissionResult}
import com.daml.ledger.validator.LedgerStateOperations.Value
import com.daml.ledger.validator.SubmissionValidator.RawKeyValuePairs
import com.daml.ledger.validator.caching.{
  CacheUpdatePolicy,
  CachingDamlLedgerStateReaderWithFingerprints
}
import com.daml.ledger.validator.{
  LedgerStateAccess,
  StateAccessingValidatingCommitter,
  StateKeySerializationStrategy
}
import com.daml.timer.RetryStrategy

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * A pre-executing validating committer based on [[LedgerStateAccess]] (that does not provide fingerprints
  * alongside values), parametric in the logic that produces a fingerprint given a value.
  *
  * @param now The record time provider.
  * @param keySerializationStrategy The key serializer used for state keys.
  * @param validator The pre-execution validator.
  * @param valueToFingerprint The logic that produces a fingerprint given a value.
  * @param postExecutionFinalizer The post-execution finalizer that will also perform conflicts detection and
  *                               time bounds checks.
  * @param stateValueCache The cache instance for state values.
  * @param cacheUpdatePolicy The caching policy for values.
  * @param materializer The Akka materializer.
  * @tparam LogResult type of the offset used for a log entry.
  */
class PreExecutingValidatingCommitter[LogResult](
    now: () => Instant,
    keySerializationStrategy: StateKeySerializationStrategy,
    validator: PreExecutingSubmissionValidator[RawKeyValuePairs],
    valueToFingerprint: Option[Value] => Fingerprint,
    postExecutionFinalizer: PostExecutionFinalizerWithFingerprintsFromValues[LogResult],
    stateValueCache: Cache[DamlStateKey, (DamlStateValue, Fingerprint)],
    cacheUpdatePolicy: CacheUpdatePolicy)(implicit materializer: Materializer)
    extends StateAccessingValidatingCommitter[LogResult] {

  /**
    * Pre-executes and then finalizes a submission.
    */
  override def commit(
      correlationId: String,
      submissionEnvelope: Bytes,
      submittingParticipantId: ParticipantId,
      ledgerStateAccess: LedgerStateAccess[LogResult])(
      implicit executionContext: ExecutionContext): Future[SubmissionResult] =
    for {
      preExecutionOutput <- validator
        .validate(
          submissionEnvelope,
          correlationId,
          submittingParticipantId,
          CachingDamlLedgerStateReaderWithFingerprints(
            stateValueCache,
            cacheUpdatePolicy,
            new LedgerStateReaderWithFingerprintsFromValues(ledgerStateAccess, valueToFingerprint),
            keySerializationStrategy,
          )
        )
      submissionResult <- retry {
        case PostExecutionFinalizerWithFingerprintsFromValues.Conflict => true
      } { (_, _) =>
        postExecutionFinalizer.conflictDetectAndFinalize(now, preExecutionOutput, ledgerStateAccess)
      }.transform {
        case Failure(PostExecutionFinalizerWithFingerprintsFromValues.Conflict) =>
          Success(SubmissionResult.Acknowledged) // But it will simply be dropped.
        case result => result
      }
    } yield submissionResult

  // TODO consider adding to [[RetryStrategy]] a pre-built exponential backoff strategy
  //  with custom exception processing and use it here.
  private[this] def retry: PartialFunction[Throwable, Boolean] => RetryStrategy =
    RetryStrategy.constant(attempts = Some(3), 5.seconds)
}
