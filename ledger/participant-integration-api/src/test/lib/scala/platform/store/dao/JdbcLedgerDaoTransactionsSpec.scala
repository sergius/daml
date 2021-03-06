// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.dao

import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import com.daml.ledger.participant.state.v1.Offset
import com.daml.lf.data.Ref.{Identifier, Party}
import com.daml.lf.transaction.Node.{NodeCreate, NodeExercises}
import com.daml.lf.transaction.NodeId
import com.daml.lf.value.Value.ContractId
import com.daml.ledger.EventId
import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.api.v1.transaction_service.GetTransactionsResponse
import com.daml.logging.LoggingContext
import com.daml.platform.ApiOffset
import com.daml.platform.api.v1.event.EventOps.EventOps
import com.daml.platform.store.entries.LedgerEntry
import org.scalatest.{AsyncFlatSpec, Inside, LoneElement, Matchers, OptionValues}

import scala.concurrent.Future

private[dao] trait JdbcLedgerDaoTransactionsSpec extends OptionValues with Inside with LoneElement {
  this: AsyncFlatSpec with Matchers with JdbcLedgerDaoSuite =>

  behavior of "JdbcLedgerDao (lookupFlatTransactionById)"

  it should "return nothing for a mismatching transaction id" in {
    for {
      (_, tx) <- store(singleCreate)
      result <- ledgerDao.transactionsReader
        .lookupFlatTransactionById(transactionId = "WRONG", Set(tx.submittingParty.get))
    } yield {
      result shouldBe None
    }
  }

  it should "return nothing for a mismatching party" in {
    for {
      (_, tx) <- store(singleCreate)
      result <- ledgerDao.transactionsReader
        .lookupFlatTransactionById(tx.transactionId, Set("WRONG"))
    } yield {
      result shouldBe None
    }
  }

  it should "return the expected flat transaction for a correct request (create)" in {
    for {
      (offset, tx) <- store(singleCreate)
      result <- ledgerDao.transactionsReader
        .lookupFlatTransactionById(tx.transactionId, Set(tx.submittingParty.get))
    } yield {
      inside(result.value.transaction) {
        case Some(transaction) =>
          transaction.commandId shouldBe tx.commandId.get
          transaction.offset shouldBe ApiOffset.toApiString(offset)
          transaction.effectiveAt.value.seconds shouldBe tx.ledgerEffectiveTime.getEpochSecond
          transaction.effectiveAt.value.nanos shouldBe tx.ledgerEffectiveTime.getNano
          transaction.transactionId shouldBe tx.transactionId
          transaction.workflowId shouldBe tx.workflowId.getOrElse("")
          inside(transaction.events.loneElement.event.created) {
            case Some(created) =>
              val (nodeId, createNode: NodeCreate.WithTxValue[ContractId]) =
                tx.transaction.nodes.head
              created.eventId shouldBe EventId(tx.transactionId, nodeId).toLedgerString
              created.witnessParties should contain only tx.submittingParty.get
              created.agreementText.getOrElse("") shouldBe createNode.coinst.agreementText
              created.contractKey shouldBe None
              created.createArguments shouldNot be(None)
              created.signatories should contain theSameElementsAs createNode.signatories
              created.observers should contain theSameElementsAs createNode.stakeholders.diff(
                createNode.signatories)
              created.templateId shouldNot be(None)
          }
      }
    }
  }

  it should "return the expected flat transaction for a correct request (exercise)" in {
    for {
      (_, create) <- store(singleCreate)
      (offset, exercise) <- store(singleExercise(nonTransient(create).loneElement))
      result <- ledgerDao.transactionsReader
        .lookupFlatTransactionById(exercise.transactionId, Set(exercise.submittingParty.get))
    } yield {
      inside(result.value.transaction) {
        case Some(transaction) =>
          transaction.commandId shouldBe exercise.commandId.get
          transaction.offset shouldBe ApiOffset.toApiString(offset)
          transaction.transactionId shouldBe exercise.transactionId
          transaction.effectiveAt.value.seconds shouldBe exercise.ledgerEffectiveTime.getEpochSecond
          transaction.effectiveAt.value.nanos shouldBe exercise.ledgerEffectiveTime.getNano
          transaction.workflowId shouldBe exercise.workflowId.getOrElse("")
          inside(transaction.events.loneElement.event.archived) {
            case Some(archived) =>
              val (nodeId, exerciseNode: NodeExercises.WithTxValue[NodeId, ContractId]) =
                exercise.transaction.nodes.head
              archived.eventId shouldBe EventId(transaction.transactionId, nodeId).toLedgerString
              archived.witnessParties should contain only exercise.submittingParty.get
              archived.contractId shouldBe exerciseNode.targetCoid.coid
              archived.templateId shouldNot be(None)
          }
      }
    }
  }

  it should "hide events on transient contracts to the original submitter" in {
    for {
      (offset, tx) <- store(fullyTransient)
      result <- ledgerDao.transactionsReader
        .lookupFlatTransactionById(tx.transactionId, Set(tx.submittingParty.get))
    } yield {
      inside(result.value.transaction) {
        case Some(transaction) =>
          transaction.commandId shouldBe tx.commandId.get
          transaction.offset shouldBe ApiOffset.toApiString(offset)
          transaction.transactionId shouldBe tx.transactionId
          transaction.effectiveAt.value.seconds shouldBe tx.ledgerEffectiveTime.getEpochSecond
          transaction.effectiveAt.value.nanos shouldBe tx.ledgerEffectiveTime.getNano
          transaction.workflowId shouldBe tx.workflowId.getOrElse("")
          transaction.events shouldBe Seq.empty
      }
    }
  }

  behavior of "JdbcLedgerDao (getFlatTransactions)"

  it should "match the results of lookupFlatTransactionById" in {
    for {
      (from, to, transactions) <- storeTestFixture()
      lookups <- lookupIndividually(transactions, Set(alice, bob, charlie))
      result <- transactionsOf(
        ledgerDao.transactionsReader
          .getFlatTransactions(
            startExclusive = from,
            endInclusive = to,
            filter = Map(alice -> Set.empty, bob -> Set.empty, charlie -> Set.empty),
            verbose = true,
          ))
    } yield {
      comparable(result) should contain theSameElementsInOrderAs comparable(lookups)
    }
  }

  it should "filter correctly by party" in {
    for {
      from <- ledgerDao.lookupLedgerEnd()
      (_, tx) <- store(multipleCreates(charlie, Seq(alice -> "foo:bar:baz", bob -> "foo:bar:baz")))
      to <- ledgerDao.lookupLedgerEnd()
      individualLookupForAlice <- lookupIndividually(Seq(tx), as = Set(alice))
      individualLookupForBob <- lookupIndividually(Seq(tx), as = Set(bob))
      individualLookupForCharlie <- lookupIndividually(Seq(tx), as = Set(charlie))
      resultForAlice <- transactionsOf(
        ledgerDao.transactionsReader
          .getFlatTransactions(
            startExclusive = from,
            endInclusive = to,
            filter = Map(alice -> Set.empty),
            verbose = true,
          ))
      resultForBob <- transactionsOf(
        ledgerDao.transactionsReader
          .getFlatTransactions(
            startExclusive = from,
            endInclusive = to,
            filter = Map(bob -> Set.empty),
            verbose = true,
          ))
      resultForCharlie <- transactionsOf(
        ledgerDao.transactionsReader
          .getFlatTransactions(
            startExclusive = from,
            endInclusive = to,
            filter = Map(charlie -> Set.empty),
            verbose = true,
          ))
    } yield {
      individualLookupForAlice should contain theSameElementsInOrderAs resultForAlice
      individualLookupForBob should contain theSameElementsInOrderAs resultForBob
      individualLookupForCharlie should contain theSameElementsInOrderAs resultForCharlie
    }
  }

  it should "filter correctly for a single party" in {
    for {
      from <- ledgerDao.lookupLedgerEnd()
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            alice -> "pkg:mod:Template1",
            bob -> "pkg:mod:Template3",
            alice -> "pkg:mod:Template3",
          )
        ))
      to <- ledgerDao.lookupLedgerEnd()
      result <- transactionsOf(
        ledgerDao.transactionsReader
          .getFlatTransactions(
            startExclusive = from,
            endInclusive = to,
            filter = Map(alice -> Set(Identifier.assertFromString("pkg:mod:Template3"))),
            verbose = true,
          ))
    } yield {
      inside(result.loneElement.events.loneElement.event.created) {
        case Some(create) =>
          create.witnessParties.loneElement shouldBe alice
          val identifier = create.templateId.value
          identifier.packageId shouldBe "pkg"
          identifier.moduleName shouldBe "mod"
          identifier.entityName shouldBe "Template3"
      }
    }
  }

  it should "filter correctly by multiple parties with the same template" in {
    for {
      from <- ledgerDao.lookupLedgerEnd()
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            alice -> "pkg:mod:Template1",
            bob -> "pkg:mod:Template3",
            alice -> "pkg:mod:Template3",
          )
        ))
      to <- ledgerDao.lookupLedgerEnd()
      result <- transactionsOf(
        ledgerDao.transactionsReader
          .getFlatTransactions(
            startExclusive = from,
            endInclusive = to,
            filter = Map(
              alice -> Set(
                Identifier.assertFromString("pkg:mod:Template3"),
              ),
              bob -> Set(
                Identifier.assertFromString("pkg:mod:Template3"),
              )
            ),
            verbose = true,
          ))
    } yield {
      val events = result.loneElement.events.toArray
      events should have length 2
      inside(events(0).event.created) {
        case Some(create) =>
          create.witnessParties.loneElement shouldBe bob
          val identifier = create.templateId.value
          identifier.packageId shouldBe "pkg"
          identifier.moduleName shouldBe "mod"
          identifier.entityName shouldBe "Template3"
      }
      inside(events(1).event.created) {
        case Some(create) =>
          create.witnessParties.loneElement shouldBe alice
          val identifier = create.templateId.value
          identifier.packageId shouldBe "pkg"
          identifier.moduleName shouldBe "mod"
          identifier.entityName shouldBe "Template3"
      }
    }
  }

  it should "filter correctly by multiple parties with different templates" in {
    for {
      from <- ledgerDao.lookupLedgerEnd()
      _ <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            alice -> "pkg:mod:Template1",
            bob -> "pkg:mod:Template3",
            alice -> "pkg:mod:Template3",
          )
        ))
      to <- ledgerDao.lookupLedgerEnd()
      result <- transactionsOf(
        ledgerDao.transactionsReader
          .getFlatTransactions(
            startExclusive = from,
            endInclusive = to,
            filter = Map(
              alice -> Set(
                Identifier.assertFromString("pkg:mod:Template1"),
              ),
              bob -> Set(
                Identifier.assertFromString("pkg:mod:Template3"),
              )
            ),
            verbose = true,
          ))
    } yield {
      val events = result.loneElement.events.toArray
      events should have length 2
      inside(events(0).event.created) {
        case Some(create) =>
          create.witnessParties.loneElement shouldBe alice
          val identifier = create.templateId.value
          identifier.packageId shouldBe "pkg"
          identifier.moduleName shouldBe "mod"
          identifier.entityName shouldBe "Template1"
      }
      inside(events(1).event.created) {
        case Some(create) =>
          create.witnessParties.loneElement shouldBe bob
          val identifier = create.templateId.value
          identifier.packageId shouldBe "pkg"
          identifier.moduleName shouldBe "mod"
          identifier.entityName shouldBe "Template3"
      }
    }
  }

  it should "filter correctly by multiple parties with different template and wildcards" in {
    for {
      from <- ledgerDao.lookupLedgerEnd()
      (_, _) <- store(
        multipleCreates(
          operator = "operator",
          signatoriesAndTemplates = Seq(
            alice -> "pkg:mod:Template1",
            bob -> "pkg:mod:Template3",
            alice -> "pkg:mod:Template3",
          )
        ))
      to <- ledgerDao.lookupLedgerEnd()
      result <- transactionsOf(
        ledgerDao.transactionsReader
          .getFlatTransactions(
            startExclusive = from,
            endInclusive = to,
            filter = Map(
              alice -> Set(
                Identifier.assertFromString("pkg:mod:Template3"),
              ),
              bob -> Set.empty
            ),
            verbose = true,
          ))
    } yield {
      val events = result.loneElement.events.toArray
      events should have length 2
      inside(events(0).event.created) {
        case Some(create) =>
          create.witnessParties.loneElement shouldBe bob
          val identifier = create.templateId.value
          identifier.packageId shouldBe "pkg"
          identifier.moduleName shouldBe "mod"
          identifier.entityName shouldBe "Template3"
      }
      inside(events(1).event.created) {
        case Some(create) =>
          create.witnessParties.loneElement shouldBe alice
          val identifier = create.templateId.value
          identifier.packageId shouldBe "pkg"
          identifier.moduleName shouldBe "mod"
          identifier.entityName shouldBe "Template3"
      }
    }
  }

  it should "return all events in the expected order" in {
    for {
      from <- ledgerDao.lookupLedgerEnd()
      (_, create) <- store(singleCreate)
      firstContractId = nonTransient(create).loneElement
      (offset, exercise) <- store(exerciseWithChild(firstContractId))
      result <- ledgerDao.transactionsReader
        .getFlatTransactions(
          from,
          offset,
          Map(exercise.submittingParty.get -> Set.empty[Identifier]),
          verbose = true)
        .runWith(Sink.seq)
    } yield {
      import com.daml.ledger.api.v1.event.Event
      import com.daml.ledger.api.v1.event.Event.Event.{Created, Archived}

      val txs = extractAllTransactions(result)

      inside(txs) {
        case Vector(tx1, tx2) =>
          tx1.transactionId shouldBe create.transactionId
          tx2.transactionId shouldBe exercise.transactionId
          inside(tx1.events) {
            case Seq(Event(Created(createdEvent))) =>
              createdEvent.contractId shouldBe firstContractId.coid
          }
          inside(tx2.events) {
            case Seq(Event(Archived(archivedEvent)), Event(Created(_))) =>
              archivedEvent.contractId shouldBe firstContractId.coid
          }
      }
    }
  }

  it should "return the expected flat transaction for the specified offset range" in {
    for {
      (_, create1) <- store(singleCreate)
      (offset1, exercise) <- store(singleExercise(nonTransient(create1).loneElement))
      (offset2, create2) <- store(singleCreate)
      result <- ledgerDao.transactionsReader
        .getFlatTransactions(
          offset1,
          offset2,
          Map(exercise.submittingParty.get -> Set.empty[Identifier]),
          verbose = true)
        .runWith(Sink.seq)

    } yield {
      import com.daml.ledger.api.v1.event.Event
      import com.daml.ledger.api.v1.event.Event.Event.Created

      inside(extractAllTransactions(result)) {
        case Vector(tx) =>
          tx.transactionId shouldBe create2.transactionId
          inside(tx.events) {
            case Seq(Event(Created(createdEvent))) =>
              createdEvent.contractId shouldBe nonTransient(create2).loneElement.coid
          }
      }
    }
  }

  it should "return empty source when offset range is from the future" in {
    val commands: Vector[(Offset, LedgerEntry.Transaction)] = Vector.fill(3)(singleCreate)
    val beginOffsetFromTheFuture = nextOffset()
    val endOffsetFromTheFuture = nextOffset()

    for {
      _ <- storeSync(commands)

      result <- ledgerDao.transactionsReader
        .getFlatTransactions(
          beginOffsetFromTheFuture,
          endOffsetFromTheFuture,
          Map(alice -> Set.empty[Identifier]),
          verbose = true)
        .runWith(Sink.seq)

    } yield {
      extractAllTransactions(result) shouldBe empty
    }
  }

  // TODO(Leo): this should be converted to scalacheck test with random offset gaps and pageSize
  it should "return all transactions in the specified offset range when iterating with gaps in the offsets assigned to events and a page size that ensures a page ends in such a gap" in {
    // Simulates a gap in the offsets assigned to events, as they
    // can be assigned to party allocation, package uploads and
    // configuration updates as well
    def offsetGap(): Vector[(Offset, LedgerEntry.Transaction)] = {
      nextOffset()
      Vector.empty[(Offset, LedgerEntry.Transaction)]
    }

    // the order of `nextOffset()` calls is important
    val beginOffset = nextOffset()

    val commandsWithOffsetGaps: Vector[(Offset, LedgerEntry.Transaction)] =
      Vector(singleCreate) ++ offsetGap ++
        Vector.fill(2)(singleCreate) ++ offsetGap ++
        Vector.fill(3)(singleCreate) ++ offsetGap ++ offsetGap ++
        Vector.fill(5)(singleCreate)

    val endOffset = nextOffset()

    commandsWithOffsetGaps should have length 11L

    for {
      _ <- storeSync(commandsWithOffsetGaps)

      // `pageSize = 2` and the offset gaps in the `commandWithOffsetGaps` above are to make sure
      // that streaming works with event pages separated by offsets that don't have events in the store
      ledgerDao <- createLedgerDao(pageSize = 2)

      response <- ledgerDao.transactionsReader
        .getFlatTransactions(
          beginOffset,
          endOffset,
          Map(alice -> Set.empty[Identifier]),
          verbose = true)
        .runWith(Sink.seq)

      readTxs = extractAllTransactions(response)
    } yield {
      val readTxOffsets: Vector[String] = readTxs.map(_.offset)
      readTxOffsets shouldBe readTxOffsets.sorted
      readTxOffsets shouldBe commandsWithOffsetGaps.map(_._1.toHexString)
    }
  }

  private def storeTestFixture(): Future[(Offset, Offset, Seq[LedgerEntry.Transaction])] =
    for {
      from <- ledgerDao.lookupLedgerEnd()
      (_, t1) <- store(singleCreate)
      (_, t2) <- store(singleCreate)
      (_, t3) <- store(singleExercise(nonTransient(t2).loneElement))
      (_, t4) <- store(fullyTransient)
      to <- ledgerDao.lookupLedgerEnd()
    } yield (from, to, Seq(t1, t2, t3, t4))

  private def lookupIndividually(
      transactions: Seq[LedgerEntry.Transaction],
      as: Set[Party],
  ): Future[Seq[Transaction]] =
    Future
      .sequence(
        transactions.map(tx =>
          ledgerDao.transactionsReader
            .lookupFlatTransactionById(tx.transactionId, as)))
      .map(_.flatMap(_.toList.flatMap(_.transaction.toList)))

  private def transactionsOf(
      source: Source[(Offset, GetTransactionsResponse), NotUsed],
  ): Future[Seq[Transaction]] =
    source
      .map(_._2)
      .runWith(Sink.seq)
      .map(_.flatMap(_.transactions))

  // Ensure two sequences of transactions are comparable:
  // - witnesses do not have to appear in a specific order
  private def comparable(txs: Seq[Transaction]): Seq[Transaction] =
    txs.map(tx => tx.copy(events = tx.events.map(_.modifyWitnessParties(_.sorted))))

  private def extractAllTransactions(
      responses: Seq[(Offset, GetTransactionsResponse)]): Vector[Transaction] =
    responses.foldLeft(Vector.empty[Transaction])((b, a) => b ++ a._2.transactions.toVector)

  private def createLedgerDao(pageSize: Int) =
    LoggingContext.newLoggingContext { implicit logCtx =>
      daoOwner(eventsPageSize = 2).acquire()
    }.asFuture
}
