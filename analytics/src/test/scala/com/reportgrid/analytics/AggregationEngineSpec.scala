package com.reportgrid.analytics

import blueeyes._
import blueeyes.core.data.Bijection.identity
import blueeyes.core.http.{HttpStatus, HttpResponse, MimeTypes}
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.concurrent.test.FutureMatchers
import blueeyes.util._
import blueeyes.util.metrics.Duration
import blueeyes.util.metrics.Duration.toDuration
import MimeTypes._

import blueeyes.json._
import blueeyes.json.JsonAST._
import blueeyes.json.JsonDSL._
import blueeyes.json.JPathImplicits._
import blueeyes.json.xschema.JodaSerializationImplicits._
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.persistence.mongo._
import blueeyes.concurrent.Future

import net.lag.configgy.{Configgy, Config, ConfigMap}
import net.lag.logging.Logger

import org.joda.time.Instant
import org.specs.{Specification, ScalaCheck}
import org.specs.specification.PendingUntilFixed
import org.scalacheck._
import scala.math.Ordered._
import Gen._
import scalaz._
import Scalaz._
import Periodicity._

trait LocalMongo {
  val dbName = "test" + scala.util.Random.nextInt(10000)

  def mongoConfigFileData = """
    mongo {
      database = "%s"
      servers  = ["localhost:27017"]
    }

    variable_series {
      collection = "variable_series"
      time_to_idle_millis = 250
      time_to_live_millis = 500

      initial_capacity = 1000
      maximum_capacity = 10000
    }

    variable_value_series {
      collection = "variable_value_series"

      time_to_idle_millis = 250
      time_to_live_millis = 500

      initial_capacity = 1000
      maximum_capacity = 100000
    }

    variable_values {
      collection = "variable_values"

      time_to_idle_millis = 250
      time_to_live_millis = 500

      initial_capacity = 1000
      maximum_capacity = 10000
    }

    variable_children {
      collection = "variable_children"

      time_to_idle_millis = 250
      time_to_live_millis = 500

      initial_capacity = 1000
      maximum_capacity = 10000
    }

    path_children {
      collection = "path_children"

      time_to_idle_millis = 250
      time_to_live_millis = 500

      initial_capacity = 1000
      maximum_capacity = 10000
    }

    log {
      level   = "debug"
      console = true
    }
  """.format(dbName)
}

object Console {
  import FutureUtils._
  def apply(file: java.io.File): Console = {
    apply((new Config()) ->- (_.loadFile(file.getPath)))
  }

  def apply(config: ConfigMap): Console = {
    val mongoConfig = config.configMap("services.analytics.v0.mongo")
    val mongo = new RealMongo(mongoConfig)
    val database = mongo.database(mongoConfig("database"))
    Console(
      AggregationEngine.forConsole(config, Logger.get, database),
      get(TokenManager(database, "tokens"))
    )
  }
}

case class Console(engine: AggregationEngine, tokenManager: TokenManager)

object FutureUtils {
  def get[A](f: Future[A]): A = {
    val latch = new java.util.concurrent.CountDownLatch(1)

    f.deliverTo(_ => latch.countDown())

    latch.await()

    f.value.get
  }
}

class AggregationEngineSpec extends Specification with ArbitraryEvent with FutureMatchers with LocalMongo with PendingUntilFixed {
  import AggregationEngine._
  import FutureUtils._

  val config = (new Config()) ->- (_.load(mongoConfigFileData))

  val mongo = new RealMongo(config.configMap("mongo")) 
  //val mongo = new MockMongo()

  val database = mongo.database(dbName)

//  implicit val hashFunction: HashFunction = new HashFunction {
//    override def apply(bytes : Array[Byte]) = bytes
//  }
  
  val engine = get(AggregationEngine(config, Logger.get, database))

  override implicit val defaultFutureTimeouts = FutureTimeouts(40, toDuration(500).milliseconds)

