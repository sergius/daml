// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.engine.trigger

import java.io.File
import java.net.{InetAddress, ServerSocket, Socket}
import java.time.Duration

import akka.actor.ActorSystem
import akka.actor.typed.{ActorSystem => TypedActorSystem}
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.stream.Materializer
import com.daml.bazeltools.BazelRunfiles
import com.daml.daml_lf_dev.DamlLf
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.jwt.JwksVerifier
import com.daml.ledger.api.auth.AuthServiceJWT
import com.daml.ledger.api.domain.LedgerId
import com.daml.ledger.api.refinements.ApiTypes.ApplicationId
import com.daml.ledger.client.LedgerClient
import com.daml.ledger.client.configuration.{
  CommandClientConfiguration,
  LedgerClientConfiguration,
  LedgerIdRequirement
}
import com.daml.lf.archive.Dar
import com.daml.lf.data.Ref._
import com.daml.platform.common.LedgerIdMode
import com.daml.platform.sandbox.SandboxServer
import com.daml.platform.sandbox.config.SandboxConfig
import com.daml.platform.services.time.TimeProviderType
import com.daml.ports.Port
import com.daml.timer.RetryStrategy
import eu.rekawek.toxiproxy._

import scala.concurrent._
import scala.concurrent.duration._
import scala.sys.process.Process

object TriggerServiceFixture {

  // Might throw IOException (unlikely). Best effort. There's a small
  // chance that having found one, it gets taken before we get to use
  // it.
  private def findFreePort(): Port = {
    val socket = new ServerSocket(Port(0).value)
    try {
      Port(socket.getLocalPort)
    } finally {
      socket.close()
    }
  }

  // Use a small initial interval so we can test restart behaviour more easily.
  private val minRestartInterval = FiniteDuration(1, duration.SECONDS)

