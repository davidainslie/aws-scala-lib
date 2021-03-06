package uk.gov.homeoffice.aws.sqs

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.Try
import akka.actor.Props
import akka.testkit.TestActorRef
import play.api.http.Status.OK
import org.json4s.JsonAST.JObject
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson._
import org.scalactic.{Good, Or}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import uk.gov.homeoffice.akka.ActorSystemSpecification
import uk.gov.homeoffice.concurrent.PromiseOps
import uk.gov.homeoffice.json._

class JsonSQSActorSpec(implicit ev: ExecutionEnv) extends Specification with ActorSystemSpecification with JsonFormats with PromiseOps {
  trait Context extends ActorSystemContext with SQSServerEmbedded {
    val queue = create(new Queue("test-queue"))

    trait MyJsonSubscription extends JsonSubscription {
      this: SQSActor =>

      val jsonSchema = JsonSchema(
        ("id" -> "http://www.bad.com/schema") ~
          ("$schema" -> "http://json-schema.org/draft-04/schema") ~
          ("type" -> "object") ~
          ("properties" ->
            ("input" ->
              ("type" -> "string")))
      )
    }
  }

  "JSON subscription" should {
    "receive valid JSON and process it" in new Context {
      val result = Promise[String]()

      val actor = TestActorRef {
        new SQSActor(new SQS(queue)) with MyJsonSubscription {
          def receive: Receive = {
            case m: Message => result <~ Future {
              parse(m, jsonSchema)
              "Well done!"
            }
          }
        }
      }

      actor ! createMessage(compact(render(JObject("input" -> JString("blah")))))

      result.future must beEqualTo("Well done!").await
    }

    "receive JSON and fail to validate" in new Context {
      val actor = TestActorRef {
        new SQSActor(new SQS(queue)) with MyJsonSubscription {
          def receive: Receive = {
            case m: Message => parse(m, jsonSchema)
          }
        }
      }

      actor ! createMessage(compact(render(JObject("input" -> JInt(0)))))

      val errorSubscriber = new SQS(queue)

      def publishedErrorMessage = Try {
        (parseJson(errorSubscriber.receiveErrors.head.content) \ "error-message" \ "error").extract[String] contains "error: instance type (integer) does not match any allowed primitive type"
      } getOrElse false

      true must eventually(beEqualTo(publishedErrorMessage))
    }
  }

  "JSON subscription with only messages" should {
    "receive valid JSON and process it" in new Context {
      val result = Promise[String Or JsonError]()

      system actorOf Props {
        new SQSActor(new SQS(queue)) with MyJsonSubscription {
          def receive: Receive = {
            case m: Message => result <~ Future {
              parse(m, jsonSchema) map { _ => "Well done!" }
            }
          }
        }
      }

      val sqs = new SQS(queue)
      sqs publish compact(render(JObject("input" -> JString("blah"))))

      result.future must beEqualTo(Good("Well done!")).await
    }

    "receive JSON and fail to validate" in new Context {
      system actorOf Props {
        new SQSActor(new SQS(queue)) with MyJsonSubscription {
          def receive: Receive = {
            case m: Message => parse(m, jsonSchema)
          }
        }
      }

      val sqs = new SQS(queue)

      val input = JObject("input" -> JInt(0))
      sqs publish compact(render(input))

      val errorSubscriber = new SQS(queue)

      def publishedErrorMessage: Boolean =  Try {
        val `error-message` = parseJson(errorSubscriber.receiveErrors.head.content) \ "error-message"

        (`error-message` \ "json" == input) &&
        (`error-message` \ "error").extract[String].contains("error: instance type (integer) does not match any allowed primitive type")
      } getOrElse false

      true must eventually(beEqualTo(publishedErrorMessage))
    }

    "receive valid JSON from a RESTful POST and process it" in new Context with REST {
      val response = wsClient.url(s"$sqsHost/queue/${queue.queueName}")
                             .withHeaders("Content-Type" -> "application/x-www-form-urlencoded")
                             .post(params("Action" -> "SendMessage", "MessageBody" -> compact(render(JObject("input" -> JString("blah")))))) map { response =>
        response.status
      }

      response must beEqualTo(OK).await

      val result = Promise[String Or JsonError]()

      system actorOf Props {
        new SQSActor(new SQS(queue)) with MyJsonSubscription {
          def receive: Receive = {
            case m: Message => result <~ Future {
              parse(m, jsonSchema) map { _ => "Well done!" }
            }
          }
        }
      }

      result.future must beEqualTo(Good("Well done!")).awaitFor(3 seconds)
    }

    "receive invalid JSON from a RESTful POST" in new Context with REST {
      val input = JObject("input" -> JInt(0))

      val response = wsClient.url(s"$sqsHost/queue/${queue.queueName}")
                             .withHeaders("Content-Type" -> "application/x-www-form-urlencoded")
                             .post(params("Action" -> "SendMessage", "MessageBody" -> compact(render(input)))) map { response =>
        response.status
      }

      response must beEqualTo(OK).await

      system actorOf Props {
        new SQSActor(new SQS(queue)) with MyJsonSubscription {
          def receive: Receive = {
            case m: Message => parse(m, jsonSchema)
          }
        }
      }

      val errorSubscriber = new SQS(queue)

      def publishedErrorMessage: Boolean =  Try {
        val `error-message` = parseJson(errorSubscriber.receiveErrors.head.content) \ "error-message"

        (`error-message` \ "json" == input) &&
        (`error-message` \ "error").extract[String].contains("error: instance type (integer) does not match any allowed primitive type")
      } getOrElse false

      true must eventually(beEqualTo(publishedErrorMessage))
    }
  }
}