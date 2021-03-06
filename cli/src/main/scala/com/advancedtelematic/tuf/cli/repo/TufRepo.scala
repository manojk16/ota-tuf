package com.advancedtelematic.tuf.cli.repo

import java.io._
import java.net.URI
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.time.{Instant, Period}

import cats.data.Validated.{Invalid, Valid}
import com.advancedtelematic.libtuf.data.RootManipulationOps._
import com.advancedtelematic.libats.data.DataType.{HashMethod, ValidChecksum}
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{ClientTargetItem, MetaPath, RoleKeys, RootRole, TargetCustom, TargetsRole, TufRole, TufRoleOps}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat.TargetFormat
import com.advancedtelematic.libtuf.data.TufDataType.{ClientSignature, HardwareIdentifier, JsonSignedPayload, KeyId, KeyType, RoleType, SignedPayload, TargetFilename, TargetName, TargetVersion, TufKeyPair, TufPrivateKey, ValidTargetFilename}
import com.advancedtelematic.libtuf.reposerver.{UserDirectorClient, UserTufServerClient, UserReposerverClient}
import com.advancedtelematic.tuf.cli.DataType._
import com.advancedtelematic.tuf.cli.DataType.{AuthConfig, KeyName, RepoConfig, RepoName}
import com.advancedtelematic.tuf.cli.{CliCodecs, CliUtil}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineV
import io.circe.jawn.parseFile
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import org.slf4j.LoggerFactory
import com.advancedtelematic.tuf.cli.TryToFuture._
import com.advancedtelematic.libtuf.data.ClientDataType.TufRole._
import com.advancedtelematic.libtuf.reposerver.UserTufServerClient.{RoleNotFound, TargetsResponse}
import java.nio.file.attribute.PosixFilePermission._

import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.crypt.CanonicalJson._
import cats.data.{NonEmptyList, ValidatedNel}
import com.advancedtelematic.libtuf.data.RootRoleValidation

import scala.async.Async._
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}
import shapeless.{:: => _, Path => _, _}
import cats.syntax.validated._
import com.advancedtelematic.tuf.cli.repo.TufRepo.{RootPullError, TargetsPullError}
import cats.implicits._

object TufRepo {
  import CliCodecs._

  def apply(repoServerType: TufServerType, name: RepoName, repoPath: Path)(implicit ec: ExecutionContext) =
    repoServerType match {
      case RepoServer => new RepoServerRepo(name, repoPath)
      case Director => new DirectorRepo(name, repoPath)
    }

  case object RoleChecksumNotFound extends Exception(
    "Could not find targets checksum file. You need this to push a new targets file. A Role checksum can be obtained using the pull command"
  ) with NoStackTrace

  case class TreehubConfigError(msg: String) extends Exception(msg) with NoStackTrace

  case class MissingCredentialsZipFile(filename: String) extends Exception(
    s"Missing file from credentials.zip: $filename"
  ) with NoStackTrace

  case class RepoAlreadyInitialized(path: Path) extends Exception(s"Repository at $path was already initialized") with NoStackTrace

  case class TargetsPullError(msg: String) extends Exception(s"Could not pull targets from server $msg") with NoStackTrace

  case class RoleMissing[T](rolePath: String)(implicit ev: TufRole[T]) extends Throwable(s"Missing role ${ev.toMetaPath.toString()} at $rolePath") with NoStackTrace

  case class RootPullError(errors: NonEmptyList[String]) extends Throwable("Could not validate a valid root.json chain:\n" + errors.toList.mkString("\n")) with NoStackTrace

  implicit class RootValidationTryConversion(value: ValidatedNel[String, SignedPayload[RootRole]]) {
    def toTry: Try[SignedPayload[RootRole]] =
      value.fold(
        errors => Failure(RootPullError(errors)),
        signedPayload => Success(signedPayload)
      )
  }

