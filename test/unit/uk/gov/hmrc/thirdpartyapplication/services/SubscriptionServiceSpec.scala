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

package unit.uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.{DateTime, DateTimeUtils}
import org.mockito.BDDMockito.given
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.libs.ws.WSResponse
import uk.gov.hmrc.thirdpartyapplication.connector.{ApiDefinitionConnector, EmailConnector}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.{BRONZE, GOLD, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.{ExecutionContext, Future}

class SubscriptionServiceSpec extends UnitSpec with ScalaFutures with MockitoSugar with BeforeAndAfterAll with ApplicationStateUtil {

  trait Setup {

    lazy val locked = false
    val mockWSO2APIStore = mock[Wso2ApiStore]
    val mockApplicationRepository = mock[ApplicationRepository]
    val mockStateHistoryRepository = mock[StateHistoryRepository]
    val mockApiDefinitionConnector = mock[ApiDefinitionConnector]
    val mockAuditService = mock[AuditService]
    val mockEmailConnector = mock[EmailConnector]
    val mockSubscriptionRepository = mock[SubscriptionRepository]
    val response = mock[WSResponse]

    val trustedApplicationConfig = TrustedApplicationsConfig(Seq(trustedApplicationId.toString))

    implicit val hc = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> loggedInUser,
      LOGGED_IN_USER_NAME_HEADER -> "John Smith"
    )

    val underTest = new SubscriptionService(
      mockApplicationRepository, mockSubscriptionRepository, mockApiDefinitionConnector, mockAuditService, mockWSO2APIStore, trustedApplicationConfig)

    when(mockWSO2APIStore.createApplication(any(), any(), any())(any[HeaderCarrier])).thenReturn(successful(ApplicationTokens(productionToken, sandboxToken)))
    when(mockApplicationRepository.save(any())).thenAnswer(new Answer[Future[ApplicationData]] {
      override def answer(invocation: InvocationOnMock): Future[ApplicationData] = {
        successful(invocation.getArguments()(0).asInstanceOf[ApplicationData])
      }
    })
    when(mockApiDefinitionConnector.fetchAllAPIs(any())(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier], any[ExecutionContext]))
      .thenReturn(Seq(anAPIDefinition()))
    when(mockWSO2APIStore.getSubscriptions(any(), any(), any())(any[HeaderCarrier])).thenReturn(Seq())
    when(mockWSO2APIStore.addSubscription(any(), any(), any(), any(), any())(any[HeaderCarrier])).thenReturn(HasSucceeded)
    when(mockWSO2APIStore.removeSubscription(any(), any(), any(), any())(any[HeaderCarrier])).thenReturn(HasSucceeded)
    when(mockSubscriptionRepository.add(any(), any())).thenReturn(HasSucceeded)
    when(mockSubscriptionRepository.remove(any(), any())).thenReturn(HasSucceeded)
  }

  private def aSecret(secret: String): ClientSecret = {
    ClientSecret(secret, secret)
  }

  private val loggedInUser = "loggedin@example.com"
  private val productionToken = EnvironmentToken("aaa", "bbb", "wso2Secret", Seq(aSecret("secret1"), aSecret("secret2")))
  private val sandboxToken = EnvironmentToken("111", "222", "wso2SandboxSecret", Seq(aSecret("secret3"), aSecret("secret4")))
  private val trustedApplicationId = UUID.randomUUID()

  override def beforeAll() {
    DateTimeUtils.setCurrentMillisFixed(DateTimeUtils.currentTimeMillis())
  }

  override def afterAll() {
    DateTimeUtils.setCurrentMillisSystem()
  }

  "isSubscribed" should {
    val applicationId = UUID.randomUUID()
    val api = APIIdentifier("context", "1.0")

    "return true when the application is subscribed to a given API version" in new Setup {
      given(mockSubscriptionRepository.isSubscribed(applicationId, api)).willReturn(true)

      val result = await(underTest.isSubscribed(applicationId, api))

      result shouldBe true
    }

    "return false when the application is not subscribed to a given API version" in new Setup {
      given(mockSubscriptionRepository.isSubscribed(applicationId, api)).willReturn(false)

      val result = await(underTest.isSubscribed(applicationId, api))

      result shouldBe false
    }
  }

  "fetchAllSubscriptionsForApplication" should {
    val applicationId = UUID.randomUUID()

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(None))

      intercept[NotFoundException] {
        await(underTest.fetchAllSubscriptionsForApplication(applicationId))
      }
    }

    "fetch all API subscriptions from api-definition for the given application id when an application exists" in new Setup {
      val applicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetch(applicationId))
        .thenReturn(successful(Some(applicationData)))
      when(mockApiDefinitionConnector.fetchAllAPIs(refEq(applicationId))(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier](), any[ExecutionContext]()))
        .thenReturn(Seq(anAPIDefinition("context", Seq(anAPIVersion("1.0"), anAPIVersion("2.0")))))
      when(mockWSO2APIStore.getSubscriptions(any(), any(), any())(any[HeaderCarrier]))
        .thenReturn(successful(Seq(anAPI("context", "1.0"))))

      val result = await(underTest.fetchAllSubscriptionsForApplication(applicationId))

      result shouldBe Seq(ApiSubscription("name", "service", "context", Seq(
        VersionSubscription(ApiVersion("1.0", ApiStatus.STABLE, None), subscribed = true),
        VersionSubscription(ApiVersion("2.0", ApiStatus.STABLE, None), subscribed = false)
      ), Some(false))
      )
    }

    "fetch APIs which require trust for a trusted application" in new Setup {
      val applicationData = anApplicationData(trustedApplicationId)
      val requiresTrustAPI = anAPIDefinition("context", Seq(anAPIVersion("1.0"))).copy(requiresTrust = Some(true))

      when(mockApplicationRepository.fetch(trustedApplicationId)).thenReturn(successful(Some(applicationData)))
      when(mockApiDefinitionConnector
        .fetchAllAPIs(refEq(trustedApplicationId))(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Seq(requiresTrustAPI))

      val result = await(underTest.fetchAllSubscriptionsForApplication(trustedApplicationId))

      result shouldBe Seq(ApiSubscription("name", "service", "context", Seq(
        VersionSubscription(ApiVersion("1.0", ApiStatus.STABLE, None), subscribed = false)), Some(true))
      )
    }

    "filter APIs which require trust for a non trusted application" in new Setup {
      val applicationData = anApplicationData(applicationId)
      val requiresTrustAPI = anAPIDefinition("context", Seq(anAPIVersion("1.0"))).copy(requiresTrust = Some(true))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      when(mockApiDefinitionConnector.fetchAllAPIs(refEq(applicationId))(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Seq(requiresTrustAPI))

      val result = await(underTest.fetchAllSubscriptionsForApplication(applicationId))

      result shouldBe Seq()
    }
  }

  "createSubscriptionForApplication" should {
    val applicationId = UUID.randomUUID()
    val applicationData = anApplicationData(applicationId, rateLimitTier = Some(GOLD))
    val api = anAPI()

    "create a subscription in WSO2 and Mongo for the given application when an application exists in the repository" in new Setup {

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      when(mockApiDefinitionConnector.fetchAllAPIs(refEq(applicationId))(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Seq(anAPIDefinition()))

      val result = await(underTest.createSubscriptionForApplication(applicationId, api))

      result shouldBe HasSucceeded
      verify(mockAuditService).audit(refEq(Subscribed), any[Map[String, String]])(refEq(hc))
      verify(mockWSO2APIStore).addSubscription(refEq(applicationData.wso2Username), refEq(applicationData.wso2Password),
        refEq(applicationData.wso2ApplicationName), refEq(api), refEq(Some(GOLD)))(any[HeaderCarrier])
      verify(mockWSO2APIStore).addSubscription(refEq(applicationData.wso2Username), refEq(applicationData.wso2Password),
        refEq(applicationData.wso2ApplicationName), refEq(api), refEq(Some(GOLD)))(any[HeaderCarrier])
      verify(mockSubscriptionRepository).add(applicationId, api)
    }

    "create a subscription in WSO2 and Mongo when the API requires trust and the application is trusted" in new Setup {
      val trustedApplication = anApplicationData(trustedApplicationId)
      val trustedApi = anAPIDefinition().copy(requiresTrust = Some(true))

      when(mockApplicationRepository.fetch(trustedApplicationId)).thenReturn(successful(Some(trustedApplication)))
      when(mockApiDefinitionConnector
        .fetchAllAPIs(refEq(trustedApplicationId))(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Seq(trustedApi))

      val result = await(underTest.createSubscriptionForApplication(trustedApplicationId, api))

      result shouldBe HasSucceeded
      verify(mockWSO2APIStore).addSubscription(refEq(applicationData.wso2Username), refEq(applicationData.wso2Password),
        refEq(applicationData.wso2ApplicationName), refEq(api), refEq(Some(RateLimitTier.BRONZE)))(any[HeaderCarrier])
      verify(mockWSO2APIStore).addSubscription(refEq(applicationData.wso2Username), refEq(applicationData.wso2Password),
        refEq(applicationData.wso2ApplicationName), refEq(api), refEq(Some(BRONZE)))(any[HeaderCarrier])
      verify(mockSubscriptionRepository).add(trustedApplicationId, api)
    }

    "throw SubscriptionAlreadyExistsException if already subscribed" in new Setup {

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      when(mockApiDefinitionConnector.fetchAllAPIs(refEq(applicationId))(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Seq(anAPIDefinition()))
      when(mockWSO2APIStore.getSubscriptions(any(), any(), any())(any[HeaderCarrier])).thenReturn(Seq(api))

      intercept[SubscriptionAlreadyExistsException] {
        await(underTest.createSubscriptionForApplication(applicationId, api))
      }

      verify(mockWSO2APIStore, never).addSubscription(any(), any(), any(), any(), any())(any[HeaderCarrier])
    }

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(None)
      when(mockApiDefinitionConnector.fetchAllAPIs(refEq(applicationId))(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Seq(anAPIDefinition()))

      intercept[NotFoundException] {
        await(underTest.createSubscriptionForApplication(applicationId, api))
      }

      verify(mockWSO2APIStore, never).addSubscription(any(), any(), any(), any(), any())(any[HeaderCarrier])
    }

    "throw a NotFoundException when the API does not exist" in new Setup {

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      when(mockApiDefinitionConnector.fetchAllAPIs(refEq(applicationId))(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Seq.empty)

      intercept[NotFoundException] {
        await(underTest.createSubscriptionForApplication(applicationId, api))
      }

      verify(mockWSO2APIStore, never).addSubscription(any(), any(), any(), any(), any())(any[HeaderCarrier])
    }

    "throw a NotFoundException when the version does not exist for the given context" in new Setup {
      val apiWithWrongVersion = api.copy(version = "10.0")

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      when(mockApiDefinitionConnector.fetchAllAPIs(refEq(applicationId))(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier](), any[ExecutionContext]()))
        .thenReturn(Seq(anAPIDefinition()))

      intercept[NotFoundException] {
        await(underTest.createSubscriptionForApplication(applicationId, apiWithWrongVersion))
      }
      verify(mockWSO2APIStore, never).addSubscription(any(), any(), any(), any(), any())(any[HeaderCarrier])
    }

    "throw a NotFoundException when the API requires trust and the application is not trusted" in new Setup {
      val trustedApi = anAPIDefinition().copy(requiresTrust = Some(true))

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      when(mockApiDefinitionConnector.fetchAllAPIs(refEq(applicationId))(any[HttpReads[Seq[ApiDefinition]]](), any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Seq(trustedApi))

      intercept[NotFoundException] {
        await(underTest.createSubscriptionForApplication(applicationId, api))
      }
      verify(mockWSO2APIStore, never).addSubscription(any(), any(), any(), any(), any())(any[HeaderCarrier])
    }
  }

  "removeSubscriptionForApplication" should {
    val applicationId = UUID.randomUUID()
    val api = anAPI()

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(None))

      intercept[NotFoundException] {
        await(underTest.removeSubscriptionForApplication(applicationId, api))
      }
    }

    "remove the API subscription from WSO2 and Mongo for the given application id when an application exists" in new Setup {
      val applicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))

      val result = await(underTest.removeSubscriptionForApplication(applicationId, api))

      result shouldBe HasSucceeded
      verify(mockSubscriptionRepository).remove(applicationId, api)
      verify(mockWSO2APIStore).removeSubscription(any(), any(), any(), any())(any[HeaderCarrier])
      verify(mockAuditService).audit(refEq(Unsubscribed), any[Map[String, String]])(refEq(hc))
    }
  }

  "refreshSubscriptions" should {
    val applicationId = UUID.randomUUID()
    val api = anAPI()
    val applicationData = anApplicationData(applicationId)

    "add in Mongo the subscriptions present in WSO2 and not in Mongo" in new Setup {

      given(mockApplicationRepository.findAll()).willReturn(List(applicationData))
      given(mockWSO2APIStore.getSubscriptions(
        refEq(applicationData.wso2Username), refEq(applicationData.wso2Password), refEq(applicationData.wso2ApplicationName))(any[HeaderCarrier]))
        .willReturn(Seq(api))
      given(mockSubscriptionRepository.getSubscriptions(applicationId)).willReturn(Seq.empty)

      val result = await(underTest.refreshSubscriptions())

      result shouldBe 1
      verify(mockSubscriptionRepository).add(applicationId, api)
    }

    "remove from Mongo the subscriptions not present in WSO2 " in new Setup {

      given(mockApplicationRepository.findAll()).willReturn(List(applicationData))
      given(mockWSO2APIStore.getSubscriptions(
        refEq(applicationData.wso2Username), refEq(applicationData.wso2Password), refEq(applicationData.wso2ApplicationName))(any[HeaderCarrier]))
        .willReturn(Seq.empty)
      given(mockSubscriptionRepository.getSubscriptions(applicationId)).willReturn(Seq(api))

      val result = await(underTest.refreshSubscriptions())

      result shouldBe 1
      verify(mockSubscriptionRepository).remove(applicationId, api)
    }

    "process multiple applications" in new Setup {
      val applicationId2 = UUID.randomUUID()
      val applicationData2 = anApplicationData(applicationId2)

      given(mockApplicationRepository.findAll()).willReturn(List(applicationData, applicationData2))
      given(mockWSO2APIStore.getSubscriptions(any(), any(), any())(any[HeaderCarrier])).willReturn(Seq(api))
      given(mockSubscriptionRepository.getSubscriptions(any())).willReturn(Seq.empty)

      val result = await(underTest.refreshSubscriptions())

      result shouldBe 2
      verify(mockSubscriptionRepository).add(applicationId, api)
      verify(mockSubscriptionRepository).add(applicationId2, api)
    }

    "not refresh the subscriptions when fetching the subscriptions from WSO2 fail" in new Setup {

      given(mockApplicationRepository.findAll()).willReturn(List(applicationData))
      given(mockWSO2APIStore.getSubscriptions(
        refEq(applicationData.wso2Username), refEq(applicationData.wso2Password), refEq(applicationData.wso2ApplicationName))(any[HeaderCarrier]))
        .willReturn(failed(new RuntimeException("Something went wrong")))
      given(mockSubscriptionRepository.getSubscriptions(applicationId)).willReturn(Seq(api))

      intercept[RuntimeException] {
        await(underTest.refreshSubscriptions())
      }

      verify(mockSubscriptionRepository, never()).remove(applicationId, api)
    }
  }

  private val requestedByEmail = "john.smith@example.com"

  private def anApplicationData(applicationId: UUID, state: ApplicationState = productionState(requestedByEmail),
                                collaborators: Set[Collaborator] = Set(Collaborator(loggedInUser, ADMINISTRATOR)),
                                rateLimitTier: Option[RateLimitTier] = Some(BRONZE)) = {
    new ApplicationData(
      applicationId,
      "MyApp",
      "myapp",
      collaborators,
      Some("description"),
      "aaaaaaaaaa",
      "aaaaaaaaaa",
      "aaaaaaaaaa",
      ApplicationTokens(productionToken, sandboxToken), state,
      Standard(Seq(), None, None),
      new DateTime(),
      rateLimitTier
    )
  }

  private def anAPIVersion(version: String) = ApiVersion(version, ApiStatus.STABLE, None)

  private def anAPIDefinition(context: String = "some-context", versions: Seq[ApiVersion] = Seq(anAPIVersion("1.0"))) =
    ApiDefinition("service", "name", context, versions, Some(false))

  private def anAPI(context: String = "some-context", version: String = "1.0") = {
    new APIIdentifier(context, version)
  }

}
