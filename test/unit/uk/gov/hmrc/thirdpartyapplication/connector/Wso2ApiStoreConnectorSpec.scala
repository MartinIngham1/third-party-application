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

import org.mockito.Matchers.{any, eq => meq}
import org.mockito.Mockito.{verify, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.http.ContentTypes.FORM
import play.api.http.HeaderNames.CONTENT_TYPE
import play.api.http.Status
import play.api.http.Status._
import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.connector.{Wso2ApiStoreConfig, Wso2ApiStoreConnector}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class Wso2ApiStoreConnectorSpec extends UnitSpec with MockitoSugar with ScalaFutures {

  implicit val hc = HeaderCarrier()
  private val baseUrl = s"http://example.com"

  private trait Setup {
    val serviceName = "third-party-application"
    val applicationName: String = "third-party-application"
    val adminUsername = "admin"
    val cookie = "login-cookie-123"
    val mockHttpClient = mock[HttpClient]
    val config = Wso2ApiStoreConfig(baseUrl, adminUsername)
    val underTest = new Wso2ApiStoreConnector(mockHttpClient, config)
  }

  "login" should {

    "log the user into WSO2 API Store and return the cookies" in new Setup {

      val username = "user1"
      val password = "user1pw"
      val url = s"$baseUrl/store/site/blocks/user/login/ajax/login.jag"
      val body: String = s"action=login&username=$username&password=$password"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM)
      val responseBody = Some(Json.parse("""{"error":false}""""))
      val responseHeaders = Map(SET_COOKIE -> Seq("JSESSIONID=12345", "api-store=loadbalancercookie"))
      when(mockHttpClient.POSTString[HttpResponse](any(), any(), any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(OK, responseBody, responseHeaders)))

      val result = Await.result(underTest.login(username, password), 1.second)

      result shouldBe "JSESSIONID=12345;api-store=loadbalancercookie"
      verify(mockHttpClient).POSTString[HttpResponse](meq(url), meq(body), meq(headers))(any(), meq(hc), any())
    }
  }

  "getSubscriptions" should {


    "get API subscriptions for a given application" in new Setup {

      val url = s"$baseUrl/store/site/blocks/subscription/subscription-list/ajax/subscription-list.jag"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM, COOKIE -> cookie)
      val body = s"action=getSubscriptionByApplication&app=$applicationName"
      val responseBody = Some(Json.parse(
        """{
          |  "error":false,
          |  "apis":[
          |    {"apiName":"some--context--1.0","apiVersion":"1.0"},
          |    {"apiName":"some--context--1.0","apiVersion":"1.1"}
          |  ]
          |}"""
          .stripMargin)
      )
      when(mockHttpClient.POSTString[HttpResponse](any(), any(), any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(OK, responseBody)))

      val result = Await.result(underTest.getSubscriptions(cookie, applicationName), 1.second)

      result.seq.head.name shouldBe "some--context--1.0"
      result.seq.head.version shouldBe "1.0"
      result.seq(1).name shouldBe "some--context--1.0"
      result.seq(1).version shouldBe "1.1"
      verify(mockHttpClient).POSTString[HttpResponse](meq(url), meq(body), meq(headers))(any(), meq(hc), any())
    }
  }

  "getAllSubscriptions" should {

    "get all API subscriptions" in new Setup {

      val url = s"$baseUrl/store/site/blocks/subscription/subscription-list/ajax/subscription-list.jag"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM, COOKIE -> cookie)
      val body = "action=getAllSubscriptions"
      val responseBody = Some(Json.parse(
        """{
          |  "error": false,
          |  "subscriptions": {
          |    "applications": [
          |      {
          |        "id": 1,
          |        "name": "DefaultApplication",
          |        "callbackUrl": null,
          |        "prodKey": null,
          |        "prodKeyScope": null,
          |        "prodKeyScopeValue": null,
          |        "prodConsumerKey": null,
          |        "prodConsumerSecret": null,
          |        "prodRegenarateOption": true,
          |        "prodAuthorizedDomains": null,
          |        "prodValidityTime": 3600,
          |        "prodJsonString": null,
          |        "sandboxKey": null,
          |        "sandKeyScope": null,
          |        "sandKeyScopeValue": null,
          |        "sandboxConsumerKey": null,
          |        "sandboxConsumerSecret": null,
          |        "sandRegenarateOption": true,
          |        "sandboxAuthorizedDomains": null,
          |        "sandboxJsonString": null,
          |        "sandValidityTime": 3600,
          |        "subscriptions": [
          |          {
          |            "name": "pizzashack--1.0.0",
          |            "provider": "admin",
          |            "version": "1.0.0",
          |            "status": "PUBLISHED",
          |            "tier": "Unlimited",
          |            "subStatus": "UNBLOCKED",
          |            "thumburl": "/registry/resource/_system/governance/apimgt/applicationdata/icons/admin/PizzaShackAPI/1.0.0/icon",
          |            "context": "/pizzashack/1.0.0",
          |            "businessOwner": "Jane Roe",
          |            "prodKey": null,
          |            "prodConsumerKey": null,
          |            "prodConsumerSecret": null,
          |            "prodAuthorizedDomains": null,
          |            "prodValidityTime": 3600000,
          |            "sandboxKey": null,
          |            "sandboxConsumerKey": null,
          |            "sandboxConsumerSecret": null,
          |            "sandAuthorizedDomains": null,
          |            "sandValidityTime": 3600000,
          |            "hasMultipleEndpoints": "false"
          |          },
          |          {
          |            "name": "another-sample--v1.0",
          |            "provider": "admin",
          |            "version": "v1.0",
          |            "status": "PUBLISHED",
          |            "tier": "Unlimited",
          |            "subStatus": "UNBLOCKED",
          |            "thumburl": null,
          |            "context": "/another-sample/v1.0",
          |            "businessOwner": null,
          |            "prodKey": null,
          |            "prodConsumerKey": null,
          |            "prodConsumerSecret": null,
          |            "prodAuthorizedDomains": null,
          |            "prodValidityTime": 3600000,
          |            "sandboxKey": null,
          |            "sandboxConsumerKey": null,
          |            "sandboxConsumerSecret": null,
          |            "sandAuthorizedDomains": null,
          |            "sandValidityTime": 3600000,
          |            "hasMultipleEndpoints": "false"
          |          }
          |        ],
          |        "scopes": []
          |      },
          |      {
          |        "id": 2,
          |        "name": "AnotherApplication",
          |        "callbackUrl": null,
          |        "prodKey": null,
          |        "prodKeyScope": null,
          |        "prodKeyScopeValue": null,
          |        "prodConsumerKey": null,
          |        "prodConsumerSecret": null,
          |        "prodRegenarateOption": true,
          |        "prodAuthorizedDomains": null,
          |        "prodValidityTime": 3600,
          |        "prodJsonString": null,
          |        "sandboxKey": null,
          |        "sandKeyScope": null,
          |        "sandKeyScopeValue": null,
          |        "sandboxConsumerKey": null,
          |        "sandboxConsumerSecret": null,
          |        "sandRegenarateOption": true,
          |        "sandboxAuthorizedDomains": null,
          |        "sandboxJsonString": null,
          |        "sandValidityTime": 3600,
          |        "subscriptions": [
          |          {
          |            "name": "another-sample--v1.0",
          |            "provider": "admin",
          |            "version": "v1.0",
          |            "status": "PUBLISHED",
          |            "tier": "Unlimited",
          |            "subStatus": "UNBLOCKED",
          |            "thumburl": null,
          |            "context": "/another-sample/v1.0",
          |            "businessOwner": null,
          |            "prodKey": null,
          |            "prodConsumerKey": null,
          |            "prodConsumerSecret": null,
          |            "prodAuthorizedDomains": null,
          |            "prodValidityTime": 3600000,
          |            "sandboxKey": null,
          |            "sandboxConsumerKey": null,
          |            "sandboxConsumerSecret": null,
          |            "sandAuthorizedDomains": null,
          |            "sandValidityTime": 3600000,
          |            "hasMultipleEndpoints": "false"
          |          }
          |        ],
          |        "scopes": []
          |      }
          |    ],
          |    "totalLength": 1
          |  }
          |}
          |""".stripMargin)
      )
      when(mockHttpClient.POSTString[HttpResponse](any(), any(), any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(OK, responseBody)))

      val result = Await.result(underTest.getAllSubscriptions(cookie), 1.second)

      result shouldBe Map(
        "DefaultApplication" -> Seq(Wso2Api("pizzashack--1.0.0", "1.0.0"), Wso2Api("another-sample--v1.0", "v1.0")),
        "AnotherApplication" -> Seq(Wso2Api("another-sample--v1.0", "v1.0")))
      verify(mockHttpClient).POSTString[HttpResponse](meq(url), meq(body), meq(headers))(any(), meq(hc), any())
    }
  }

  "logout" should {

    "logout of WSO2 API Store for the given cookie" in new Setup {

      val url = s"$baseUrl/store/site/blocks/user/login/ajax/login.jag?action=logout"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM, COOKIE -> cookie)
      val responseBody = Some(Json.parse("""{"error":false}""""))
      when(mockHttpClient.GET[HttpResponse](any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(OK, responseBody)))

      val result = Await.result(underTest.logout(cookie), 1.second)

      result shouldBe HasSucceeded
      verify(mockHttpClient).GET[HttpResponse](meq(url))(any(), meq(hc.withExtraHeaders(headers: _*)), any())
    }

  }

  "addSubscription" should {

    "add an API subscription from WSO2 for the given application" in new Setup {
      val api = Wso2Api("my--api--1.0", "1.0")
      val url = s"$baseUrl/store/site/blocks/subscription/subscription-add/ajax/subscription-add.jag"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM, COOKIE -> cookie)
      val body =
        s"action=addAPISubscription" +
          s"&name=${api.name}" +
          s"&version=${api.version}" +
          s"&provider=$adminUsername" +
          s"&tier=BRONZE_SUBSCRIPTION" +
          s"&applicationName=$applicationName"
      val responseBody = Some(Json.parse("""{"error":false}"""))
      when(mockHttpClient.POSTString[HttpResponse](any(), any(), any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(OK, responseBody)))

      val result = Await.result(underTest.addSubscription(cookie, applicationName, api, None, 0), 1.second)

      result shouldBe HasSucceeded
      verify(mockHttpClient).POSTString[HttpResponse](meq(url), meq(body), meq(headers))(any(), meq(hc), any())
    }

  }

  "removeSubscription" should {

    "remove an API subscription from WSO2 for the given application" in new Setup {

      val api = Wso2Api("my--api--1.0", "1.0")
      val url = s"$baseUrl/store/site/blocks/subscription/subscription-remove/ajax/subscription-remove.jag"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM, COOKIE -> cookie)
      val body =
        s"action=removeSubscription" +
          s"&name=${api.name}" +
          s"&version=${api.version}" +
          s"&provider=$adminUsername" +
          s"&applicationName=$applicationName"
      val responseBody = Some(Json.parse("""{"error":false}"""))
      when(mockHttpClient.POSTString[HttpResponse](any(), any(), any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(OK, responseBody)))

      val result = Await.result(underTest.removeSubscription(cookie, applicationName, api, 0), 1.second)
      result shouldBe HasSucceeded
      verify(mockHttpClient).POSTString[HttpResponse](meq(url), meq(body), meq(headers))(any(), meq(hc), any())
    }

  }

  "generateApplicationKey" should {

    "generate an application key in WSO2 for a given application name and key type" in new Setup {

      val environment = Environment.PRODUCTION
      val url = s"$baseUrl/store/site/blocks/subscription/subscription-add/ajax/subscription-add.jag"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM, COOKIE -> cookie)
      val body =
        s"action=generateApplicationKey" +
          s"&application=$applicationName" +
          s"&keytype=$environment" +
          s"&callbackUrl=" +
          s"&authorizedDomains=ALL" +
          s"&validityTime=-1"
      val responseBody = Some(Json.parse(
        s"""{
           |  "error":false,
           |  "data":{
           |    "key":{
           |      "consumerSecret":"secret",
           |      "consumerKey":"key",
           |      "accessToken":"token"
           |    }
           |  }
           |}""".stripMargin))
      when(mockHttpClient.POSTString[HttpResponse](any(), any(), any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(OK, responseBody)))

      val result = Await.result(underTest.generateApplicationKey(cookie, applicationName, environment), 1.second)

      result shouldBe (_: EnvironmentToken)
      verify(mockHttpClient).POSTString[HttpResponse](meq(url), meq(body), meq(headers))(any(), meq(hc), any())
    }

  }

  "deleteApplication" should {

    "delete an application in WSO2 for the given application name" in new Setup {

      val url = s"$baseUrl/store/site/blocks/application/application-remove/ajax/application-remove.jag"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM, COOKIE -> cookie)
      val body = s"action=removeApplication&application=$applicationName"
      val responseBody = Some(Json.parse("""{"error":false}"""))
      when(mockHttpClient.POSTString[HttpResponse](any(), any(), any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(OK, responseBody)))

      val result = Await.result(underTest.deleteApplication(cookie, applicationName), 1.second)

      result shouldBe HasSucceeded
      verify(mockHttpClient).POSTString[HttpResponse](meq(url), meq(body), meq(headers))(any(), meq(hc), any())
    }

  }

  "createApplication" should {

    "create an application in WSO2 for the given application name" in new Setup {

      val url = s"$baseUrl/store/site/blocks/application/application-add/ajax/application-add.jag"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM, COOKIE -> cookie)
      val body =
        s"action=addApplication" +
          s"&application=$applicationName" +
          s"&tier=BRONZE_APPLICATION" +
          s"&description=" +
          s"&callbackUrl="
      val responseBody = Some(Json.parse("""{"error":false}"""))
      when(mockHttpClient.POSTString[HttpResponse](any(), any(), any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(OK, responseBody)))

      val result = Await.result(underTest.createApplication(cookie, applicationName), 1.second)

      result shouldBe HasSucceeded
      verify(mockHttpClient).POSTString[HttpResponse](meq(url), meq(body), meq(headers))(any(), meq(hc), any())
    }

  }

  "updateApplication" should {

    val cookie = "login-cookie-123"
    val wso2ApplicationName = "myapp"

    def mockForApplicationUpdate(mockHttpClient: HttpClient, userAgent: String, responseCode: Int, isError: Option[Boolean] = None): Unit = {

      val responseBody: String = isError match {
        case Some(b) => s"""{"error":${b.toString}}"""
        case _ => s"""{}"""
      }

      when(mockHttpClient.POSTString[HttpResponse](any(), any(), any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(responseCode, Some(Json.parse(responseBody)))))
    }

    def verifyApplicationUpdate(mockHttpClient: HttpClient) = {
      val url = s"$baseUrl/store/site/blocks/application/application-update/ajax/application-update.jag"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM, COOKIE -> cookie)
      val body =
        s"action=updateApplication" +
          s"&applicationOld=$wso2ApplicationName" +
          s"&applicationNew=$wso2ApplicationName" +
          s"&callbackUrlNew=" +
          s"&descriptionNew=" +
          s"&tier=SILVER_APPLICATION"
      verify(mockHttpClient).POSTString[HttpResponse](meq(url), meq(body), meq(headers))(any(), meq(hc), any())
    }

    "update rate limiting tier is wso2" in new Setup {
      mockForApplicationUpdate(mockHttpClient, serviceName, Status.OK, isError = Some(false))

      await(underTest updateApplication(cookie, wso2ApplicationName, RateLimitTier.SILVER)) shouldBe HasSucceeded

      verifyApplicationUpdate(mockHttpClient)
    }

    "thrown an exception if the response contains an error" in new Setup {
      mockForApplicationUpdate(mockHttpClient, serviceName, Status.OK, isError = Some(true))

      intercept[RuntimeException] {
        await(underTest updateApplication(cookie, wso2ApplicationName, RateLimitTier.SILVER))
      }

      verifyApplicationUpdate(mockHttpClient)
    }

    "thrown an exception if the response code is not 200 OK" in new Setup {
      mockForApplicationUpdate(mockHttpClient, serviceName, Status.INTERNAL_SERVER_ERROR)

      intercept[RuntimeException] {
        await(underTest updateApplication(cookie, wso2ApplicationName, RateLimitTier.SILVER))
      }

      verifyApplicationUpdate(mockHttpClient)
    }
  }

  "createUser" should {

    "create a user in WSO2 for the given username and password" in new Setup {

      val username = "myuser"
      val password = "mypassword"
      val url = s"$baseUrl/store/site/blocks/user/sign-up/ajax/user-add.jag"
      val headers: Seq[(String, String)] = Seq(CONTENT_TYPE -> FORM)
      val body =
        s"action=addUser" +
          s"&username=$username" +
          s"&password=$password" +
          s"&allFieldsValues=firstname|lastname|email"
      val responseBody = Some(Json.parse("""{"error":false}"""))
      when(mockHttpClient.POSTString[HttpResponse](any(), any(), any())(any(), any(), any()))
        .thenReturn(Future(HttpResponse(OK, responseBody)))

      val result = Await.result(underTest.createUser(username, password), 1.second)

      result shouldBe HasSucceeded
      verify(mockHttpClient).POSTString[HttpResponse](meq(url), meq(body), meq(headers))(any(), meq(hc), any())
    }

  }

}