  protected [cli] def readConfigFile(repoPath: Path): Try[RepoConfig] =
    Try { new FileInputStream(repoPath.resolve("config.json").toFile) }
      .flatMap { is => CliUtil.readJsonFrom[RepoConfig](is) }

  protected [repo] def writeConfigFiles(repoPath: Path, repoUri: URI, treehubConfig: TreehubConfig,
                                        authConfig: Option[AuthConfig], repoServerType: TufServerType): Try[RepoConfig] = Try {
    val repoConfig = RepoConfig(repoUri, authConfig, treehubConfig, repoServerType)
    Files.write(repoPath.resolve("config.json"), repoConfig.asJson.spaces2.getBytes)
    repoConfig
  }
}

abstract class TufRepo(val name: RepoName, val repoPath: Path)(implicit ec: ExecutionContext) {
  import TufRepo._

  protected[repo] lazy val keyStorage = new CliKeyStorage(repoPath)

  protected lazy val log = LoggerFactory.getLogger(this.getClass)

  private lazy val rolesPath = repoPath.resolve("roles")

  protected val DEFAULT_ROOT_EXPIRE_TIME = Period.ofDays(365)
  private val DEFAULT_TARGET_EXPIRE_TIME = Period.ofDays(31)

  def initRepoDirs(): Try[Unit] = Try {
    val perms = PosixFilePermissions.asFileAttribute(java.util.EnumSet.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE))

    Files.createDirectory(repoPath, perms)
    Files.createDirectory(rolesPath, perms)
    Files.createDirectory(rolesPath.resolve("unsigned"), perms)

    def checkPerms(path: Path): Unit = {
      val currentPerms = Files.getPosixFilePermissions(path)
      if(currentPerms.asScala != Set(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE))
        log.warn(s"Permissions for $path are too open")
    }

