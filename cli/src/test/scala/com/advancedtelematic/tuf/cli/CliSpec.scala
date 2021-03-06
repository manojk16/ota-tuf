package com.advancedtelematic.tuf.cli

import TryToFuture.TryToFutureOp
import java.security.Security
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import com.advancedtelematic.libats.data.DataType.ValidChecksum
import com.advancedtelematic.libtuf.crypt.SignedPayloadSignatureOps._
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.ClientDataType.{RoleKeys, RootRole, TargetsRole}
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, JsonSignedPayload, KeyId, KeyType, RoleType, RsaKeyType, SignedPayload, TufKey, TufKeyPair}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FunSuite, Matchers}
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.reposerver.{UserDirectorClient, UserTufServerClient, UserReposerverClient}
import com.advancedtelematic.libtuf.reposerver.UserTufServerClient.{RoleNotFound, TargetsResponse}
import com.advancedtelematic.tuf.cli.DataType.KeyName
import com.advancedtelematic.tuf.cli.repo.{DirectorRepo, RepoServerRepo, TufRepo}
import eu.timepit.refined.api.Refined
import org.scalactic.source.Position
import io.circe.syntax._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait KeyTypeSpecSupport {
  self: FunSuite =>

  def keyTypeTest(name: String)(fn: KeyType => Any)(implicit pos: Position): Unit = {
    test(name + " Ed25519")(fn(Ed25519KeyType))
    test(name + " RSA")(fn(RsaKeyType))
  }
}

abstract class CliSpec extends FunSuite with Matchers with ScalaFutures {
  Security.addProvider(new BouncyCastleProvider)

  override implicit def patienceConfig = PatienceConfig().copy(timeout = Span(10, Seconds))
}

import scala.concurrent.ExecutionContext.Implicits.global

abstract class FakeUserClient(keyType: KeyType) extends UserTufServerClient {

  protected val oldPair = keyType.crypto.generateKeyPair(keyType.crypto.defaultKeySize)

  protected val targetsPair = keyType.crypto.generateKeyPair(keyType.crypto.defaultKeySize)
  protected val pairs = Set(oldPair, targetsPair)
  protected var targetsPubKey = targetsPair.pubkey

  protected val rootRoles = new ConcurrentHashMap[Int, SignedPayload[RootRole]]()

  override def fetchKeyPair(keyId: KeyId): Future[TufKeyPair] = {
    Future.successful(pairs.filter(_.pubkey.id == keyId).head)
  }

  override def root(version: Option[Int] = None): Future[SignedPayload[RootRole]] = {
    if (version.isDefined)
      Option(rootRoles.get(version.get)) match {
        case Some(r) => Future.successful(r)
        case None => Future.failed(RoleNotFound(s"role with version ${version.get} not found"))
      }
    else if (rootRoles.isEmpty) {
      val unsignedRoot = RootRole(
        Map(
          oldPair.pubkey.id -> oldPair.pubkey,
          targetsPubKey.id -> targetsPubKey),
        Map(
          RoleType.ROOT -> RoleKeys(Seq(oldPair.pubkey.id), 1),
          RoleType.TARGETS -> RoleKeys(Seq(targetsPubKey.id), 1)
        ), 1, Instant.now.plus(1, ChronoUnit.HOURS))

      val sig = TufCrypto.signPayload(oldPair.privkey, unsignedRoot.asJson).toClient(oldPair.pubkey.id)
      val signedRoot = SignedPayload(Seq(sig), unsignedRoot, unsignedRoot.asJson)

      rootRoles.put(signedRoot.signed.version, signedRoot)
      Future.successful(signedRoot)
    } else Future.successful {
      rootRoles.asScala.maxBy(_._1)._2
    }
  }

  override def deleteKey(keyId: KeyId): Future[Unit] = {
    if (keyId == oldPair.pubkey.id)
      Future.successful(())
    else
      Future.failed(new RuntimeException(s"[test] key not found $keyId"))
  }

