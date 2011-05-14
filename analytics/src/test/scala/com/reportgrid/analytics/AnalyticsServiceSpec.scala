package com.reportgrid.analytics

import blueeyes.core.data.Bijection.identity
import blueeyes.core.http.{HttpStatus, HttpResponse, MimeTypes}
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.core.service.test.BlueEyesServiceSpecification
import MimeTypes._

import blueeyes.json.JsonAST.{JValue, JObject, JField, JString, JNothing, JArray}
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.persistence.mongo.{Mongo, MockMongo}

import net.lag.configgy.{Configgy, ConfigMap}

import org.specs._
import org.specs.specification.PendingUntilFixed
import org.specs.util.TimeConversions._
import org.scalacheck._
import Gen._

class AnalyticsServiceSpec extends BlueEyesServiceSpecification with PendingUntilFixed with ScalaCheck 
with AnalyticsService with ArbitraryEvent {

  def mongoFactory(config: ConfigMap): Mongo = new MockMongo()

  override def configuration = """
    services {
      analytics {
        v0 {
          variable_series {
            collection = "variable_series"

            time_to_idle_millis = 500
            time_to_live_millis = 100

            initial_capacity = 1000
            maximum_capacity = 10000
          }

          variable_value_series {
            collection = "variable_value_series"

            time_to_idle_millis = 500
            time_to_live_millis = 100

            initial_capacity = 1000
            maximum_capacity = 10000
          }

          variable_values {
            collection = "variable_values"

            time_to_idle_millis = 500
            time_to_live_millis = 100

            initial_capacity = 1000
            maximum_capacity = 10000
          }

          variable_children {
            collection = "variable_children"

            time_to_idle_millis = 500
            time_to_live_millis = 100

            initial_capacity = 1000
            maximum_capacity = 10000
          }

          path_children {
            collection = "path_children"

            time_to_idle_millis = 500
            time_to_live_millis = 100

            initial_capacity = 1000
            maximum_capacity = 10000
          }

          mongo {
            database = "analytics"

            servers = ["mongodb01.reportgrid.com:27017", "mongodb02.reportgrid.com:27017", "mongodb03.reportgrid.com:27017"]
          }

          log {
            level   = "debug"
            console = true
          }
        }
      }
    }

    server {
      log {
        level   = "debug"
        console = true
      }
    }
    """

  lazy val analytics = service.contentType[JValue](application/json).query("tokenId", Token.Test.tokenId)

  "Demo Service" should {
    "create events" in pendingUntilFixed {
      val sampleEvents: List[Event] = containerOfN[List, Event](2, eventGen).sample.get

      lazy val tweetedCount = sampleEvents.count {
        case Event(JObject(JField("tweeted", _) :: Nil), _) => true
        case _ => false
      }

      for (event <- sampleEvents) {
        analytics.post[JValue]("/vfs/gluecon")(event.message)
      }

      (analytics.get[JValue]("/vfs/gluecon/.tweeted/count").value) must eventually(10, 1.second) {
        beLike {
          case Some(HttpResponse(_, _, Some(result), _)) =>
            result.deserialize[Long] must_== tweetedCount
        }
      } 
    }
  }
}