    checkPerms(repoPath)
    checkPerms(rolesPath)
    checkPerms(rolesPath.resolve("unsigned"))
  }

  def keyIdsByName(keyNames: List[KeyName]): Try[List[KeyId]] = for {
    keys <- keyNames.traverse(keyStorage.readPublicKey)
  } yield keys.map(_.id)

  def removeRootKeys(keyIds: List[KeyId]): Try[Path] = for {
    unsignedRoot <- readUnsignedRole[RootRole]
    newRoot = unsignedRoot.removeRoleKeys(RoleType.ROOT, keyIds.toSet)
    path <- writeUnsignedRole(newRoot)
  } yield path

  def addRootKeys(keyNames: List[KeyName]): Try[Path] =
    for {
      unsignedRoot <- readUnsignedRole[RootRole]
      keys <- keyNames.traverse(keyStorage.readPublicKey)
      newRoot = unsignedRoot.addRoleKeys(RoleType.ROOT, keys:_*)
      path <- writeUnsignedRole(newRoot)
    } yield path

  private def validateSameVersion(from: SignedPayload[RootRole], to: SignedPayload[RootRole]): Future[Unit] = {
    if(from.asJson.canonical == to.asJson.canonical)
      Future.successful(())
    else
      Future.failed(RootPullError(NonEmptyList.of("New root has same version as old root but is not the same root.json")))
  }

  private def validatePath(reposerverClient: UserTufServerClient, from: SignedPayload[RootRole], to: SignedPayload[RootRole]): Future[Unit] = {
    val rootRolesF = fetchRootChain(reposerverClient, from.signed.version + 1, to.signed.version)

    rootRolesF.flatMap { rootRoles =>
      val validated = (from +: rootRoles :+ to).sliding(2, 1).toList.traverse {
        case Vector(oldR, newR) =>
          RootRoleValidation.newRootIsValid(newR, oldR)
        case l =>
          throw new IllegalArgumentException(s"too many elements for $l")
      }

      validated.fold(
        errors => Future.failed(RootPullError(errors)),
        _ => Future.successful(())
      )
    }
  }

  def pullRoot(client: UserTufServerClient, skipLocalValidation: Boolean): Future[SignedPayload[RootRole]] = {
    def newRootIsvalid(newRoot: SignedPayload[RootRole]): Future[SignedPayload[RootRole]] = {
      if(skipLocalValidation)
        RootRoleValidation.rootIsValid(newRoot).toTry.toFuture
      else
        async {
          val oldRoot = await(readSignedRole[RootRole].toFuture)

          if (oldRoot.signed.version == newRoot.signed.version)
            await(validateSameVersion(oldRoot, newRoot))
          else
            await(validatePath(client, oldRoot, newRoot))

          newRoot
        }
    }

    for {
      newRoot <- client.root()
      _ <- newRootIsvalid(newRoot)
      _ <- writeUnsignedRole(newRoot.signed).toFuture
      _ <- writeSignedRole(newRoot).toFuture
    } yield newRoot
  }

  def pushRoot(client: UserTufServerClient): Future[Unit] = for {
    signedRoot <- readSignedRole[RootRole].toFuture
    _ <- client.pushSignedRoot(signedRoot)
  } yield ()

  def signRoot(keys: Seq[KeyName], version: Option[Int] = None): Try[Path] =
    signRole[RootRole](version, keys)

  private val VersionPath = ^.version
  private val ExpiresPath = ^.expires
  import cats.implicits._

  protected def signRole[T : Decoder : Encoder](version: Option[Int], keys: Seq[KeyName])
                                             (implicit ev: TufRole[T], versionL: VersionPath.Lens[T, Int],
                                              expiresL: ExpiresPath.Lens[T, Instant]): Try[Path] = {
    def signatures(payload: T): Try[List[ClientSignature]] =
      keys.toList.traverse { key =>
        keyStorage.readKeyPair(key).map { case (pub, priv) =>
          TufCrypto.signPayload(priv, payload.asJson).toClient(pub.id)
        }
      }

    for {
      unsigned <- readUnsignedRole[T]
      newV = version.getOrElse(versionL().get(unsigned) + 1)
      withIncreasedVersion = versionL().set(unsigned)(newV)
      newUnsigned = expiresL().set(withIncreasedVersion)(Instant.now().plus(DEFAULT_TARGET_EXPIRE_TIME))
      sigs <- signatures(newUnsigned)
      signedRole = SignedPayload(sigs, newUnsigned, newUnsigned.asJson)
      path <- writeSignedRole(signedRole)
    } yield path
  }

  protected def deleteOrReadKey(reposerverClient: UserTufServerClient, keyName: KeyName, keyId: KeyId): Future[TufPrivateKey] = {
    keyStorage.readPrivateKey(keyName).toFuture.recoverWith { case _ =>
      log.info(s"Could not read old private key locally, fetching before deleting from server")

      for {
        keyPair ← reposerverClient.fetchKeyPair(keyId)
        _ ← reposerverClient.deleteKey(keyId)
      } yield keyPair.privkey
    }
  }

  private def withExistingRolePath[T : TufRole, U](path: Path)(fn: Path => Try[U]): Try[U] = {
    if (path.toFile.exists())
      fn(path)
    else
      Failure(RoleMissing[T](path.toString))
  }

  def readUnsignedRole[T : Decoder](implicit ev: TufRole[T]): Try[T] = {
    val path = rolesPath.resolve("unsigned").resolve(ev.toMetaPath.value)
    withExistingRolePath[T, T](path) { p =>
      parseFile(p.toFile).flatMap(_.as[T]).toTry
    }
  }

  def readSignedRole[T : Encoder : Decoder](implicit ev: TufRole[T]): Try[SignedPayload[T]] = {
    val path = rolesPath.resolve(ev.toMetaPath.value)
    withExistingRolePath[T, SignedPayload[T]](path) { p =>
      parseFile(p.toFile).flatMap(_.as[SignedPayload[T]]).toTry
    }
  }

  protected def readChecksum[T](implicit ev: TufRole[T]): Try[Refined[String, ValidChecksum]] = Try {
    val lines = Files.readAllLines(rolesPath.resolve(ev.checksumPath)).asScala
    assert(lines.tail.isEmpty)
    refineV[ValidChecksum](lines.head).valueOr(err => throw new Exception(err))
  }.recoverWith {
    case _: FileNotFoundException => Failure(RoleChecksumNotFound)
  }

  protected def writeChecksum(checksum: Refined[String, ValidChecksum]): Try[Unit] = Try {
    Files.write(rolesPath.resolve(TufRole.targetsTufRole.checksumPath), checksum.value.getBytes)
  }

  def writeUnsignedRole[T : TufRole : Encoder](role: T): Try[Path] =
    writeRole(rolesPath.resolve("unsigned"), role.toMetaPath, role)

  def writeSignedRole[T : TufRole : Encoder](signedPayload: SignedPayload[T]): Try[Path] =
    writeRole(rolesPath, signedPayload.signed.toMetaPath, signedPayload)

  private def writeRole[T : Encoder](path: Path, metaPath: MetaPath, payload: T): Try[Path] = Try {
    val rolePath = path.resolve(metaPath.value)
    Files.write(rolePath, payload.asJson.spaces2.getBytes)
    rolePath
  }

  def genKeys(name: KeyName, keyType: KeyType, keySize: Option[Int] = None): Try[TufKeyPair] =
    keyStorage.genKeys(name, keyType, keySize)

  def treehubConfig: Try[TreehubConfig] = TufRepo.readConfigFile(repoPath).map(_.treehub)

  def authConfig: Try[Option[AuthConfig]] = TufRepo.readConfigFile(repoPath).map(_.auth)

  def repoServerUri: Try[URI] = TufRepo.readConfigFile(repoPath).map(_.reposerver)

  private def fetchRootChain(reposerverClient: UserTufServerClient, from: Int, to: Int): Future[Vector[SignedPayload[RootRole]]] = {
    val versionsToFetch = from until to

    val rootRolesF = versionsToFetch.foldLeft(Future.successful(Vector.empty[SignedPayload[RootRole]])) { (accF, vv) =>
      for {
        acc <- accF
        payload <- reposerverClient.root(vv.some).recoverWith {
          case RoleNotFound(msg) => Future.failed(RootPullError(NonEmptyList.of(msg)))
        }
      } yield acc :+ payload
    }

    rootRolesF
  }

}

