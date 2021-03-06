// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.testtool

import java.nio.file.Path

import com.daml.ledger.api.testtool
import com.daml.ledger.api.testtool.infrastructure.{BenchmarkReporter, LedgerTestSuite}
import com.daml.ledger.api.testtool.tests._
import org.slf4j.LoggerFactory

object Tests {

  val all: Vector[LedgerTestSuite] =
    Vector(
      new ActiveContractsServiceIT,
      new ClosedWorldIT,
      new CommandServiceIT,
      new CommandSubmissionCompletionIT,
      new CommandDeduplicationIT,
      new ConfigManagementServiceIT,
      new ContractKeysIT,
      new DivulgenceIT,
      new HealthServiceIT,
      new IdentityIT,
      new LedgerConfigurationServiceIT,
      new PackageManagementServiceIT,
      new PackageServiceIT,
      new PartyManagementServiceIT,
      new SemanticTests,
      new TransactionServiceIT,
      new WitnessesIT,
      new WronglyTypedContractIdIT,
    )

  val retired: Vector[LedgerTestSuite] =
    Vector(
      new LotsOfPartiesIT,
      new TransactionScaleIT,
    )

  /**
    * These are performance envelope tests that also provide benchmarks and are always run
    * sequentially; they also must be specified explicitly with --perf-tests and will exclude
    * all other tests.
    */
  def performanceTests(path: Option[Path]): Map[String, LedgerTestSuite] = {
    val reporter =
      (key: String, value: Double) =>
        path
          .map(BenchmarkReporter.toFile)
          .getOrElse(BenchmarkReporter.toStream(System.out))
          .addReport(key, value)

    Envelope.values.flatMap { envelope =>
      {
        val throughputKey: String = performanceEnvelopeThroughputTestKey(envelope)
        val latencyKey: String = performanceEnvelopeLatencyTestKey(envelope)
        val transactionSizeKey: String = performanceEnvelopeTransactionSizeTestKey(envelope)
        List(
          throughputKey -> new testtool.tests.PerformanceEnvelope.ThroughputTest(
            logger = LoggerFactory.getLogger(throughputKey),
            envelope = envelope,
            reporter = reporter,
          ),
          latencyKey -> new testtool.tests.PerformanceEnvelope.LatencyTest(
            logger = LoggerFactory.getLogger(latencyKey),
            envelope = envelope,
            reporter = reporter,
          ),
          transactionSizeKey -> new testtool.tests.PerformanceEnvelope.TransactionSizeScaleTest(
            logger = LoggerFactory.getLogger(transactionSizeKey),
            envelope = envelope,
          ),
        )
      }
    }
  }.toMap

  private[this] def performanceEnvelopeThroughputTestKey(envelope: Envelope): String =
    s"PerformanceEnvelope.${envelope.name}.Throughput"
  private[this] def performanceEnvelopeLatencyTestKey(envelope: Envelope): String =
    s"PerformanceEnvelope.${envelope.name}.Latency"
  private[this] def performanceEnvelopeTransactionSizeTestKey(envelope: Envelope): String =
    s"PerformanceEnvelope.${envelope.name}.TransactionSize"

  private[testtool] val PerformanceTestsKeys =
    Envelope.values.flatMap { envelope =>
      List(
        performanceEnvelopeThroughputTestKey(envelope),
        performanceEnvelopeLatencyTestKey(envelope),
        performanceEnvelopeTransactionSizeTestKey(envelope)),
    }

  private[testtool] val PerformanceTestsKeySet = PerformanceTestsKeys.toSet
}
