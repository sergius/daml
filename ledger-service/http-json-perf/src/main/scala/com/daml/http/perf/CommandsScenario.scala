package com.daml.http.perf

import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.Future

object CommandsScenario extends StrictLogging {

  def run(config: Config): Future[Unit] = Future.successful {
    logger.debug(s"Started scenario: $config")
    ()
  }

}