class RepoServerRepo(name: RepoName, repoPath: Path)(implicit ec: ExecutionContext) extends TufRepo(name, repoPath) {

  def initTargets(version: Int, expires: Instant): Try[Path] = {
    val emptyTargets = TargetsRole(expires, Map.empty, version)
    writeUnsignedRole(emptyTargets)
  }

  def ensureTargetsPulled(reposerverClient: UserReposerverClient, rootRole: RootRole): Future[Unit] = {
    Future.fromTry {
      readUnsignedRole[TargetsRole].map(_ => ())
    }.recoverWith {
      case _ =>
        log.warn("unsigned targets not available locally, pulling targets from server")
        pullTargets(reposerverClient, rootRole).map(_ => ())
    }
  }

  private def writeTargets(targets: SignedPayload[TargetsRole], checksum: Refined[String, ValidChecksum]): Try[Unit] =
    writeUnsignedRole(targets.signed).flatMap(_ => writeChecksum(checksum))

  def pullTargets(reposerverClient: UserReposerverClient, rootRole: RootRole): Future[SignedPayload[TargetsRole]] =
    reposerverClient.targets().flatMap {
      case TargetsResponse(targets, checksum) =>
        val roleValidation = TufCrypto.payloadSignatureIsValid(rootRole, targets)

        roleValidation match {
          case Valid(_) if checksum.isDefined => writeTargets(targets, checksum.get).map(_ => targets).toFuture
          case Valid(_) => Future.failed(TargetsPullError("Did not receive valid role checksum from reposerver"))
          case Invalid(s) => Future.failed(TargetsPullError(s.toList.mkString(", ")))
        }
    }

