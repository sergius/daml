// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.engine.script.test

import akka.actor.ActorSystem
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.Materializer
import io.grpc.Channel
import java.io.File

import org.scalatest._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scalaz.{-\/, \/-}
import scalaz.syntax.traverse._
import spray.json._
import com.daml.bazeltools.BazelRunfiles._
import com.daml.lf.archive.{Dar, DarReader}
import com.daml.lf.archive.Decode
import com.daml.lf.data.Ref._
import com.daml.lf.engine.script.{
  ApiParameters,
  Participant,
  Participants,
  Runner,
  ScriptLedgerClient,
  ScriptTimeMode,
  Party => ScriptParty
}
import com.daml.lf.iface.EnvironmentInterface
import com.daml.lf.iface.reader.InterfaceReader
import com.daml.lf.language.Ast.Package
import com.daml.lf.speedy.SError._
import com.daml.lf.speedy.SValue
import com.daml.lf.speedy.SValue._
import com.daml.grpc.adapter.{AkkaExecutionSequencerPool, ExecutionSequencerFactory}
import com.daml.http.HttpService
import com.daml.jwt.JwtSigner
import com.daml.jwt.domain.DecodedJwt
import com.daml.ledger.api.domain.LedgerId
import com.daml.ledger.api.refinements.ApiTypes.ApplicationId
import com.daml.ledger.api.testing.utils.{
  MockMessages,
  OwnedResource,
  SuiteResource,
  SuiteResourceManagementAroundAll,
  Resource => TestResource
}
import com.daml.ledger.api.auth.{AuthServiceJWTCodec, AuthServiceJWTPayload}
import com.daml.ledger.api.tls.TlsConfiguration
import com.daml.platform.apiserver.services.GrpcClientResource
import com.daml.platform.common.LedgerIdMode
import com.daml.platform.sandbox.{AbstractSandboxFixture, SandboxServer}
import com.daml.platform.sandbox.config.SandboxConfig
import com.daml.platform.sandbox.services.TestCommands
import com.daml.ports.Port
import com.daml.resources.{Resource, ResourceOwner}

trait JsonApiFixture
    extends AbstractSandboxFixture
    with SuiteResource[(SandboxServer, Channel, ServerBinding)] {
  self: Suite =>

  override protected def darFile = new File(rlocation("daml-script/test/script-test.dar"))
  protected val darFileNoLedger = new File(rlocation("daml-script/test/script-test-no-ledger.dar"))
  protected def server: SandboxServer = suiteResource.value._1
  override protected def serverPort: Port = server.port
  override protected def channel: Channel = suiteResource.value._2
  override protected def config: SandboxConfig =
    super.config
      .copy(ledgerIdMode = LedgerIdMode.Static(LedgerId("MyLedger")))
  def httpPort: Int = suiteResource.value._3.localAddress.getPort

  // We have to use a different actorsystem for the JSON API since package reloading
  // blocks everything so it will timeout as sandbox cannot make progres simultaneously.
  private val jsonApiActorSystem: ActorSystem = ActorSystem("json-api")
  private val jsonApiMaterializer: Materializer = Materializer(system)
  private val jsonApiExecutionSequencerFactory: ExecutionSequencerFactory =
    new AkkaExecutionSequencerPool(poolName = "json-api", actorCount = 1)

  override protected def afterAll(): Unit = {
    jsonApiExecutionSequencerFactory.close()
    materializer.shutdown()
    Await.result(jsonApiActorSystem.terminate(), 30.seconds)
    super.afterAll()
  }

  override protected lazy val suiteResource
    : TestResource[(SandboxServer, Channel, ServerBinding)] = {
    implicit val ec: ExecutionContext = system.dispatcher
    new OwnedResource[(SandboxServer, Channel, ServerBinding)](
      for {
        jdbcUrl <- database
          .fold[ResourceOwner[Option[String]]](ResourceOwner.successful(None))(_.map(info =>
            Some(info.jdbcUrl)))
        server <- SandboxServer.owner(config.copy(jdbcUrl = jdbcUrl))
        channel <- GrpcClientResource.owner(server.port)
        httpService <- new ResourceOwner[ServerBinding] {
          override def acquire()(implicit ec: ExecutionContext): Resource[ServerBinding] = {
            Resource[ServerBinding] {
              val config = new HttpService.DefaultStartSettings {
                override val ledgerHost = "localhost"
                override val ledgerPort = server.port.value
                override val applicationId = ApplicationId(MockMessages.applicationId)
                override val address = "localhost"
                override val httpPort = 0
                override val portFile = None
                override val tlsConfig = TlsConfiguration(enabled = false, None, None, None)
                override val wsConfig = None
                override val accessTokenFile = None
                override val allowNonHttps = true
              }
              HttpService
                .start(config)(
                  jsonApiActorSystem,
                  jsonApiMaterializer,
                  jsonApiExecutionSequencerFactory,
                  jsonApiActorSystem.dispatcher)
                .flatMap({
                  case -\/(e) => Future.failed(new IllegalStateException(e.toString))
                  case \/-(a) => Future.successful(a)
                })
            }((binding: ServerBinding) => binding.unbind().map(done => ()))
          }
        }
      } yield (server, channel, httpService)
    )
  }
}