  override def pushSignedRoot(signedRoot: SignedPayload[RootRole]): Future[Unit] = {
    if (signedRoot.isValidFor(oldPair.pubkey)) {
      rootRoles.put(signedRoot.signed.version, signedRoot)
      Future.successful(())
    } else
      Future.failed(new RuntimeException("[test] invalid signatures for root role"))
  }

  def sign(rootRole: RootRole): SignedPayload[RootRole] = {
    val sig = TufCrypto.signPayload(oldPair.privkey, rootRole.asJson).toClient(oldPair.pubkey.id)
    SignedPayload(Seq(sig), rootRole, rootRole.asJson)
  }

  def setRoot(signedPayload: SignedPayload[RootRole]): Unit = {
    rootRoles.put(signedPayload.signed.version, signedPayload)
  }
}

abstract class FakeUserClientOffline[R <: TufRepo](keyType: KeyType) extends FakeUserClient(keyType) {

  def moveOffline(repo: R, keyType: KeyType): Future[(TufKey, TufKey, SignedPayload[RootRole])]

}

class FakeUserDirectorClient(keyType: KeyType) extends FakeUserClientOffline[DirectorRepo](keyType) with UserDirectorClient {

  def moveOffline(repo: DirectorRepo, keyType: KeyType): Future[(TufKey, TufKey, SignedPayload[RootRole])] = {
    val oldRootName = KeyName(s"oldroot${repo.name.value}")
    val newRootName = KeyName(s"newroot${repo.name.value}")

    val pub = repo.genKeys(newRootName, keyType).get.pubkey

    repo.moveRootOffline(this, newRootName, oldRootName, None).map { s => (pub, targetsPubKey, s) }
  }
}

object FakeUserDirectorClient {
  def apply(keyType: KeyType): FakeUserDirectorClient = new FakeUserDirectorClient(keyType)
}

class FakeUserReposerverClient(keyType: KeyType) extends FakeUserClientOffline[RepoServerRepo](keyType) with UserReposerverClient {
  private var unsignedTargets = TargetsRole(Instant.now.plus(1, ChronoUnit.DAYS), Map.empty, 1)

  override def targets(): Future[TargetsResponse] = Future.successful {
    val sig = TufCrypto.signPayload(targetsPair.privkey, unsignedTargets.asJson).toClient(targetsPubKey.id)
    val signedPayload = SignedPayload(Seq(sig), unsignedTargets, unsignedTargets.asJson)
    TargetsResponse(signedPayload, Option(Refined.unsafeApply("095c33175e5af42691c1b41d388c8ce842c7fbb792fcc6514b5436f7f80420db")))
  }

  override def pushTargets(targetsRole: SignedPayload[TargetsRole], checksum: Option[Refined[String, ValidChecksum]]): Future[Unit] =
    Try(checksum.get).flatMap { _ =>
      val lastRoot = rootRoles.asScala.maxBy(_._1)._2.signed
      val targetsPubKey = lastRoot.keys(lastRoot.roles(RoleType.TARGETS).keyids.head)

      if (targetsRole.isValidFor(targetsPubKey)) {
        unsignedTargets = targetsRole.signed
        Success(())
      } else
        Failure(new RuntimeException("[test] invalid signatures for targets role"))
    }.toFuture

  def moveOffline(repo: RepoServerRepo, keyType: KeyType): Future[(TufKey, TufKey, SignedPayload[RootRole])] = {
    val oldRootName = KeyName(s"oldroot${repo.name.value}")
    val newRootName = KeyName(s"newroot${repo.name.value}")
    val newTargetsName = KeyName(s"targets${repo.name.value}")

    val pub = repo.genKeys(newRootName, keyType).get.pubkey
    val pubT = repo.genKeys(newTargetsName, keyType).get.pubkey

    repo.moveRootOffline(this, newRootName, oldRootName, newTargetsName, None).map { s => (pub, pubT, s) }
  }

}

object FakeUserReposerverClient {
  def apply(keyType: KeyType): FakeUserReposerverClient = new FakeUserReposerverClient(keyType)
}