  def pushTargets(reposerverClient: UserReposerverClient): Future[SignedPayload[TargetsRole]] =
    readSignedRole[TargetsRole].toFuture.flatMap { targets =>
      log.debug(s"pushing ${targets.asJson.spaces2}")

      for {
        checksum <- readChecksum[TargetsRole].toFuture
        _ <- reposerverClient.pushTargets(targets, checksum.some)
      } yield targets
    }

  def deleteTarget(filename: TargetFilename): Try[Path] = for {
    targetsRole <- readUnsignedRole[TargetsRole]
    newTargets <- if(targetsRole.targets.contains(filename))
      Success(targetsRole.copy(targets = targetsRole.targets - filename))
    else
      Failure(new IllegalArgumentException(s"Target $filename not found in unsigned targets.json"))
    path <- writeUnsignedRole(newTargets)
  } yield path

  def addTarget(name: TargetName, version: TargetVersion, length: Int, checksum: Refined[String, ValidChecksum],
                hardwareIds: List[HardwareIdentifier], url: Option[URI], format: TargetFormat): Try[Path] = {
    for {
      targetsRole <- readUnsignedRole[TargetsRole]
      targetFilename <- refineV[ValidTargetFilename](s"${name.value}-${version.value}").leftMap(s => new IllegalArgumentException(s)).toTry
      newTargetRole = {
        val custom = TargetCustom(name, version, hardwareIds, format.some, url)
        val clientHashes = Map(HashMethod.SHA256 -> checksum)
        val newTarget = ClientTargetItem(clientHashes, length, custom.asJson.some)

        targetsRole.copy(targets = targetsRole.targets + (targetFilename -> newTarget))
      }
      path <- writeUnsignedRole(newTargetRole)
    } yield path
  }

  def signTargets(targetsKeys: Seq[KeyName], version: Option[Int] = None): Try[Path] =
    signRole[TargetsRole](version, targetsKeys)

  def moveRootOffline(repoClient: UserReposerverClient,
                      newRootName: KeyName,
                      oldRootName: KeyName,
                      newTargetsName: KeyName,
                      oldKeyId: Option[KeyId]): Future[SignedPayload[RootRole]] = {
    for {
      (newRootPubKey, newRootPrivKey) <- keyStorage.readKeyPair(newRootName).toFuture
      (newTargetsPubKey, _) <- keyStorage.readKeyPair(newTargetsName).toFuture
      oldRootRole <- repoClient.root().map(_.signed)
      _ <- ensureTargetsPulled(repoClient, oldRootRole)
      oldRootPubKeyId = oldKeyId.getOrElse(oldRootRole.roles(RoleType.ROOT).keyids.last)
      oldTargetsKeyIds = oldRootRole.roles(RoleType.TARGETS).keyids
      oldRootPubKey = oldRootRole.keys(oldRootPubKeyId)
      oldRootPrivKey <- deleteOrReadKey(repoClient, oldRootName, oldRootPubKeyId)
      _ <- keyStorage.writeKeys(oldRootName, oldRootPubKey, oldRootPrivKey).toFuture
      newKeySet = (oldRootRole.keys -- (oldTargetsKeyIds :+ oldRootPubKeyId)) ++ Map(newRootPubKey.id -> newRootPubKey, newTargetsPubKey.id -> newTargetsPubKey)
      newRootRoleKeys = RoleKeys(Seq(newRootPubKey.id), threshold = 1)
      newTargetsRoleKeys = RoleKeys(Seq(newTargetsPubKey.id), threshold = 1)
      newRootRoleMap = oldRootRole.roles ++ Map(RoleType.ROOT -> newRootRoleKeys, RoleType.TARGETS -> newTargetsRoleKeys)
      newExpireTime = oldRootRole.expires.plus(DEFAULT_ROOT_EXPIRE_TIME)
      newRootRole = oldRootRole.copy(keys = newKeySet, roles = newRootRoleMap, version = oldRootRole.version + 1, newExpireTime)
      newRootSignature = TufCrypto.signPayload(newRootPrivKey, newRootRole.asJson).toClient(newRootPubKey.id)
      newRootOldSignature = TufCrypto.signPayload(oldRootPrivKey, newRootRole.asJson).toClient(oldRootPubKeyId)
      newSignedRoot = SignedPayload(Seq(newRootSignature, newRootOldSignature), newRootRole, newRootRole.asJson)
      _ = log.debug(s"pushing ${newSignedRoot.asJson.spaces2}")
      _ <- repoClient.pushSignedRoot(newSignedRoot)
      _ <- writeSignedRole(newSignedRoot).toFuture
    } yield newSignedRoot
  }

}