final class JsonApiIt
    extends AsyncWordSpec
    with TestCommands
    with JsonApiFixture
    with Matchers
    with SuiteResourceManagementAroundAll
    with TryValues {

  private def readDar(file: File): (Dar[(PackageId, Package)], EnvironmentInterface) = {
    val dar = DarReader().readArchiveFromFile(file).get.map {
      case (pkgId, archive) => Decode.readArchivePayload(pkgId, archive)
    }
    val ifaceDar = dar.map(pkg => InterfaceReader.readInterface(() => \/-(pkg))._2)
    val envIface = EnvironmentInterface.fromReaderInterfaces(ifaceDar)
    (dar, envIface)
  }

  val (dar, envIface) = readDar(darFile)
  val (darNoLedger, envIfaceNoLedger) = readDar(darFileNoLedger)

  def getToken(parties: List[String], admin: Boolean): String = {
    val payload = AuthServiceJWTPayload(
      ledgerId = Some("MyLedger"),
      participantId = None,
      exp = None,
      applicationId = Some("foobar"),
      actAs = parties,
      admin = admin,
      readAs = List()
    )
    val header = """{"alg": "HS256", "typ": "JWT"}"""
    val jwt = DecodedJwt[String](header, AuthServiceJWTCodec.writeToString(payload))
    JwtSigner.HMAC256.sign(jwt, "secret") match {
      case -\/(e) => throw new IllegalStateException(e.toString)
      case \/-(a) => a.value
    }
  }

  private def getClients(
      parties: List[String] = List(party),
      defaultParty: Option[String] = None,
      admin: Boolean = false,
      envIface: EnvironmentInterface = envIface) = {
    // We give the default participant some nonsense party so the checks for party mismatch fail
    // due to the mismatch and not because the token does not allow inferring a party
    val defaultParticipant =
      ApiParameters("http://localhost", httpPort, Some(getToken(defaultParty.toList, true)))
    val partyMap = parties.map(p => (ScriptParty(p), Participant(p))).toMap
    val participantMap = parties
      .map(
        p =>
          (
            Participant(p),
            ApiParameters("http://localhost", httpPort, Some(getToken(List(p), admin)))))
      .toMap
    val participantParams = Participants(Some(defaultParticipant), participantMap, partyMap)
    Runner.jsonClients(participantParams, envIface)
  }

  private val party = "Alice"

  private def run(
      clients: Participants[ScriptLedgerClient],
      name: QualifiedName,
      inputValue: Option[JsValue] = Some(JsString(party)),
      dar: Dar[(PackageId, Package)] = dar): Future[SValue] = {
    val scriptId = Identifier(dar.main._1, name)
    Runner.run(
      dar,
      scriptId,
      inputValue,
      clients,
      ApplicationId(MockMessages.applicationId),
      ScriptTimeMode.WallClock)
  }

  "DAML Script over JSON API" can {
    "Basic" should {
      "return 42" in {
        for {
          clients <- getClients()
          result <- run(clients, QualifiedName.assertFromString("ScriptTest:jsonBasic"))
        } yield {
          assert(result == SInt64(42))
        }
      }
    }
    "CreateAndExercise" should {
      "return 42" in {
        for {
          clients <- getClients()
          result <- run(clients, QualifiedName.assertFromString("ScriptTest:jsonCreateAndExercise"))
        } yield {
          assert(result == SInt64(42))
        }
      }
    }
    "ExerciseByKey" should {
      "return equal contract ids" in {
        for {
          clients <- getClients()
          result <- run(clients, QualifiedName.assertFromString("ScriptTest:jsonExerciseByKey"))
        } yield {
          result match {
            case SRecord(_, _, vals) if vals.size == 2 =>
              assert(vals.get(0) == vals.get(1))
            case _ => fail(s"Expected Tuple2 but got $result")
          }
        }
      }
    }
    "submit with party mismatch fails" in {
      for {
        clients <- getClients(defaultParty = Some("Alice"))
        exception <- recoverToExceptionIf[RuntimeException](
          run(
            clients,
            QualifiedName.assertFromString("ScriptTest:jsonCreate"),
            Some(JsString("Bob"))))
      } yield {
        assert(
          exception.getMessage === "Tried to submit a command as Bob but token is only valid for Alice")
      }
    }
    "query with party mismatch fails" in {
      for {
        clients <- getClients(defaultParty = Some("Alice"))
        exception <- recoverToExceptionIf[RuntimeException](
          run(
            clients,
            QualifiedName.assertFromString("ScriptTest:jsonQuery"),
            Some(JsString("Bob"))))
      } yield {
        assert(exception.getMessage === "Tried to query as Bob but token is only valid for Alice")
      }
    }
    "submit with no party fails" in {
      for {
        clients <- getClients(parties = List())
        exception <- recoverToExceptionIf[RuntimeException](
          run(clients, QualifiedName.assertFromString("ScriptTest:jsonCreate")))
      } yield {
        assert(
          exception.getMessage === "Tried to submit a command as Alice but token does not provide a unique party identifier")
      }
    }
    "submit fails on assertion failure" in {
      for {
        clients <- getClients()
        exception <- recoverToExceptionIf[DamlEUserError](
          run(clients, QualifiedName.assertFromString("ScriptTest:jsonFailingCreateAndExercise")))
      } yield {
        exception.message should include("Error: User abort: Assertion failed.")
      }
    }
    "submitMustFail succeeds on assertion falure" in {
      for {
        clients <- getClients()
        result <- run(
          clients,
          QualifiedName.assertFromString("ScriptTest:jsonExpectedFailureCreateAndExercise"))
      } yield {
        assert(result == SUnit)
      }
    }
    "allocateParty" in {
      for {
        clients <- getClients(parties = List(), admin = true)
        result <- run(
          clients,
          QualifiedName.assertFromString("ScriptTest:jsonAllocateParty"),
          Some(JsString("Eve")))
      } yield {
        assert(result == SParty(Party.assertFromString("Eve")))
      }
    }
    "multi-party" in {
      for {
        clients <- getClients(parties = List("Alice", "Bob"))
        result <- run(
          clients,
          QualifiedName.assertFromString("ScriptTest:jsonMultiParty"),
          Some(JsArray(JsString("Alice"), JsString("Bob"))))
      } yield {
        assert(result == SUnit)
      }
    }
    "missing template id" in {
      for {
        clients <- getClients(envIface = envIfaceNoLedger)
        ex <- recoverToExceptionIf[RuntimeException](
          run(
            clients,
            QualifiedName.assertFromString("ScriptTest:jsonMissingTemplateId"),
            dar = darNoLedger
          ))
      } yield {
        assert(ex.toString.contains("Cannot resolve template ID"))
      }
    }
  }
}
