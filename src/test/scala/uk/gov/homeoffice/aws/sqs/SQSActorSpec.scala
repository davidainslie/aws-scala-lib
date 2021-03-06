package uk.gov.homeoffice.aws.sqs

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.util.Try
import akka.actor.Props
import akka.testkit.TestActorRef
import org.json4s.JValue
import org.json4s.jackson._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.control.NoLanguageFeatures
import org.specs2.mutable.Specification
import uk.gov.homeoffice.akka.{ActorExpectations, ActorSystemSpecification}
import uk.gov.homeoffice.aws.sqs.protocol._
import uk.gov.homeoffice.concurrent.PromiseOps
import uk.gov.homeoffice.json.JsonFormats

class SQSActorSpec(implicit ev: ExecutionEnv) extends Specification with ActorSystemSpecification with JsonFormats with PromiseOps with NoLanguageFeatures {
  trait Context extends ActorSystemContext with ActorExpectations with SQSServerEmbedded {
    implicit val listeners = Seq(testActor)

    val queue = create(new Queue("test-queue"))
  }

  "SQS actor" should {
    "receive a string and process it" in new Context {
      val result = Promise[String]()

      val actor = TestActorRef {
        new SQSActor(new SQS(queue)) {
          def receive: Receive = {
            case m: Message => result <~ Future { m.content }
          }
        }
      }

      actor ! createMessage("blah")

      result.future must beEqualTo("blah").await
    }

    "reject a string" in new Context {
      val actor = TestActorRef {
        new SQSActor(new SQS(queue)) {
          def receive: Receive = {
            case m: Message => publishError(new Exception("Processing failed"), m)
          }
        }
      }

      actor ! createMessage("blah")

      val errorSubscriber = new SQS(queue)

      def publishedErrorMessage: JValue = parseJson(errorSubscriber.receiveErrors.head.content)

      "Processing failed" must eventually(beEqualTo((publishedErrorMessage \ "error-message" \ "errorStackTrace" \ "errorMessage").extract[String]))
    }

    "reject a message as it didn't pass through the filter" in new Context {
      def rejectFilter(m: Message): Option[Message] = None

      val actor = TestActorRef {
        new SQSActor(new SQS(queue), rejectFilter) {
          def receive: Receive = {
            case m: Message => sender() ! Processed(m)
          }
        }
      }

      actor ! createMessage("blah")

      expectMsgType[Message]
      expectNoMsg(3 seconds)
    }

    "let the message pass through the filter" in new Context {
      def acceptFilter(m: Message): Option[Message] = Some(m)

      val actor = TestActorRef {
        new SQSActor(new SQS(queue), acceptFilter) {
          def receive: Receive = {
            case m: Message =>
              sender() ! Processed(m)
          }
        }
      }

      val message = createMessage("blah")
      actor ! message

      eventuallyExpectMsg[Processed] {
        case Processed(m) => m == message
      }
    }

    "let the message pass through two filters" in new Context {
      def acceptFilter(m: Message): Option[Message] = Some(m)

      val actor = TestActorRef {
        new SQSActor(new SQS(queue), acceptFilter, acceptFilter) {
          def receive: Receive = {
            case m: Message =>
              sender() ! Processed(m)
          }
        }
      }

      val message = createMessage("blah")
      actor ! message

      eventuallyExpectMsg[Processed] {
        case Processed(m) => m == message
      }
    }
  }

  "SQS actor with only messages" should {
    "receive a string, process it and delete it" in new Context {
      val result = Promise[String]()

      system actorOf Props {
        new SQSActor(new SQS(queue)) {
          def receive: Receive = {
            case m: Message => result <~ Future { m.content }
          }
        }
      }

      val sqs = new SQS(queue)
      sqs publish "blah"

      result.future must beEqualTo("blah").awaitFor(5 seconds)
    }

    "reject a string, publish error and delete said string" in new Context {
      system actorOf Props {
        new SQSActor(new SQS(queue)) {
          def receive: Receive = {
            case m: Message => publishError(new Exception("Processing failed"), m)
          }
        }
      }

      val sqs = new SQS(queue)
      sqs publish "blah"

      val errorSubscriber = new SQS(queue)

      def publishedErrorMessage: Boolean = Try {
        errorSubscriber.receiveErrors.size == 1
      } getOrElse false

      true must eventually(beEqualTo(publishedErrorMessage))
    }
  }

  "SQS actor with only messages and a message protocol" should {
    "receive a string, fire a message indicating that the message has been processed" in new Context {
      system actorOf Props {
        new SQSActor(new SQS(queue)) {
          def receive: Receive = {
            case m: Message => sender() ! Processed(m)
          }
        }
      }

      val message = "blah"

      val sqs = new SQS(queue)
      sqs publish message

      eventuallyExpectMsg[Processed] {
        case Processed(m) => m.content == message
      }
    }

    "throw an exception for first message, then receive a string, fire a message indicating that the message has been processed" in new Context {
      system actorOf Props {
        new SQSActor(new SQS(queue)) {
          def receive: Receive = {
            case m: Message if m.content == "Crash" => throw new Exception("Crash")
            case m: Message => sender() ! Processed(m)
          }
        }
      }

      val sqs = new SQS(queue)

      sqs publish "Crash"

      val message = "blah"
      sqs publish message

      eventuallyExpectMsg[Processed] {
        case Processed(m) => m.content == message
      }
    }
  }
}