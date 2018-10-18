/*
 * Copyright 2018 HM Revenue & Customs
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

import com.google.inject.AbstractModule
import com.typesafe.config.{Config, ConfigFactory}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.config.MicroserviceAuditConnector
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.services.{RealWSO2APIStore, StubAPIStore, WSO2APIStore}

class Module(environment: Environment, configuration: Configuration) extends AbstractModule {

  override def configure = {

    bind(classOf[Config]).toInstance(ConfigFactory.load())
    bind(classOf[AuditConnector]).toInstance(MicroserviceAuditConnector)
    val skipWso2 = configuration.getBoolean("skipWso2").getOrElse(false)
    if (skipWso2) bind(classOf[WSO2APIStore]).toInstance(StubAPIStore)
    else bind(classOf[WSO2APIStore]).to(classOf[RealWSO2APIStore])

    // Temporary binding for Sage application fix to Terms of Use Agreement
    bind(classOf[RemoveSageTermsAgreement]).asEagerSingleton
  }
}