  "Aggregating full events" should {
    shareVariables()

    // using the benchmark token for testing because it has order 3
    val sampleEvents: List[Event] = containerOfN[List, Event](10, fullEventGen).sample.get ->- {
      _.foreach(event => engine.aggregate(Token.Benchmark, "/test", event.eventName, event.tags, event.data, 1))
    }

    "retrieve path children" in {
      //skip("disabled")
      val children = sampleEvents.map {
        case Event(eventName, _, _) => "." + eventName
      }.toSet

      engine.getPathChildren(Token.Benchmark, "/test") must whenDelivered {
        haveTheSameElementsAs(children)
      }
    }
 
    "count events" in {
      //skip("disabled")
      def countEvents(eventName: String) = sampleEvents.count {
        case Event(`eventName`, _, tags) => true
        case _ => false
      }

      val eventCounts = EventTypes.map(eventName => (eventName, countEvents(eventName))).toMap

      val queryTerms = List[TagTerm](
        SpanTerm(AggregationEngine.timeSeriesEncoding, TimeSpan.Eternity),
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      eventCounts.foreach {
        case (eventName, count) =>
          engine.getVariableCount(Token.Benchmark, "/test", Variable("." + eventName), queryTerms) must whenDelivered {
            beEqualTo(count)
          }
      }
    }
 
    "retrieve values" in {
      //skip("disabled")
      val values = sampleEvents.foldLeft(Map.empty[(String, JPath), Set[JValue]]) { 
        case (map, Event(eventName, obj, _)) =>
          obj.flattenWithPath.foldLeft(map) {
            case (map, (path, value)) => 
              val key = (eventName, path)
              val oldValues = map.getOrElse(key, Set.empty)

              map + (key -> (oldValues + value))
          }

        case (map, _) => map
      }

      values.foreach {
        case ((eventName, path), values) =>
          val jpath = JPath(eventName) \ path
          if (!jpath.endsInInfiniteValueSpace) {
            engine.getValues(Token.Benchmark, "/test", Variable(jpath)) must whenDelivered {
              haveSameElementsAs(values)
            }
          }
      }
    }

    "retrieve all values of arrays" in {
      //skip("disabled")
      val arrayValues = sampleEvents.foldLeft(Map.empty[(String, JPath), Set[JValue]]) { 
        case (map, Event(eventName, obj, _)) =>
          map <+> ((obj.children.collect { case JField(name, JArray(elements)) => ((eventName, JPath(name)), elements.toSet) }).toMap)

        case (map, _) => map
      }

      arrayValues.foreach {
        case ((eventName, path), values) =>
          engine.getValues(Token.Benchmark, "/test", Variable(JPath(eventName) \ path)) must whenDelivered {
            haveSameElementsAs(values)
          }
      }
    }

    "retrieve the top results of a histogram" in {
      //skip("disabled")
      val retweetCounts = sampleEvents.foldLeft(Map.empty[JValue, Int]) {
        case (map, Event("tweeted", data, _)) => 
          val key = data.value(".retweet")
          map + (key -> map.get(key).map(_ + 1).getOrElse(1))

        case (map, _) => map
      }

      engine.getHistogramTop(Token.Benchmark, "/test", Variable(".tweeted.retweet"), 10) must whenDelivered {
        haveTheSameElementsAs(retweetCounts)
      }
    }

    "retrieve totals" in {
      //skip("disabled")
      val expectedTotals = valueCounts(sampleEvents)

      val queryTerms = List[TagTerm](
        SpanTerm(AggregationEngine.timeSeriesEncoding, TimeSpan.Eternity),
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      expectedTotals.foreach {
        case ((jpath, value), count) =>
          val variable = Variable(jpath) 
          if (!variable.name.endsInInfiniteValueSpace) {
            engine.getObservationCount(Token.Benchmark, "/test", JointObservation(HasValue(variable, value)), queryTerms) must whenDelivered {
              beEqualTo(count.toLong)
            }
          }
      }
    }

    "retrieve a time series for occurrences of a variable" in {
      //skip("disabled")
      val granularity = Minute
      val (events, minDate, maxDate) = timeSlice(sampleEvents, granularity)

      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      val expectedTotals = events.foldLeft(Map.empty[JPath, Int]) {
        case (map, Event(eventName, obj, _)) =>
          obj.flattenWithPath.foldLeft(map) {
            case (map, (path, _)) =>
              val key = JPath(eventName) \ path
              map + (key -> (map.getOrElse(key, 0) + 1))
          }
      }

      expectedTotals.foreach {
        case (jpath, count) =>
          engine.getVariableSeries(Token.Benchmark, "/test", Variable(jpath), queryTerms).
          map(_.total.count) must whenDelivered {
            beEqualTo(count.toLong)
          }
      }
    }

    "retrieve a time series of means of values of a variable" in {
      //skip("disabled")
      val granularity = Minute
      val (events, minDate, maxDate) = timeSlice(sampleEvents, granularity)

      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      expectedMeans(events, "recipientCount", keysf(granularity)).foreach {
        case (eventName, means) =>
          val expected: Map[String, Double] = means.map{ case (k, v) => (k(0), v) }.toMap

          engine.getVariableSeries(Token.Benchmark, "/test", Variable(JPath(eventName) \ "recipientCount"), queryTerms) must whenDelivered {
            verify { result => 
              val remapped: Map[String, Double] = result.flatMap{ case (k, v) => v.mean.map((k \ "timestamp").deserialize[Instant].toString -> _) }.toMap 
              remapped must_== expected
            }
          }
      }
    }

    "retrieve a time series for occurrences of a value of a variable" in {      
      ////skip("disabled")
      val granularity = Minute
      val (events, minDate, maxDate) = timeSlice(sampleEvents, granularity)
      val expectedTotals = valueCounts(events)
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      expectedTotals.foreach {
        case ((jpath, value), count) =>
          val variable = Variable(jpath)
          if (!variable.name.endsInInfiniteValueSpace) {
            val observation = JointObservation(HasValue(variable, value))

            engine.getObservationSeries(Token.Benchmark, "/test", observation, queryTerms) must whenDelivered {
              verify { results => 
                (results.total must_== count.toLong) && 
                (results must haveSize((granularity.period(minDate) until maxDate).size))
              }
            }
          }
      }
    }

    "count observations of a given value" in {
      //skip("disabled")
      val variables = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Nil

      val queryTerms = List[TagTerm](
        SpanTerm(AggregationEngine.timeSeriesEncoding, TimeSpan.Eternity),
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      val expectedCounts = sampleEvents.foldLeft(Map.empty[List[JValue], Int]) {
        case (map, Event("tweeted", data, _)) =>
          val values = variables.map(v => data.value(JPath(v.name.nodes.drop(1))))
          map + (values -> map.get(values).map(_ + 1).getOrElse(1))

        case (map, _) => map
      }

      val atemporalQueryTerms = List[TagTerm](
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      expectedCounts.map {
        case (values, count) =>
          val observation = JointObservation((variables zip values).map((HasValue(_, _)).tupled).toSet)

          engine.getObservationCount(Token.Benchmark, "/test", observation, queryTerms) must whenDelivered (beEqualTo(count))
          engine.getObservationCount(Token.Benchmark, "/test", observation, atemporalQueryTerms) must whenDelivered (beEqualTo(count))
      }
    }

    "count observations of a given value in a restricted time slice" in {
      //skip("disabled")
      val granularity = Minute
      val (events, minDate, maxDate) = timeSlice(sampleEvents, granularity)
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      val variables = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Nil

      val expectedCounts = events.foldLeft(Map.empty[List[JValue], Int]) {
        case (map, Event("tweeted", data, _)) =>
          val values = variables.map(v => data.value(JPath(v.name.nodes.drop(1))))
          map + (values -> map.get(values).map(_ + 1).getOrElse(1))

        case (map, _) => map
      }

      expectedCounts.map {
        case (values, count) =>
          val observation = JointObservation((variables zip values).map((HasValue(_, _)).tupled).toSet)

          engine.getObservationCount(Token.Benchmark, "/test", observation, queryTerms) must whenDelivered (beEqualTo(count))
      }
    }

    "retrieve intersection counts" in {      
      //skip("disabled")
      val variables   = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Nil
      val descriptors = variables.map(v => VariableDescriptor(v, 10, SortOrder.Descending))

      val queryTerms = List[TagTerm](
        SpanTerm(AggregationEngine.timeSeriesEncoding, TimeSpan.Eternity),
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      val expectedCounts = sampleEvents.foldLeft(Map.empty[List[JValue], Int]) {
        case (map, Event("tweeted", data, _)) =>
          val values = variables.map(v => data.value(JPath(v.name.nodes.drop(1))))

          map + (values -> map.get(values).map(_ + 1).getOrElse(1))

        case (map, _) => map
      }

      engine.getIntersectionCount(Token.Benchmark, "/test", descriptors, queryTerms) must whenDelivered {
        verify { result => 
          result.collect{ case (JArray(keys), v) if v != 0 => (keys, v) }.toMap must_== expectedCounts
        }
      }
    }

    "retrieve intersection counts for a slice of time" in {      
      //skip("disabled")
      val granularity = Minute
      val (events, minDate, maxDate) = timeSlice(sampleEvents, granularity)
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      val variables   = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Nil
      val descriptors = variables.map(v => VariableDescriptor(v, 10, SortOrder.Descending))

      val expectedCounts = events.foldLeft(Map.empty[List[JValue], Int]) {
        case (map, Event("tweeted", data, _)) =>
          val values = variables.map(v => data.value(JPath(v.name.nodes.drop(1))))

          map + (values -> map.get(values).map(_ + 1).getOrElse(1))

        case (map, _) => map
      }

      engine.getIntersectionCount(Token.Benchmark, "/test", descriptors, queryTerms) must whenDelivered {
        verify(_.collect{ case (JArray(keys), v) if v != 0 => (keys, v) }.toMap must_== expectedCounts)
      }
    }

    "retrieve intersection series for a slice of time" in {      
      //skip("disabled")
      val granularity = Minute
      val (events, minDate, maxDate) = timeSlice(sampleEvents, granularity)
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.AnonLocation(com.reportgrid.analytics.Path("usa")))
      )

      val variables   = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Variable(".tweeted.twitterClient") :: Nil
      val descriptors = variables.map(v => VariableDescriptor(v, 10, SortOrder.Descending))

      val expectedCounts = events.foldLeft(Map.empty[List[JValue], Int]) {
        case (map, Event("tweeted", data, _)) =>
          val values = variables.map(v => data.value(JPath(v.name.nodes.drop(1))))
          map + (values -> map.get(values).map(_ + 1).getOrElse(1))

        case (map, _) => map
      }

      engine.getIntersectionSeries(Token.Benchmark, "/test", descriptors, queryTerms) must whenDelivered {
        verify { results => 
          // all returned results must have the same length of time series
          val sizes = results.map(_._2).map(_.size).filter(_ > 0)

          results.isEmpty || (
            (sizes.zip(sizes.tail).forall { case (a, b) => a must_== b }) &&
            (results.map(((_: ResultSet[JObject, CountType]).total).second).collect{ case (JArray(keys), v) if v != 0 => (keys, v) }.toMap must_== expectedCounts)
          )
        }
      }
    }

    "retrieve all values of infinitely-valued variables that co-occurred with an observation" in {
      //pendingUntilFixed {
        //skip("disabled")
        val granularity = Minute
        val (events, minDate, maxDate) = timeSlice(sampleEvents, granularity)

        val expectedValues = events.foldLeft(Map.empty[(String, JPath, JValue), Set[JValue]]) {
          case (map, Event(eventName, data, tags)) =>
            data.flattenWithPath.foldLeft(map) {
              case (map, (jpath, value)) if !jpath.endsInInfiniteValueSpace =>
                val key = (eventName, jpath, value)
                map + (key -> (map.getOrElse(key, Set.empty[JValue]) + (data.value \ "~tweet")))

              case (map, _) => map
            }
        }

        expectedValues.foreach {
          case ((eventName, jpath, jvalue), infiniteValues) => 
            engine.findRelatedInfiniteValues(
              Token.Benchmark, "/test", 
              JointObservation(HasValue(Variable(JPath(eventName) \ jpath), jvalue)),
              TimeSpan(minDate, maxDate)
            ) map {
              _.map(_.value).toSet
            } must whenDelivered {
              beEqualTo(infiniteValues)
            }
        }
      //}
    }

    // this test is here because it's testing internals of the analytics service
    // which are not exposed though the analytics api
    //"AnalyticsService.serializeIntersectionResult must not create duplicate information" in {
    //  val granularity = Minute
    //  val (events, minDate, maxDate) = timeSlice(sampleEvents, granularity)
    //  val variables   = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Variable(".tweeted.twitterClient") :: Nil
    //  val descriptors = variables.map(v => VariableDescriptor(v, 10, SortOrder.Descending))

    //  def isFullTimeSeries(jobj: JObject): Boolean = {
    //    jobj.fields.foldLeft(true) {
    //      case (cur, JField(_, jobj @ JObject(_))) => cur && isFullTimeSeries(jobj)
    //      case (cur, JField(_, JArray(values))) => 
    //        cur && (values must notBeEmpty) && (values.map{ case JArray(v) => v(0) }.toSet.size must_== values.size)
    //      case _ => false
    //    }
    //  }

    //  engine.getIntersectionSeries(Token.Benchmark, "/test", descriptors, granularity, Some(minDate), Some(maxDate)).
    //  map(AnalyticsService.serializeIntersectionResult[TimeSeriesType](_, _.serialize)) must whenDelivered {
    //    beLike {
    //      case v @ JObject(_) => isFullTimeSeries(v)
    //    }
    //  }
    //}
  }
}

