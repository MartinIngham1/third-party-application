/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package unit.uk.gov.hmrc.thirdpartyapplication.connector

import java.util.UUID

import common.uk.gov.hmrc.thirdpartyapplication.common.LogSuppressing
import org.mockito.Matchers.{eq => meq, _}
import org.mockito.Mockito.{verify, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.http.{HeaderCarrier, Upstream5xxResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.connector.{ApiDefinitionConfig, ApiDefinitionConnector}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.{ApiDefinition, ApiStatus, ApiVersion}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApiDefinitionConnectorSpec extends UnitSpec with MockitoSugar with ScalaFutures with LogSuppressing {

  implicit val hc = HeaderCarrier()
  val baseUrl = s"https://example.com"

  val apiDefinitionWithStableStatus = ApiDefinition("api-service", "api-name", "api-context",
    Seq(ApiVersion("1.0", ApiStatus.STABLE, None)), Some(false))

  val apiDefinitionWithBetaStatus = ApiDefinition("api-service", "api-name", "api-context",
    Seq(ApiVersion("1.0", ApiStatus.BETA, None)), Some(false))

  trait Setup {
    val applicationName: String = "third-party-application"
    val config = ApiDefinitionConfig(baseUrl)
    val mockHttpClient = mock[HttpClient]

    val underTest = new ApiDefinitionConnector(mockHttpClient, config)

    def apiDefinitionWillReturn(result: Future[Seq[ApiDefinition]]) = {
      when(mockHttpClient.GET[Seq[ApiDefinition]](any())(any(), any(), any())).thenReturn(result)
    }

    def verifyApiDefinitionCalled(applicationId: UUID) = {
      val expectedUrl = s"${config.baseUrl}/api-definition?applicationId=$applicationId"
      verify(mockHttpClient).GET[Seq[ApiDefinition]](meq(expectedUrl))(any(), any(), any())
    }
  }

  "fetchAPIs" should {

    val applicationId: UUID = UUID.randomUUID()

    "return the APIs available for an application" in new Setup {

      apiDefinitionWillReturn(Seq(apiDefinitionWithStableStatus, apiDefinitionWithBetaStatus))

      val result = await(underTest.fetchAllAPIs(applicationId))

      result shouldBe Seq(apiDefinitionWithStableStatus, apiDefinitionWithBetaStatus)
      verifyApiDefinitionCalled(applicationId)
    }

    "fail when api-definition returns a 500" in new Setup {

      apiDefinitionWillReturn(Future.failed(Upstream5xxResponse("", INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR)))

      intercept[RuntimeException] {
        await(underTest.fetchAllAPIs(applicationId))
      }

      verifyApiDefinitionCalled(applicationId)
    }

  }
}