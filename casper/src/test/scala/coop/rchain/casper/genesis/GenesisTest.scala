package coop.rchain.casper.genesis

import java.io.PrintWriter
import java.nio.file.{Files, Path}

import cats.effect.Sync
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.casper.helper.BlockDagStorageFixture
import coop.rchain.casper.protocol.{BlockMessage, Bond}
import coop.rchain.casper.util.{ProtoUtil, RSpaceUtil}
import coop.rchain.casper.util.rholang.{InterpreterUtil, RuntimeManager}
import coop.rchain.crypto.codec.Base16
import coop.rchain.p2p.EffectsTestInstances.{LogStub, LogicalTime}
import coop.rchain.rholang.interpreter.Runtime
import coop.rchain.rholang.interpreter.storage.StoragePrinter
import coop.rchain.shared.PathOps.RichPath
import coop.rchain.shared.StoreType
import org.scalatest.{BeforeAndAfterEach, EitherValues, FlatSpec, Matchers}
import java.nio.file.Path

import coop.rchain.blockstorage.util.io.IOError
import coop.rchain.metrics
import coop.rchain.metrics.{Metrics, NoopSpan}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class GenesisTest extends FlatSpec with Matchers with EitherValues with BlockDagStorageFixture {
  import GenesisTest._

  val validators = Seq(
    "299670c52849f1aa82e8dfe5be872c16b600bf09cc8983e04b903411358f2de6",
    "6bf1b2753501d02d386789506a6d93681d2299c6edfd4455f596b97bc5725968"
  ).zipWithIndex

  val walletAddresses = Seq(
    "0x20356b6fae3a94db5f01bdd45347faFad3dd18ef",
    "0x041e1eec23d118f0c4ffc814d4f415ac3ef3dcff"
  )

  def printBonds(bondsFile: String): Unit = {
    val pw = new PrintWriter(bondsFile)
    pw.println(
      validators
        .map {
          case (v, i) => s"$v $i"
        }
        .mkString("\n")
    )
    pw.close()
  }

  def printWallets(walletsFile: String): Unit = {
    val pw = new PrintWriter(walletsFile)
    val walletsContent =
      walletAddresses.zipWithIndex
        .map {
          case (v, i) => s"$v,$i,0"
        }
        .mkString("\n")
    pw.println(walletsContent)
    pw.close()
  }

  "Genesis.fromInputFiles" should "generate random validators when no bonds file is given" in taskTest(
    withGenResources {
      (
          runtimeManager: RuntimeManager[Task],
          genesisPath: Path,
          log: LogStub[Task],
          time: LogicalTime[Task]
      ) =>
        for {
          _ <- fromInputFiles()(runtimeManager, genesisPath, log, time)
          _ = log.warns.count(
            _.contains(
              "Bonds file was not specified and default bonds file does not exist. Falling back on generating random validators."
            )
          ) should be(1)
        } yield log.infos.count(_.contains("Created validator")) should be(numValidators)
    }
  )

  it should "fail with error when bonds file does not exist" in taskTest(
    withGenResources {
      (
          runtimeManager: RuntimeManager[Task],
          genesisPath: Path,
          log: LogStub[Task],
          time: LogicalTime[Task]
      ) =>
        for {
          genesisAttempt <- fromInputFiles(maybeBondsPath = Some("not/a/real/file"))(
                             runtimeManager,
                             genesisPath,
                             log,
                             time
                           ).attempt
        } yield genesisAttempt.left.value.getMessage should be(
          "Specified bonds file not/a/real/file does not exist"
        )
    }
  )

  it should "fail with error when bonds file cannot be parsed" in taskTest(
    withGenResources {
      (
          runtimeManager: RuntimeManager[Task],
          genesisPath: Path,
          log: LogStub[Task],
          time: LogicalTime[Task]
      ) =>
        val badBondsFile = genesisPath.resolve("misformatted.txt").toString

        val pw = new PrintWriter(badBondsFile)
        pw.println("xzy 1\nabc 123 7")
        pw.close()

        for {
          genesisAttempt <- fromInputFiles(maybeBondsPath = Some(badBondsFile))(
                             runtimeManager,
                             genesisPath,
                             log,
                             time
                           ).attempt
        } yield genesisAttempt.left.value.getMessage should include(
          "misformatted.txt cannot be parsed"
        )
    }
  )

  it should "create a genesis block with the right bonds when a proper bonds file is given" in taskTest(
    withGenResources {
      (
          runtimeManager: RuntimeManager[Task],
          genesisPath: Path,
          log: LogStub[Task],
          time: LogicalTime[Task]
      ) =>
        val bondsFile = genesisPath.resolve("givenBonds.txt").toString
        printBonds(bondsFile)

        for {
          genesis <- fromInputFiles(maybeBondsPath = Some(bondsFile))(
                      runtimeManager,
                      genesisPath,
                      log,
                      time
                    )
          bonds = ProtoUtil.bonds(genesis)
          _     = log.infos.isEmpty should be(true)
          result = validators
            .map {
              case (v, i) => Bond(ByteString.copyFrom(Base16.unsafeDecode(v)), i.toLong)
            }
        } yield result.forall(bonds.contains(_)) should be(true)
    }
  )

  it should "create a valid genesis block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      withGenResources {
        (
            runtimeManager: RuntimeManager[Task],
            genesisPath: Path,
            log: LogStub[Task],
            time: LogicalTime[Task]
        ) =>
          implicit val logEff = log
          for {
            genesis <- fromInputFiles()(runtimeManager, genesisPath, log, time)
            _       <- BlockStore[Task].put(genesis.blockHash, genesis)
            dag     <- blockDagStorage.getRepresentation
            span    = new NoopSpan[Task]
            maybePostGenesisStateHash <- InterpreterUtil
                                          .validateBlockCheckpoint[Task](
                                            genesis,
                                            dag,
                                            runtimeManager,
                                            span
                                          )
          } yield maybePostGenesisStateHash should matchPattern { case Right(Some(_)) => }
      }
  }

  it should "detect an existing bonds file in the default location" in taskTest(withGenResources {
    (
        runtimeManager: RuntimeManager[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      val bondsFile = genesisPath.resolve("bonds.txt").toString
      printBonds(bondsFile)

      for {
        genesis <- fromInputFiles()(runtimeManager, genesisPath, log, time)
        bonds   = ProtoUtil.bonds(genesis)
        _       = log.infos.length should be(1)
        result = validators
          .map {
            case (v, i) => Bond(ByteString.copyFrom(Base16.unsafeDecode(v)), i.toLong)
          }
      } yield result.forall(bonds.contains(_)) should be(true)
  })

  it should "parse the wallets file and include it in the genesis state" in taskTest(
    withRawGenResources {
      (runtime: Runtime[Task], genesisPath: Path, log: LogStub[Task], time: LogicalTime[Task]) =>
        val walletsFile = genesisPath.resolve("wallets.txt").toString
        printWallets(walletsFile)

        import RSpaceUtil._
        for {
          runtimeManager <- RuntimeManager.fromRuntime(runtime)
          blockMessage <- fromInputFiles(deployTimestamp = Some(0L))(
                           runtimeManager,
                           genesisPath,
                           log,
                           time
                         )
          data <- {
            implicit val rm = runtimeManager
            getDataAtPrivateChannel[Task](
              blockMessage,
              "253fd2155493024567f66cf787e208feae9d3d24ae2f479c79ab3e8b98c3e6c6"
            ).map(_.head)
          }
          _ = walletAddresses.map { wallet =>
            data should include(wallet)
          }
        } yield ()
    }
  )

}

object GenesisTest {
  val storageSize     = 3024L * 1024
  def storageLocation = Files.createTempDirectory(s"casper-genesis-test-runtime")
  def genesisPath     = Files.createTempDirectory(s"casper-genesis-test")
  val numValidators   = 5
  val rchainShardId   = "rchain"

  implicit val raiseIOError = IOError.raiseIOErrorThroughSync[Task]

  def fromInputFiles(
      maybeBondsPath: Option[String] = None,
      numValidators: Int = numValidators,
      maybeWalletsPath: Option[String] = None,
      minimumBond: Long = 1L,
      maximumBond: Long = Long.MaxValue,
      faucet: Boolean = false,
      shardId: String = rchainShardId,
      deployTimestamp: Option[Long] = Some(System.currentTimeMillis())
  )(
      implicit runtimeManager: RuntimeManager[Task],
      genesisPath: Path,
      log: LogStub[Task],
      time: LogicalTime[Task]
  ): Task[BlockMessage] =
    Genesis
      .fromInputFiles[Task](
        maybeBondsPath,
        numValidators,
        genesisPath,
        maybeWalletsPath,
        minimumBond,
        maximumBond,
        faucet,
        runtimeManager,
        shardId,
        deployTimestamp
      )

  def withRawGenResources(
      body: (Runtime[Task], Path, LogStub[Task], LogicalTime[Task]) => Task[Unit]
  ): Task[Unit] = {
    val storePath                           = storageLocation
    val gp                                  = genesisPath
    implicit val log                        = new LogStub[Task]
    implicit val noopMetrics: Metrics[Task] = new metrics.Metrics.MetricsNOP[Task]
    val time                                = new LogicalTime[Task]

    for {
      runtime <- Runtime.createWithEmptyCost[Task, Task.Par](
                  storePath,
                  storageSize,
                  StoreType.LMDB
                )
      result <- body(runtime, genesisPath, log, time)
      _      <- runtime.close()
      _      <- Sync[Task].delay { storePath.recursivelyDelete() }
      _      <- Sync[Task].delay { gp.recursivelyDelete() }
    } yield result
  }

  def withGenResources(
      body: (RuntimeManager[Task], Path, LogStub[Task], LogicalTime[Task]) => Task[Unit]
  ): Task[Unit] =
    withRawGenResources {
      (runtime: Runtime[Task], genesisPath: Path, log: LogStub[Task], time: LogicalTime[Task]) =>
        RuntimeManager.fromRuntime(runtime).flatMap(body(_, genesisPath, log, time))
    }

  def taskTest[R](f: Task[R]): R =
    f.runSyncUnsafe()
}