class DirectorRepo(name: RepoName, repoPath: Path)(implicit ec: ExecutionContext) extends TufRepo(name, repoPath) {

  def ensureTargetsOnline(repoClient: UserDirectorClient, rootRole: RootRole): Future[Unit] = {
    val targetRoleKeys = rootRole.roles(RoleType.TARGETS)
    val keyPairs = targetRoleKeys.keyids.map { keyId =>
      repoClient.fetchKeyPair(keyId)
    }

    Future.sequence(keyPairs).map(_ => ())
  }

  def moveRootOffline(repoClient: UserDirectorClient,
                      newRootName: KeyName,
                      oldRootName: KeyName,
                      oldKeyId: Option[KeyId]): Future[SignedPayload[RootRole]] = {
    for {
      (newRootPubKey, newRootPrivKey) <- keyStorage.readKeyPair(newRootName).toFuture
      oldRootRole <- repoClient.root().map(_.signed)
      _ <- ensureTargetsOnline(repoClient, oldRootRole)
      oldRootPubKeyId = oldKeyId.getOrElse(oldRootRole.roles(RoleType.ROOT).keyids.last)
      oldRootPubKey = oldRootRole.keys(oldRootPubKeyId)
      oldRootPrivKey <- deleteOrReadKey(repoClient, oldRootName, oldRootPubKeyId)
      _ <- keyStorage.writeKeys(oldRootName, oldRootPubKey, oldRootPrivKey).toFuture
      newKeySet = (oldRootRole.keys - oldRootPubKeyId) ++ Map(newRootPubKey.id -> newRootPubKey)
      newRootRoleKeys = RoleKeys(Seq(newRootPubKey.id), threshold = 1)
      newRootRoleMap = oldRootRole.roles ++ Map(RoleType.ROOT -> newRootRoleKeys)
      newExpireTime = oldRootRole.expires.plus(DEFAULT_ROOT_EXPIRE_TIME)
      newRootRole = oldRootRole.copy(keys = newKeySet, roles = newRootRoleMap, version = oldRootRole.version + 1, newExpireTime)
      newRootSignature = TufCrypto.signPayload(newRootPrivKey, newRootRole.asJson).toClient(newRootPubKey.id)
      newRootOldSignature = TufCrypto.signPayload(oldRootPrivKey, newRootRole.asJson).toClient(oldRootPubKeyId)
      newSignedRoot = SignedPayload(Seq(newRootSignature, newRootOldSignature), newRootRole, newRootRole.asJson)
      _ = log.debug(s"pushing ${newSignedRoot.asJson.spaces2}")
      _ <- repoClient.pushSignedRoot(newSignedRoot)
      _ <- writeSignedRole(newSignedRoot).toFuture
    } yield newSignedRoot
  }
}
