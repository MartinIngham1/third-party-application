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

package unit.uk.gov.hmrc.thirdpartyapplication.models

import common.uk.gov.hmrc.thirdpartyapplication.common.LogSuppressing
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.libs.json.Json
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.{ApiDefinition, ApiStatus, ApiVersion}

class ApiDefinitionSpec extends UnitSpec with WithFakeApplication with MockitoSugar with ScalaFutures with LogSuppressing {

  private val apiDefinitionWithStableStatus = ApiDefinition("api-service", "api-name", "api-context",
    Seq(ApiVersion("1.0", ApiStatus.STABLE, None)), Some(false))

  private val apiDefinitionWithBetaStatus = ApiDefinition("api-service", "api-name", "api-context",
    Seq(ApiVersion("1.0", ApiStatus.BETA, None)), Some(false))

  private val apiDefinitionWithIsTestSupportFlag = ApiDefinition("api-service", "api-name", "api-context",
    Seq(ApiVersion("1.0", ApiStatus.STABLE, None)), Some(false), Some(true))

  private val apiDefinitionWithStableStatusJson =
    """{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "STABLE"
      |    }
      |  ],
      |  "requiresTrust": false
      |}""".stripMargin

  private val apiDefinitionWithPublishedStatusJson =
    """{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "PUBLISHED"
      |    }
      |  ],
      |  "requiresTrust": false
      |}""".stripMargin

  private val apiDefinitionWithPrototypedStatusJson =
    """{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "PROTOTYPED"
      |    }
      |  ],
      |  "requiresTrust": false
      |}""".stripMargin

  private val apiDefinitionWithIsTestSupportFlagJson =
    """{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "PUBLISHED"
      |    }
      |  ],
      |  "requiresTrust": false,
      |  "isTestSupport": true
      |}""".stripMargin

  "APIDefinition" should {

    "map a status of STABLE in the JSON to STABLE in the model" in {

      val result = Json.fromJson[ApiDefinition](Json.parse(apiDefinitionWithStableStatusJson))

      result.asOpt shouldBe Some(apiDefinitionWithStableStatus)
    }

    "map a status of PROTOTYPED in the JSON to BETA in the model" in {

      val result = Json.fromJson[ApiDefinition](Json.parse(apiDefinitionWithPrototypedStatusJson))

      result.asOpt shouldBe Some(apiDefinitionWithBetaStatus)
    }

    "map a status of PUBLISHED in the JSON to STABLE in the model" in {

      val result = Json.fromJson[ApiDefinition](Json.parse(apiDefinitionWithPublishedStatusJson))

      result.asOpt shouldBe Some(apiDefinitionWithStableStatus)
    }

    "map a isTestSupport flag when true in the JSON to true in the model" in {

      val result = Json.fromJson[ApiDefinition](Json.parse(apiDefinitionWithIsTestSupportFlagJson))

      result.asOpt shouldBe Some(apiDefinitionWithIsTestSupportFlag)
    }

  }
}