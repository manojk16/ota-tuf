package com.advancedtelematic.ota_tuf.http

import com.advancedtelematic.ota_tuf.data.DataType._
import com.advancedtelematic.util.OtaTufSpec
import io.circe.syntax._
import CanonicalJson._
import com.advancedtelematic.ota_tuf.vault.VaultClient.VaultKey
import org.genivi.sota.core.DatabaseSpec
import com.advancedtelematic.libtuf.crypt.RsaKeyPair._
import cats.syntax.show.toShowOps
import com.advancedtelematic.libtuf.crypt.RsaKeyPair
import com.advancedtelematic.libtuf.data.CommonDataType.{KeyType, Signature}
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.ExecutionContext

case class TestPayload(propertyB: String = "some B", propertyA: String = "some A",
                       arrayMapProp: List[Map[String, Int]] = List(Map("bbb" -> 1, "aaa" -> 0)),
                       mapProp: Map[String, Int] = Map("bb" -> 1, "aa" -> 0))

class RoleSigningSpec extends OtaTufSpec with DatabaseSpec with PatienceConfiguration {

  implicit val ec = ExecutionContext.global

  override implicit def patienceConfig = PatienceConfig().copy(timeout = Span(3, Seconds))

  val keyPair = RsaKeyPair.generate(1024)

  val dbKey = Key(keyPair.id, RoleId.generate(), KeyType.RSA, keyPair.getPublic)

  implicit val encoder = io.circe.generic.semiauto.deriveEncoder[TestPayload]
  implicit val decoder = io.circe.generic.semiauto.deriveDecoder[TestPayload]

  lazy val roleSigning = {
    fakeVault.createKey(VaultKey(dbKey.id, dbKey.keyType, dbKey.publicKey.show, keyPair.getPrivate.show)).futureValue
    new RoleSigning(fakeVault)
  }

  test("signs a payload with a key") {
    val payload = TestPayload()

    val signature = roleSigning.signForClient(payload)(dbKey).futureValue

    signature.sig.get should have size 256
  }

  test("generates valid signatures")  {
    val payload = TestPayload()

    val clientSignature = roleSigning.signForClient(payload)(dbKey).futureValue
    val signature = Signature(clientSignature.sig, clientSignature.method)

    RsaKeyPair.isValid(keyPair.getPublic, signature, payload.asJson.canonical.getBytes) shouldBe true
  }

  test("generates valid signatures when verifying with canonical representation") {
    val payload = TestPayload()

    val clientSignature = roleSigning.signForClient(payload)(dbKey).futureValue
    val signature = Signature(clientSignature.sig, clientSignature.method)

    val canonicalJson = """{"arrayMapProp":[{"aaa":0,"bbb":1}],"mapProp":{"aa":0,"bb":1},"propertyA":"some A","propertyB":"some B"}""".getBytes

    RsaKeyPair.isValid(keyPair.getPublic, signature, canonicalJson) shouldBe true
  }
}