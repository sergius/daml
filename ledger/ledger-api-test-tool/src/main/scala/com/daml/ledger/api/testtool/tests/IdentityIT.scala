// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.testtool.tests

import com.daml.ledger.api.testtool.infrastructure.Allocation._
import com.daml.ledger.api.testtool.infrastructure.LedgerTestSuite

import scala.concurrent.{ExecutionContext, Future}

final class IdentityIT extends LedgerTestSuite() {
  test(
    "IdNotEmpty",
    "A ledger should return a non-empty string as its identity",
    allocate(NoParties),
  ) {
    case (Participants(Participant(ledger)), ec) =>
      implicit val e: ExecutionContext = ec
      Future {
        assert(ledger.ledgerId.nonEmpty, "The returned ledger identifier was empty")
      }
  }
}
