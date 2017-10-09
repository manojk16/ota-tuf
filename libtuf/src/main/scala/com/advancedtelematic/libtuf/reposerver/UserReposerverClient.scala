package com.advancedtelematic.libtuf.reposerver

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Slash
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{RootRole, TargetsRole}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{KeyId, SignedPayload, TufKey, TufPrivateKey}
import com.advancedtelematic.libtuf.http.SHttpjServiceClient
import io.circe.Decoder

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scalaj.http.{Http, HttpRequest, HttpResponse}

trait UserReposerverClient {
  def root(): Future[SignedPayload[RootRole]]

  def deleteKey(keyId: KeyId): Future[TufPrivateKey]

  def pushSignedRoot(signedRoot: SignedPayload[RootRole]): Future[Unit]

  def targets(): Future[SignedPayload[TargetsRole]]

  def pushTargets(targetsRole: SignedPayload[TargetsRole]): Future[Unit]

  def pushTargetsKey(key: TufKey): Future[TufKey]
}

class UserReposerverHttpClient(reposerverUri: Uri,
                               httpClient: HttpRequest => Future[HttpResponse[Array[Byte]]],
                               token: String)(implicit ec: ExecutionContext)
  extends SHttpjServiceClient(httpClient) with UserReposerverClient {

  override protected def execHttp[T : ClassTag : Decoder](request: HttpRequest)(errorHandler: PartialFunction[Int, Future[T]]) =
    super.execHttp(request.header("Authorization", s"Bearer $token"))(errorHandler)

  private def apiUri(path: Path): String =
    reposerverUri.withPath(Path("/api") / "v1" / "user_repo" ++ Slash(path)).toString()

  def root(): Future[SignedPayload[RootRole]] = {
    val req = Http(apiUri(Path("root.json"))).method("GET")
    execHttp[SignedPayload[RootRole]](req)()
  }

  def deleteKey(keyId: KeyId): Future[TufPrivateKey] = {
    val req = Http(apiUri(Path("root") / "private_keys" / keyId.value)).method("DELETE")
    execHttp[TufPrivateKey](req)()
  }

  def pushSignedRoot(signedRoot: SignedPayload[RootRole]): Future[Unit] = {
    val req = Http(apiUri(Path("root"))).method("POST")
    execJsonHttp[Unit, SignedPayload[RootRole]](req, signedRoot)()
  }

  def targets(): Future[SignedPayload[TargetsRole]] = {
    val req = Http(apiUri(Path("targets.json"))).method("GET")
    execHttp[SignedPayload[TargetsRole]](req)()
  }

  def pushTargets(role: SignedPayload[TargetsRole]): Future[Unit] = {
    val req = Http(apiUri(Path("targets"))).method("PUT")
    execJsonHttp[Unit, SignedPayload[TargetsRole]](req, role)()
  }

  override def pushTargetsKey(key: TufKey): Future[TufKey] = {
    val req = Http(apiUri(Path("keys") / "targets")).method("PUT")
    execJsonHttp[Unit, TufKey](req, key)().map(_ => key)
  }
}