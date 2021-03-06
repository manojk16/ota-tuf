package com.advancedtelematic.tuf.reposerver.http

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.http.scaladsl.util.FastFuture
import cats.syntax.either._
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType._
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf.data.TufDataType.{RepoId, RoleType, JsonSignedPayload, TargetFilename}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import com.advancedtelematic.tuf.reposerver.data.RepositoryDataType.{SignedRole, TargetItem}
import com.advancedtelematic.tuf.reposerver.db.{FilenameCommentRepository, SignedRoleRepository, SignedRoleRepositorySupport, TargetItemRepositorySupport}
import com.advancedtelematic.tuf.reposerver.db.SignedRoleRepository.SignedRoleNotFound
import com.advancedtelematic.tuf.reposerver.db.{SignedRoleRepository, SignedRoleRepositorySupport, TargetItemRepositorySupport}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import cats.syntax.either._
import cats.syntax.flatMap
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import com.advancedtelematic.tuf.reposerver.db.Schema.filenameComments
import org.slf4j.LoggerFactory

class SignedRoleGeneration(keyserverClient: KeyserverClient)
                          (implicit val db: Database, val ec: ExecutionContext) extends SignedRoleRepositorySupport {

  private val log = LoggerFactory.getLogger(this.getClass)

  val targetRoleGeneration = new TargetRoleGeneration(keyserverClient)

  def regenerateSignedRoles(repoId: RepoId): Future[JsonSignedPayload] = {
    async {
      await(fetchRootRole(repoId))

      val expireAt = defaultExpire

      val targetVersion = await(nextVersion(repoId, RoleType.TARGETS))
      val targetRole = await(targetRoleGeneration.generate(repoId, expireAt, targetVersion))
      val signedTarget = await(signRole(repoId, targetRole))

      val dependent = await(regenerateSignedDependent(repoId, signedTarget, expireAt))

      await(signedRoleRepo.persistAll(signedTarget :: dependent))

      signedTarget.content
    }
  }

  def regenerateSignedDependent(repoId: RepoId, targetRole: SignedRole, expireAt: Instant): Future[List[SignedRole]] = async {
    val signedRoot = await(fetchRootRole(repoId))

    val snapshotVersion = await(nextVersion(repoId, RoleType.SNAPSHOT))
    val snapshotRole = genSnapshotRole(signedRoot, targetRole, expireAt, snapshotVersion)
    val signedSnapshot = await(signRole(repoId, snapshotRole))

    val timestampVersion = await(nextVersion(repoId, RoleType.TIMESTAMP))
    val timestampRole = genTimestampRole(signedSnapshot, expireAt, timestampVersion)
    val signedTimestamp = await(signRole(repoId, timestampRole))

    List(signedSnapshot, signedTimestamp)
  }

  def addTargetItem(targetItem: TargetItem): Future[JsonSignedPayload] =
    targetRoleGeneration.addTargetItem(targetItem).flatMap(_ ⇒ regenerateSignedRoles(targetItem.repoId))

  def deleteTargetItem(repoId: RepoId, filename: TargetFilename): Future[Unit] = for {
    _ <- ensureTargetsCanBeSigned(repoId)
    _ <- targetRoleGeneration.deleteTargetItem(repoId, filename)
    _ <- regenerateSignedRoles(repoId)
  } yield ()

  def signRole[T <: VersionedRole : TufRole : Decoder : Encoder](repoId: RepoId, role: T): Future[SignedRole] = {
    keyserverClient.sign(repoId, role.roleType, role.asJson).map { signedRole =>
      SignedRole.withChecksum(repoId, role.roleType, signedRole, role.version, role.expires)
    }
  }

  private def ensureTargetsCanBeSigned(repoId: RepoId): Future[SignedRole] = async {
    val rootRole = await(signedRoleRepo.find(repoId, RoleType.TARGETS)).content.signed.as[TargetsRole].valueOr(throw _)
    await(signRole(repoId, rootRole))
  }

  private def fetchRootRole(repoId: RepoId): Future[SignedRole] =
    keyserverClient.fetchRootRole(repoId).map { rootRole =>
      SignedRole.withChecksum(repoId, RoleType.ROOT, rootRole.asJsonSignedPayload, rootRole.signed.version, rootRole.signed.expires)
    }

  private def findAndCacheRole(repoId: RepoId, roleType: RoleType): Future[SignedRole] = {
    signedRoleRepo
      .find(repoId, roleType)
      .recoverWith { case SignedRoleNotFound => generateAndCacheRole(repoId, roleType) }
  }

  private def findFreshRole[T <: VersionedRole : TufRole : Decoder : Encoder](repoId: RepoId, roleType: RoleType, updateRoleFn: (T, Instant, Int) => T): Future[SignedRole] = {
    signedRoleRepo.find(repoId, roleType).flatMap { role =>
      val futureRole =
        if (role.expireAt.isBefore(Instant.now.plus(1, ChronoUnit.HOURS))) {
          val versionedRole = role.content.signed.as[T].valueOr(throw _)
          val nextVersion = versionedRole.version + 1
          val nextExpires = Instant.now.plus(1, ChronoUnit.DAYS)
          val newRole = updateRoleFn(versionedRole, nextExpires, nextVersion)

          signRole(repoId, newRole).flatMap(sr => signedRoleRepo.persist(sr))
        } else {
          FastFuture.successful(role)
        }

      futureRole.recoverWith {
        case KeyserverClient.RoleKeyNotFound =>
          log.info(s"Could not update $roleType (for $repoId) because the keys are missing, returning expired version")
          FastFuture.successful(role)
      }
    }.recoverWith {
      case SignedRoleRepository.SignedRoleNotFound =>
        generateAndCacheRole(repoId, roleType)
    }
  }

  private def generateAndCacheRole(repoId: RepoId, roleType: RoleType): Future[SignedRole] = {
    regenerateSignedRoles(repoId)
      .recoverWith { case err => log.warn("Could not generate signed roles", err) ; FastFuture.failed(SignedRoleNotFound) }
      .flatMap(_ => signedRoleRepo.find(repoId, roleType))
  }

  def findRole(repoId: RepoId, roleType: RoleType): Future[SignedRole] = {
    roleType match {
      case RoleType.ROOT =>
        fetchRootRole(repoId)
      case r @ RoleType.SNAPSHOT =>
        findFreshRole[SnapshotRole](repoId, r, (role, expires, version) => role.copy(expires = expires, version = version))
      case r @ RoleType.TIMESTAMP =>
        findFreshRole[TimestampRole](repoId, r, (role, expires, version) => role.copy(expires = expires, version = version))
      case r @ RoleType.TARGETS =>
        findFreshRole[TargetsRole](repoId, r, (role, expires, version) => role.copy(expires = expires, version = version))
      case _ =>
        findAndCacheRole(repoId, roleType)
    }
  }

  private def nextVersion(repoId: RepoId, roleType: RoleType): Future[Int] =
    signedRoleRepo
      .find(repoId, roleType)
      .map(_.version + 1)
      .recover {
        case SignedRoleNotFound => 1
      }

  private def genSnapshotRole(root: SignedRole, target: SignedRole, expireAt: Instant, version: Int): SnapshotRole = {
    val meta = List(root.asMetaRole, target.asMetaRole).toMap
    SnapshotRole(meta, expireAt, version)
  }

  private def genTimestampRole(snapshotRole: SignedRole, expireAt: Instant, version: Int): TimestampRole = {
    val meta = Map(snapshotRole.asMetaRole)
    TimestampRole(meta, expireAt, version)
  }

  private def defaultExpire: Instant =
    Instant.now().plus(31, ChronoUnit.DAYS)
}

protected class TargetRoleGeneration(roleSigningClient: KeyserverClient)
                          (implicit val db: Database, val ec: ExecutionContext)
  extends TargetItemRepositorySupport with FilenameCommentRepository.Support {

  def addTargetItem(targetItem: TargetItem): Future[TargetItem] =
    targetItemRepo.persist(targetItem)

  def deleteTargetItem(repoId: RepoId, filename: TargetFilename): Future[Unit] =
    targetItemRepo.deleteItemAndComments(filenameCommentRepo)(repoId, filename)

  def generate(repoId: RepoId, expireAt: Instant, version: Int): Future[TargetsRole] = {
    targetItemRepo.findFor(repoId).map { targetItems =>
      val targets = targetItems.map { item =>
        val hashes = Map(item.checksum.method -> item.checksum.hash)
        item.filename -> ClientTargetItem(hashes, item.length, item.custom.map(_.asJson))
      }.toMap

      TargetsRole(expireAt, targets, version)
    }
  }
}