  def withTriggerService[A](
      testName: String,
      dars: List[File],
      encodedDar: Option[Dar[(PackageId, DamlLf.ArchivePayload)]],
      jdbcConfig: Option[JdbcConfig],
      auth: Boolean,
  )(testFn: (Uri, LedgerClient, Proxy) => Future[A])(
      implicit asys: ActorSystem,
      mat: Materializer,
      aesf: ExecutionSequencerFactory,
      ec: ExecutionContext): Future[A] = {
    val host = InetAddress.getLoopbackAddress
    val isWindows: Boolean = sys.props("os.name").toLowerCase.contains("windows")

    // Set up an authentication service if enabled
    val authServiceAdminLedger: Future[Option[(SandboxServer, Port)]] =
      if (!auth) Future(None)
      else {
        val adminLedgerId = LedgerId("auth-service-admin-ledger")
        for {
          ledger <- Future(
            new SandboxServer(
              SandboxServer.defaultConfig.copy(
                port = Port.Dynamic,
                ledgerIdMode = LedgerIdMode.Static(adminLedgerId),
              ),
              mat))
          ledgerPort <- ledger.portF
        } yield Some((ledger, ledgerPort))
      }

    val authServiceBinaryLoc: String = {
      val extension = if (isWindows) ".exe" else ""
      BazelRunfiles.rlocation("triggers/service/ref-ledger-authentication-binary" + extension)
    }

    val authServiceInstanceF: Future[Option[(Process, Uri)]] =
      authServiceAdminLedger.flatMap {
        case None => Future(None)
        case Some((_, adminLedgerPort)) =>
          for {
            port <- Future(findFreePort())
            ledgerUri = Uri.from(
              scheme = "http",
              host = host.getHostAddress,
              port = adminLedgerPort.value)
            process <- Future {
              Process(
                Seq(authServiceBinaryLoc),
                None,
                ("DABL_AUTHENTICATION_SERVICE_ADDRESS", host.getHostAddress),
                ("DABL_AUTHENTICATION_SERVICE_PORT", port.toString),
                ("DABL_AUTHENTICATION_SERVICE_LEDGER_URL", ledgerUri.toString),
                ("DABL_AUTHENTICATION_SERVICE_TEST_MODE", "true") // Needed for initial authorize call with basic credentials
              ).run()
            }
            // Wait for the auth service instance to be ready to accept connections.
            _ <- RetryStrategy.constant(attempts = 10, waitTime = 4.seconds) { (_, _) =>
              for {
                channel <- Future(new Socket(host, port.value))
              } yield channel.close()
            }
            authServiceBaseUrl = Uri.from(
              scheme = "http",
              host = host.getHostAddress,
              port = port.value)
          } yield Some((process, authServiceBaseUrl))
      }

    // Launch a toxiproxy instance. Wait on it to be ready to accept connections.
    val toxiProxyExe =
      if (!isWindows)
        BazelRunfiles.rlocation("external/toxiproxy_dev_env/bin/toxiproxy-cmd")
      else
        BazelRunfiles.rlocation("external/toxiproxy_dev_env/toxiproxy-server-windows-amd64.exe")
    val toxiProxyPort = findFreePort()
    val toxiProxyProc = Process(Seq(toxiProxyExe, "--port", toxiProxyPort.value.toString)).run()
    RetryStrategy.constant(attempts = 3, waitTime = 2.seconds) { (_, _) =>
      for {
        channel <- Future(new Socket(host, toxiProxyPort.value))
      } yield channel.close()
    }
    val toxiProxyClient = new ToxiproxyClient(host.getHostName, toxiProxyPort.value)

    val ledgerId = LedgerId(testName)
    val applicationId = ApplicationId(testName)
    val ledgerF = for {
      authServiceInstance <- authServiceInstanceF
      authServiceBaseUrl = authServiceInstance.map(_._2)
      authServiceJwksUrl = authServiceBaseUrl.map(_.withPath(Path("/sa/jwks")))
      ledger <- Future(
        new SandboxServer(ledgerConfig(Port.Dynamic, dars, ledgerId, authServiceJwksUrl), mat))
      sandboxPort <- ledger.portF
      ledgerPort = sandboxPort.value
      ledgerProxyPort = findFreePort()
      ledgerProxy = toxiProxyClient.createProxy(
        "sandbox",
        s"${host.getHostName}:$ledgerProxyPort",
        s"${host.getHostName}:$ledgerPort")
    } yield (ledger, ledgerPort, ledgerProxyPort, ledgerProxy, authServiceBaseUrl)
    // 'ledgerProxyPort' is managed by the toxiproxy instance and
    // forwards to the real sandbox port.

    // Configure this client with the ledger's *actual* port.
    val clientF: Future[LedgerClient] = for {
      (_, ledgerPort, _, _, _) <- ledgerF
      client <- LedgerClient.singleHost(host.getHostName, ledgerPort, clientConfig(applicationId))
    } yield client

    // Configure the service with the ledger's *proxy* port.
    val serviceF: Future[(ServerBinding, TypedActorSystem[Message])] = for {
      (_, _, ledgerProxyPort, _, authServiceBaseUrl) <- ledgerF
      ledgerConfig = LedgerConfig(
        host.getHostName,
        ledgerProxyPort.value,
        ledgerId,
        TimeProviderType.Static,
        Duration.ofSeconds(30),
        ServiceConfig.DefaultMaxInboundMessageSize,
      )
      restartConfig = TriggerRestartConfig(
        minRestartInterval,
        ServiceConfig.DefaultMaxRestartInterval,
      )
      service <- ServiceMain.startServer(
        host.getHostName,
        Port(0).value,
        ledgerConfig,
        restartConfig,
        encodedDar,
        jdbcConfig,
        noSecretKey = true,
        authServiceBaseUrl
      )
    } yield service

    // For adding toxics.
    val ledgerProxyF: Future[Proxy] = for {
      (_, _, _, ledgerProxy, _) <- ledgerF
    } yield ledgerProxy

    val fa: Future[A] = for {
      client <- clientF
      binding <- serviceF
      ledgerProxy <- ledgerProxyF
      uri = Uri.from(scheme = "http", host = "localhost", port = binding._1.localAddress.getPort)
      a <- testFn(uri, client, ledgerProxy)
    } yield a

    fa.onComplete { _ =>
      serviceF.foreach({ case (_, system) => system ! Stop })
      ledgerF.foreach(_._1.close())
      toxiProxyProc.destroy
      authServiceInstanceF.foreach(_.foreach(_._1.destroy))
      authServiceAdminLedger.foreach(_.foreach(_._1.close()))
    }

    fa
  }

  private def ledgerConfig(
      ledgerPort: Port,
      dars: List[File],
      ledgerId: LedgerId,
      authServiceJwksUrl: Option[Uri],
  ): SandboxConfig =
    SandboxServer.defaultConfig.copy(
      port = ledgerPort,
      damlPackages = dars,
      timeProviderType = Some(TimeProviderType.Static),
      ledgerIdMode = LedgerIdMode.Static(ledgerId),
      authService = authServiceJwksUrl.map(url => AuthServiceJWT(JwksVerifier(url.toString))),
    )

  private def clientConfig(
      applicationId: ApplicationId,
      token: Option[String] = None): LedgerClientConfiguration =
    LedgerClientConfiguration(
      applicationId = ApplicationId.unwrap(applicationId),
      ledgerIdRequirement = LedgerIdRequirement.none,
      commandClient = CommandClientConfiguration.default,
      sslContext = None,
      token = token,
    )
}
