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

package unit.uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.{HOURS, SECONDS}

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.{DateTime, DateTimeUtils, Duration}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.models.State.PENDING_REQUESTER_VERIFICATION
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.scheduled.{UpliftVerificationExpiryJob, UpliftVerificationExpiryJobConfig, UpliftVerificationExpiryJobLockKeeper}
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class UpliftVerificationExpiryJobSpec extends UnitSpec with MockitoSugar with MongoSpecSupport with BeforeAndAfterAll with ApplicationStateUtil {

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  val FixedTimeNow: DateTime = HmrcTime.now
  val expiryTimeInDays = 90

  trait Setup {
    val mockApplicationRepository = mock[ApplicationRepository]
    val mockStateHistoryRepository = mock[StateHistoryRepository]

    val lockKeeperSuccess: () => Boolean = () => true

    val mockLockKeeper = new UpliftVerificationExpiryJobLockKeeper(reactiveMongoComponent) {
      override def lockId: String = ???

      override def repo: LockRepository = ???

      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number

      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Future.successful(Some(value)))
        else Future.successful(None)
    }

    val upliftVerificationValidity = FiniteDuration(expiryTimeInDays, TimeUnit.DAYS)
    val initialDelay = FiniteDuration(60, SECONDS) // scalastyle:off magic.number
    val interval = FiniteDuration(24, HOURS) // scalastyle:off magic.number
    val config = UpliftVerificationExpiryJobConfig(initialDelay, interval, enabled = true, upliftVerificationValidity)

    val underTest = new UpliftVerificationExpiryJob(mockLockKeeper, mockApplicationRepository, mockStateHistoryRepository, config)

    when(mockApplicationRepository.save(any[ApplicationData])).thenAnswer(returnSame[ApplicationData])

    def returnSame[T] = new Answer[Future[T]] {
      override def answer(invocationOnMock: InvocationOnMock): Future[T] = {
        val argument = invocationOnMock.getArguments()(0)
        successful(argument.asInstanceOf[T])
      }
    }
  }

  override def beforeAll(): Unit = {
    DateTimeUtils.setCurrentMillisFixed(FixedTimeNow.toDate.getTime)
  }

  override def afterAll(): Unit = {
    DateTimeUtils.setCurrentMillisSystem()
  }

  "uplift verification expiry job execution" should {
    "expire all application uplifts having expiry date before the expiry time" in new Setup {
      val app1 = anApplicationData(UUID.randomUUID(), "aaa", "111")
      val app2 = anApplicationData(UUID.randomUUID(), "aaa", "111")

      when(mockApplicationRepository.fetchAllByStatusDetails(refEq(PENDING_REQUESTER_VERIFICATION), any[DateTime]))
        .thenReturn(Future.successful(Seq(app1, app2)))

      await(underTest.execute)
      verify(mockApplicationRepository).fetchAllByStatusDetails(PENDING_REQUESTER_VERIFICATION, FixedTimeNow.minusDays(expiryTimeInDays))
      verify(mockApplicationRepository).save(app1.copy(state = testingState()))
      verify(mockApplicationRepository).save(app2.copy(state = testingState()))
      verify(mockStateHistoryRepository).insert(StateHistory(app1.id, State.TESTING,
        Actor("UpliftVerificationExpiryJob", ActorType.SCHEDULED_JOB), Some(PENDING_REQUESTER_VERIFICATION)))
      verify(mockStateHistoryRepository).insert(StateHistory(app2.id, State.TESTING,
        Actor("UpliftVerificationExpiryJob", ActorType.SCHEDULED_JOB), Some(PENDING_REQUESTER_VERIFICATION)))
    }

    "not execute if the job is already running" in new Setup {
      override val lockKeeperSuccess: () => Boolean = () => false

      await(underTest.execute)
    }

    "handle error on first database call to fetch all applications" in new Setup {
      when(mockApplicationRepository.fetchAllByStatusDetails(refEq(PENDING_REQUESTER_VERIFICATION), any[DateTime])).thenReturn(
        Future.failed(new RuntimeException("A failure on executing fetchAllByStatusDetails db query"))
      )
      val result = await(underTest.execute)

      result.message shouldBe
        "The execution of scheduled job UpliftVerificationExpiryJob failed with error 'A failure on executing fetchAllByStatusDetails db query'." +
          " The next execution of the job will do retry."
    }

    "handle error on subsequent database call to update an application" in new Setup {
      val app1 = anApplicationData(UUID.randomUUID(), "aaa", "111")
      val app2 = anApplicationData(UUID.randomUUID(), "aaa", "111")

      when(mockApplicationRepository.fetchAllByStatusDetails(refEq(PENDING_REQUESTER_VERIFICATION), any[DateTime]))
        .thenReturn(Future.successful(Seq(app1, app2)))
      when(mockApplicationRepository.save(any[ApplicationData])).thenReturn(
        Future.failed(new RuntimeException("A failure on executing save db query"))
      )

      val result = await(underTest.execute)

      verify(mockApplicationRepository).fetchAllByStatusDetails(PENDING_REQUESTER_VERIFICATION, FixedTimeNow.minusDays(expiryTimeInDays))
      result.message shouldBe
        "The execution of scheduled job UpliftVerificationExpiryJob failed with error 'A failure on executing save db query'." +
          " The next execution of the job will do retry."
    }

  }

  def anApplicationData(id: UUID, prodClientId: String, sandboxClientId: String, state: ApplicationState = testingState()): ApplicationData = {
    ApplicationData(
      id,
      s"myApp-$id",
      s"myapp-$id",
      Set(Collaborator("user@example.com", Role.ADMINISTRATOR)),
      Some("description"),
      "username",
      "password",
      "myapplication",
      ApplicationTokens(
        EnvironmentToken(prodClientId, "bbb", "ccc"),
        EnvironmentToken(sandboxClientId, "222", "333")),
      state, Standard(Seq.empty, None, None))
  }
}
