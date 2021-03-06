package com.reportgrid.analytics

import blueeyes._
import blueeyes.bkka._
import blueeyes.core.http.{HttpStatus, HttpResponse, MimeTypes}
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.concurrent.test.FutureMatchers
import blueeyes.health.HealthMonitor
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
import com.weiglewilczek.slf4s.Logging

import org.joda.time.Instant
import org.specs2.mutable.Specification
import org.specs2.specification.{Fragment, Fragments, Outside, Scope, SpecificationStructure, Step}
import org.specs2.matcher.MatchResult
import org.specs2.execute.Result
import org.scalacheck._
import scala.math.Ordered._
import Gen._
import scalaz._
import Scalaz._
import Periodicity._

import java.util.concurrent.TimeUnit

// For DB cleanup
import com.weiglewilczek.slf4s.Logging
import com.mongodb.Mongo

trait LocalMongo extends Specification with Logging {
  val eventsName = "testev%04d".format(scala.util.Random.nextInt(10000))
  val indexName =  "testix%04d".format(scala.util.Random.nextInt(10000))

  def mongoConfigFileData = """
    eventsdb {
      database = "%s"
      servers  = ["127.0.0.1:27017"]
    }

    indexdb {
      database = "%s"
      servers  = ["127.0.0.1:27017"]
    }

    tokens {
      collection = "tokens"
    }

    variable_series {
      collection = "variable_series"
      time_to_idle_millis = 100
      time_to_live_millis = 100

      initial_capacity = 100
      maximum_capacity = 100
    }

    variable_value_series {
      collection = "variable_value_series"

      time_to_idle_millis = 100
      time_to_live_millis = 100

      initial_capacity = 100
      maximum_capacity = 100
    }

    variable_values {
      collection = "variable_values"

      time_to_idle_millis = 100
      time_to_live_millis = 100

      initial_capacity = 100
      maximum_capacity = 100
    }

    variable_children {
      collection = "variable_children"

      time_to_idle_millis = 100
      time_to_live_millis = 100

      initial_capacity = 100
      maximum_capacity = 100
    }

    path_children {
      collection = "path_children"

      time_to_idle_millis = 100
      time_to_live_millis = 100

      initial_capacity = 100
      maximum_capacity = 100
    }

    log {
      level   = "warning"
      console = true
    }
  """.format(eventsName, indexName)

  // We need to remove the databases used from Mongo after we're done
  def cleanupDb = Step {
    try {
      val conn = new Mongo("localhost")

      logger.info("Dropping events DB: " + eventsName)
      conn.getDB(eventsName).dropDatabase()
      logger.info("Dropped " + eventsName)

      logger.info("Dropping index DB: " + indexName)
      conn.getDB(indexName).dropDatabase()
      logger.info("Dropped " + indexName)
      
      conn.close()
    } catch {
      case t => logger.error("Error on DB cleanup: " + t.getMessage, t)
    }
  }

  override def map(fs : => Fragments) = super.map(fs) ^ cleanupDb
}

trait AggregationEngineTests extends Specification with FutureMatchers with ArbitraryEvent {
  val TestToken = Token(
    tokenId        = "C7A18C95-3619-415B-A89B-4CE47693E4CC",
    parentTokenId  = Some(Token.Root.tokenId),
    accountTokenId = "C7A18C95-3619-415B-A89B-4CE47693E4CC",
    path           = "unittest",
    permissions    = Permissions(true, true, true, true),
    createdAt      = Token.ReportGridStart,
    expires        = Token.Never,
    limits         = Limits(order = 1, depth = 5, limit = 20, tags = 2, rollup = 2)
  )

  def countStoredEvents(sampleEvents: List[Event], engine: AggregationEngine) = {
    //skip("disabled")
    def countEvents(eventName: String) = sampleEvents.count {
      case Event(name, _, _) => name == eventName
    }

    val eventCounts = EventTypes.map(eventName => (eventName, countEvents(eventName))).toMap

    val queryTerms = List[TagTerm](
      HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
    )

    forall(eventCounts) {
      case (name, count) => engine.getVariableCount(TestToken, "/test", Variable("." + name), queryTerms) must whenDelivered(be_==(count))
    }
  }
}

trait AggregationEngineFixtures extends LocalMongo with Logging {
  val config = (new Config()) ->- (_.load(mongoConfigFileData))

  val eventsConfig = config.configMap("eventsdb")

  val insertEventsMongo = new RealMongo(eventsConfig) 
  val insertEventsdb = insertEventsMongo.database(eventsConfig("database"))
  
  val queryEventsMongo = new RealMongo(eventsConfig) 
  val queryEventsdb = queryEventsMongo.database(eventsConfig("database"))
  
  val indexConfig = config.configMap("indexdb")
  val indexMongo = new RealMongo(indexConfig)
  val indexdb = indexMongo.database(indexConfig("database"))

  val engine = AggregationEngine.forConsole(config, logger, insertEventsdb, queryEventsdb, indexdb, HealthMonitor.Noop)

  implicit val timeout = akka.actor.Actor.Timeout(120000) 

  import blueeyes.concurrent.Future._

  def stopAggregationEngineFixture() { 
    val shutdown = Future.sync(
      Stoppable(engine,
        Stoppable(indexdb, Stoppable(indexMongo) :: Nil) ::
        Stoppable(insertEventsdb, Stoppable(insertEventsMongo) :: Nil) ::
        Stoppable(queryEventsdb, Stoppable(queryEventsMongo) :: Nil) :: Nil
      )
    )
    shutdown.flatMap { stoppable => (Stoppable.stop(stoppable)).toBlueEyes }.toAkka.get
  }
}




class AggregationEngineSpec extends AggregationEngineTests with AggregationEngineFixtures {
  import AggregationEngine._

  // Ensure that DB cleanup runs at the end
  sequential

  val genTimeClock = Clock.System

  override implicit val defaultFutureTimeouts = FutureTimeouts(15, toDuration(1000).milliseconds)

  object sampleData extends Outside[List[Event]] with Scope {
    val outside = containerOfN[List, Event](50, fullEventGen).sample.get ->- {
      _.foreach { event => 
        engine.aggregate(TestToken, "/test", event.eventName, event.tags, event.data, 1)
        engine.store(TestToken, "/test", event.eventName, event.messageData, Tag.Tags(Future.sync(event.tags)), 1, 1, false)
      }
    }
  }

  "Aggregating full events" should {
    "retrieve path children" in sampleData { sampleEvents =>
      //skip("disabled")
      val children = sampleEvents.map { case Event(eventName, _, _) => "." + eventName }.toSet

      engine.getPathChildren(TestToken, "/test") must whenDelivered {
        haveTheSameElementsAs(children)
      }
    }
 
    "retrieve variable children" in sampleData { sampleEvents =>
      //skip("disabled")
      val expectedChildren = sampleEvents.foldLeft(Map.empty[String, Set[String]]) {
        case (m, Event(eventName, EventData(JObject(fields)), _)) => 
          val properties = fields.map("." + _.name)
          m + (eventName -> (m.getOrElse(eventName, Set.empty[String]) ++ properties))
      }
    
      forall(expectedChildren) { 
        case (eventName, children) => 
          engine.getVariableChildren(TestToken, "/test", Variable(JPath("." + eventName))).map(_.map(_._1.child.toString)) must whenDelivered[List[String]] {
            haveTheSameElementsAs(children)
          }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve variable children limited by time ranges" in sampleData {
      sampleEvents => {
        val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
        
        // Select the middle 50% of the range
        val rangeDelta = maxDate.getMillis - minDate.getMillis
        val (rangeStart,rangeEnd) = (minDate.plus((rangeDelta * 0.25).toLong), minDate.plus((rangeDelta * 0.75).toLong))
        val rangedEvents = events.filter( event => event.timestamp.map(ts => ts.compareTo(rangeStart) >= 0 && ts.compareTo(rangeEnd) < 0).getOrElse(false))
    
        rangedEvents must not be empty
    
        val expectedChildren = rangedEvents.foldLeft(Map.empty[String, Set[String]]) {
          case (m, Event(eventName, EventData(JObject(fields)), _)) => 
            val properties = fields.map("." + _.name)
            m + (eventName -> (m.getOrElse(eventName, Set.empty[String]) ++ properties))
        }
    
        val terms = List(SpanTerm(AggregationEngine.timeSeriesEncoding, TimeSpan(rangeStart,rangeEnd)))
    
        forall(expectedChildren) { 
          case (eventName, children) => 
            engine.getVariableChildren(TestToken, "/test", Variable(JPath("." + eventName)), terms).map(_.map(_._1.child.toString)) must whenDelivered[List[String]] {
              haveTheSameElementsAs(children)
            }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
        } 
      }
    }
    
    "retrieve path tags" in sampleData { sampleEvents =>
      //skip("disabled")
      val children: Set[String] = sampleEvents.flatMap({ case Event(_, _, tags) => tags.map(_.name) })(collection.breakOut)
    
      engine.getPathTags(TestToken, "/test") must whenDelivered {
        haveTheSameElementsAs(children)
      }
    }
    
    "retrieve hierarchy children" in sampleData { sampleEvents =>
      val expectedChildren = sampleEvents.flatMap(_.tags).foldLeft(Map.empty[Path, Set[String]]) {
        case (m, Tag("location", Hierarchy(locations))) => 
          locations.foldLeft(m) {
            (m, l) => l.path.parent match {
              case Some(parent) => m + (parent -> (m.getOrElse(parent, Set.empty[String]) + l.path.elements.last))
              case None => m
            }
          }
        case (m, _) => m
      } 
    
      forall(expectedChildren) {
        case (path, children) => 
          engine.getHierarchyChildren(TestToken, "/test", "location", JPath(path.elements.map(JPathField(_)): _*)).map(_.toSet) must whenDelivered {
            be_==(children)
          }
      }
    }
    
    "count events" in sampleData { sampleEvents =>
      def countEvents(eventName: String) = sampleEvents.count {
        case Event(name, _, _) => name == eventName
      }
    
      val queryTerms = List[TagTerm](
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      forall(EventTypes.map(eventName => (eventName, countEvents(eventName)))) {
        case (name, count) => 
          engine.getVariableCount(TestToken, "/test", Variable("." + name), queryTerms) must whenDelivered[Long] {
            be_==(count)
          }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve values" in sampleData { sampleEvents =>
      //skip("disabled")
      val values = sampleEvents.foldLeft(Map.empty[(String, JPath), Set[JValue]]) { 
        case (map, Event(eventName, obj, _)) =>
          obj.flattenWithPath.foldLeft(map) {
            case (map, (path, value)) if (!path.endsInInfiniteValueSpace) => 
              val key = (eventName, path)
              val oldValues = map.getOrElse(key, Set.empty)
    
              map + (key -> (oldValues + value))
            case (map, _) => map
          }
    
        case (map, _) => map
      }
    
      forall(values) {
        case ((eventName, path), values) => 
          engine.getValues(TestToken, "/test", Variable(JPath(eventName) \ path), Nil) must whenDelivered[Seq[JValue]] {
            haveTheSameElementsAs(values)
          }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve all values of arrays" in sampleData { sampleEvents =>
      //skip("disabled")
      val arrayValues = sampleEvents.foldLeft(Map.empty[(String, JPath), Set[JValue]]) { 
        case (map, Event(eventName, obj, _)) =>
          map <+> ((obj.children.collect { case JField(name, JArray(elements)) => ((eventName, JPath(name)), elements.toSet) }).toMap)
    
        case (map, _) => map
      }
    
      forall(arrayValues) {
        case ((eventName, path), values) =>
          logger.debug("Querying values for " + Variable(JPath(eventName) \ path))
          engine.getValues(TestToken, "/test", Variable(JPath(eventName) \ path), Nil) must whenDelivered[Seq[JValue]] {
            haveTheSameElementsAs(values)
          }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve the top results of a histogram" in sampleData { sampleEvents => 
      //skip("disabled")
      val retweetCounts = sampleEvents.foldLeft(Map.empty[JValue, Int]) {
        case (map, Event("tweeted", data, _)) => 
          val key = data.value(".retweet")
          map + (key -> map.get(key).map(_ + 1).getOrElse(1))
    
        case (map, _) => map
      }
    
      engine.getHistogramTop(TestToken, "/test", Variable(".tweeted.retweet"), 10, Nil) must whenDelivered[ResultSet[JValue,CountType]] {
        haveTheSameElementsAs(retweetCounts)
      }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
    }
    
    "retrieve the results of a histogram limited with a where clause" in sampleData { sampleEvents => 
      def isFemaleEvent(e : Event) = e.data.value(".gender") match {
        case JString("female") => true
        case _                 => false
      }
    
      val femaleRetweetCounts = sampleEvents.filter(isFemaleEvent).foldLeft(Map.empty[JValue, Int]) {
        case (map, Event("tweeted", data, _)) => 
          val key = data.value(".retweet")
          map + (key -> map.get(key).map(_ + 1).getOrElse(1))
    
        case (map, _) => map
      }
    
      engine.getHistogramTop(TestToken, "/test", Variable(".tweeted.retweet"), 10, Nil, Set(HasValue(Variable(".tweeted.gender"), JString("female")))) must whenDelivered[ResultSet[JValue,CountType]] {
        haveTheSameElementsAs(femaleRetweetCounts)
      }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
    }
    
    "retrieve histograms limited by time ranges" in sampleData { 
      sampleEvents => {
        val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
        
        // Select the middle 50% of the range
        val rangeDelta = maxDate.getMillis - minDate.getMillis
        val (rangeStart,rangeEnd) = (minDate.plus((rangeDelta * 0.25).toLong), minDate.plus((rangeDelta * 0.75).toLong))
        val rangedEvents = events.filter( event => event.timestamp.map(ts => ts.compareTo(rangeStart) >= 0 && ts.compareTo(rangeEnd) < 0).getOrElse(false))
    
        rangedEvents must not be empty
    
        val retweetCounts = rangedEvents.foldLeft(Map.empty[JValue,Long]) {
          case (map, Event("tweeted", data, _)) => 
            val key = data.value(".retweet")
            map + (key -> map.get(key).map(_ + 1).getOrElse(1))
          
          case (map, _) => map
        }
    
        val queryTerms = List[TagTerm](
          IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(rangeStart, rangeEnd))
        )
    
        engine.getHistogram(TestToken, "/test", Variable(".tweeted.retweet"), queryTerms) must whenDelivered[Map[JValue,CountType]] {
          haveTheSameElementsAs(retweetCounts)
        }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }                                       
    }
    
    "retrieve histograms limited by location" in sampleData { 
      sampleEvents => {
        val coloradoEvents = sampleEvents.filter {
          event => event.location.map(_.exists {
            case Hierarchy.NamedLocation("state", path) => path == Path("usa/colorado")
            case _ => false
          }).getOrElse(false)
        }
    
        coloradoEvents must not be empty
          
        val retweetCounts = coloradoEvents.foldLeft(Map.empty[JValue,Long]) {
          case (map, Event("tweeted", data, _)) => 
            val key = data.value(".retweet")
            map + (key -> map.get(key).map(_ + 1).getOrElse(1))
          
          case (map, _) => map
        }
        
    
        val queryTerms = List[TagTerm](
          HierarchyLocationTerm("location", Hierarchy.NamedLocation("state", com.reportgrid.analytics.Path("usa/colorado")))
        )
    
        engine.getHistogram(TestToken, "/test", Variable(".tweeted.retweet"), queryTerms) must whenDelivered[Map[JValue,CountType]] {
          haveTheSameElementsAs(retweetCounts)
        }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve values limited by time ranges" in sampleData {
      sampleEvents => {
        val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
        
        // Select the middle 50% of the range
        val rangeDelta = maxDate.getMillis - minDate.getMillis
        val (rangeStart,rangeEnd) = (minDate.plus((rangeDelta * 0.25).toLong), minDate.plus((rangeDelta * 0.75).toLong))
        val rangedEvents = events.filter( event => event.timestamp.map(ts => ts.compareTo(rangeStart) >= 0 && ts.compareTo(rangeEnd) < 0).getOrElse(false))
    
        rangedEvents must not be empty
    
        val retweetValues = rangedEvents.foldLeft(Set.empty[JValue]) {
          case (set, Event("tweeted", data, _)) => 
            set + data.value(".retweet")
          case (set, _) => set
        }
    
        val queryTerms = List[TagTerm](
          IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(rangeStart, rangeEnd))
        )
    
        engine.getValues(TestToken, "/test", Variable(".tweeted.retweet"), queryTerms) must whenDelivered[Seq[JValue]] {
          haveTheSameElementsAs(retweetValues)
        }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve values limited by location" in sampleData {
      sampleEvents => {
        val coloradoEvents = sampleEvents.filter {
          event => event.location.map(_.exists {
            case Hierarchy.NamedLocation("state", path) => path == Path("usa/colorado")
            case _ => false
          }).getOrElse(false)
        }
    
        coloradoEvents must not be empty
          
        val retweetValues = coloradoEvents.foldLeft(Set.empty[JValue]) {
          case (set, Event("tweeted", data, _)) => 
            set + data.value(".retweet")
          case (set, _) => set
        }
    
        val queryTerms = List[TagTerm](
          HierarchyLocationTerm("location", Hierarchy.NamedLocation("state", com.reportgrid.analytics.Path("usa/colorado")))
        )
    
        engine.getValues(TestToken, "/test", Variable(".tweeted.retweet"), queryTerms) must whenDelivered[Seq[JValue]] {
          haveTheSameElementsAs(retweetValues)
        }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve totals" in sampleData { sampleEvents =>
      //skip("disabled")
      val expectedTotals = valueCounts(sampleEvents).filterNot {
        case ((jpath, _), _) => jpath.endsInInfiniteValueSpace
      }
    
      val queryTerms = List[TagTerm](
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      forall(expectedTotals) {
        case ((jpath, value), count) =>
          engine.getObservationCount(TestToken, "/test", JointObservation(HasValue(Variable(jpath), value)), queryTerms) must whenDelivered[Long] {
            be_==(count.toLong)
          }(FutureTimeouts(1, Duration(20, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve a time series for occurrences of an event" in sampleData { sampleEvents =>
      //skip("disabled")
      logger.trace("Retrieve time series for event")
      val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
    
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      val expectedTotals = events.foldLeft(Map.empty[JPath, Int]) {
        case (map, Event(eventName, obj, _)) =>
          val key = JPath(eventName) 
          map + (key -> (map.getOrElse(key, 0) + 1))
      }
    
      forall(expectedTotals) {
        case (jpath, count) =>
          engine.getVariableSeries(TestToken, "/test", Variable(jpath), queryTerms).map(_.total.count) must whenDelivered[Long] {
            be_==(count.toLong)
          }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }

    "retrieve a time series for occurrences of an event with an offset in ms" in sampleData { sampleEvents =>
      val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
      logger.trace("Retrieve time series for event from %s → %s".format(minDate, maxDate))
    
      // Skip the event(s) at our max boundary
      val upperBound = granularity.floor(maxDate).getMillis
      val offset = new org.joda.time.Duration(maxDate.getMillis - upperBound)
      val offsetTimestamp = maxDate.minus(offset)

      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate), offset),
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      val expectedTotals = events.foldLeft(Map.empty[JPath, Int]) {
        case (map, e @ Event(eventName, obj, _)) =>
          val key = JPath(eventName) 
          if (e.timestamp.map(_.isBefore(offsetTimestamp)).getOrElse(false)) {
            map + (key -> (map.getOrElse(key, 0) + 1))
          } else {
            map
          }
      }
    
      forall(expectedTotals) {
        case (jpath, count) =>
          engine.getVariableSeries(TestToken, "/test", Variable(jpath), queryTerms).map(_.total.count) must whenDelivered[Long] {
            be_==(count.toLong)
          }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve a time series of means of values of a variable over eternity" in sampleData { sampleEvents =>
      logger.trace("Retrieving time series means")
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, Eternity, TimeSpan(new Instant(42), new Instant))
      )
    
      forall(expectedMeans(sampleEvents, "recipientCount", keysf(Eternity))) {
        case (eventName, means) =>
          val expected: Map[String, Double] = means.map{ case (k, v) => (k(0), v) }.toMap
    
          engine.getVariableSeries(TestToken, "/test", Variable(JPath(eventName) \ "recipientCount"), queryTerms) must whenDelivered {
            beLike { 
              case result => 
                val remapped: Map[String, Double] = result.flatMap{ case (k, v) => v.mean.map((k \ "timestamp").deserialize[Instant].toString -> _) }.toMap 
                remapped must_== expected
            }
          }
      }
    }
    
    "retrieve a time series of means of values of a variable" in sampleData { sampleEvents =>
      //skip("disabled")
      val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
    
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      forall(expectedMeans(events, "recipientCount", keysf(granularity))) {
        case (eventName, means) =>
          val expected: Map[String, Double] = means.map{ case (k, v) => (k(0), v) }.toMap
    
          engine.getVariableSeries(TestToken, "/test", Variable(JPath(eventName) \ "recipientCount"), queryTerms) must whenDelivered[ResultSet[JObject,ValueStats]]{
            beLike { 
              case result => 
                val remapped: Map[String, Double] = result.flatMap{ case (k, v) => v.mean.map((k \ "timestamp").deserialize[Instant].toString -> _) }.toMap 
                remapped must_== expected
            }
          }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve a time series for occurrences of a value of a variable" in sampleData { sampleEvents =>
      //skip("disabled")
      val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
    
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      forallWhen(valueCounts(events)) {
        case ((jpath, value), count) if !jpath.endsInInfiniteValueSpace =>
          val observation = JointObservation(HasValue(Variable(jpath), value))
    
          engine.getObservationSeries(TestToken, "/test", observation, queryTerms) must whenDelivered[ResultSet[JObject,CountType]] {
            beLike { case results => 
              (results.total must_== count.toLong) and
              (results must haveSize((granularity.period(minDate) until maxDate).size))
            }
          }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve a time series for occurrences of a value of a variable via the raw events" in sampleData { sampleEvents =>
      ////skip("disabled")
      val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
      val expectedTotals = valueCounts(events) 
    
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      forallWhen(expectedTotals) {
        case ((jpath, value), count) if !jpath.endsInInfiniteValueSpace => 
          val observation = JointObservation(HasValue(Variable(jpath), value))
    
          engine.getRawEvents(TestToken, "/test", observation, queryTerms).map(AggregationEngine.countByTerms(_, queryTerms)) must whenDelivered[Map[JObject,CountType]] {
            beLike { case results => 
              (results.toSeq.total must_== count.toLong) and 
              (results must haveSize((granularity.period(minDate) until maxDate).size))
            }
          }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "count observations of a given value" in sampleData { sampleEvents =>
      //skip("disabled")
      val variables = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Nil
    
      val queryTerms = List[TagTerm](
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      val expectedCounts: Map[List[JValue], CountType] = sampleEvents.foldLeft(Map.empty[List[JValue], CountType]) {
        case (map, Event("tweeted", data, _)) =>
          val values = variables.map(v => data.value(JPath(v.name.nodes.drop(1))))
          map + (values -> map.get(values).map(_ + 1L).getOrElse(1L))
    
        case (map, _) => map
      }
    
      forall(expectedCounts) {
        case (values, count) =>
          val observation = JointObservation((variables zip values).map((HasValue(_, _)).tupled).toSet)
          forall(observation.obs) { hasValue => 
            engine.getObservationCount(TestToken, "/test", JointObservation(hasValue), queryTerms) must whenDelivered[CountType] {
              beGreaterThanOrEqualTo(count)
            }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
          } and {
            engine.getObservationCount(TestToken, "/test", observation, queryTerms) must whenDelivered[CountType] {
              be_== (count)
            }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
          }
    
      }
    }
    
    "count observations of a given value in a restricted time slice" in sampleData { sampleEvents =>
      //skip("disabled")
      val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
      val queryTerms = List[TagTerm](
        SpanTerm(AggregationEngine.timeSeriesEncoding, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      val variables = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Nil
    
      val expectedCounts: Map[List[JValue], Long] = events.foldLeft(Map.empty[List[JValue], Long]) {
        case (map, Event("tweeted", data, _)) =>
          val values = variables.map(v => data.value(JPath(v.name.nodes.drop(1))))
          map + (values -> map.get(values).map(_ + 1L).getOrElse(1L))
    
        case (map, _) => map
      }
    
      forall(expectedCounts) {
        case (values, count) =>
          val observation = JointObservation((variables zip values).map((HasValue(_, _)).tupled).toSet)
          engine.getObservationCount(TestToken, "/test", observation, queryTerms) must whenDelivered[CountType] (beEqualTo(count))(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
      }
    }
    
    "retrieve intersection counts" in sampleData { sampleEvents =>
      //skip("disabled")
      val variables   = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Nil
      val descriptors = variables.map(v => VariableDescriptor(v, 10, SortOrder.Descending))
    
      val queryTerms = List[TagTerm](
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      val expectedCounts = sampleEvents.foldLeft(Map.empty[List[JValue], Int]) {
        case (map, Event("tweeted", data, _)) =>
          val values = variables.map(v => data.value(JPath(v.name.nodes.drop(1))))
    
          map + (values -> map.get(values).map(_ + 1).getOrElse(1))
    
        case (map, _) => map
      }
    
      engine.getIntersectionCount(TestToken, "/test", descriptors, queryTerms, Set()) must whenDelivered[ResultSet[JArray,CountType]] {
        beLike { case result => 
          result.collect{ case (JArray(keys), v) if v != 0 => (keys, v) }.toMap must_== expectedCounts
        }
      }(FutureTimeouts(1, Duration(120, TimeUnit.SECONDS)))
    }
    
    "retrieve intersection counts for a slice of time" in sampleData { sampleEvents =>
      //skip("disabled")
      val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      val variables   = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Nil
      val descriptors = variables.map(v => VariableDescriptor(v, 10, SortOrder.Descending))
    
      val expectedCounts = events.foldLeft(Map.empty[List[JValue], Int]) {
        case (map, Event("tweeted", data, _)) =>
          val values = variables.map(v => data.value(JPath(v.name.nodes.drop(1))))
    
          map + (values -> map.get(values).map(_ + 1).getOrElse(1))
    
        case (map, _) => map
      }
    
      engine.getIntersectionCount(TestToken, "/test", descriptors, queryTerms, Set()).
      map(_.collect{ case (JArray(keys), v) if v != 0 => (keys, v) }.toMap) must {
        whenDelivered[Map[List[JValue], CountType]]( be_== (expectedCounts) )(FutureTimeouts(5, toDuration(3000).milliseconds))
      }
    }
    
    "retrieve intersection series for a slice of time" in sampleData { sampleEvents =>
      //skip("disabled")
      val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
      val queryTerms = List[TagTerm](
        IntervalTerm(AggregationEngine.timeSeriesEncoding, granularity, TimeSpan(minDate, maxDate)),
        HierarchyLocationTerm("location", Hierarchy.NamedLocation("country", com.reportgrid.analytics.Path("usa")))
      )
    
      val variables   = Variable(".tweeted.retweet") :: Variable(".tweeted.recipientCount") :: Variable(".tweeted.twitterClient") :: Nil
      val descriptors = variables.map(v => VariableDescriptor(v, 10, SortOrder.Descending))
    
      val expectedCounts = events.foldLeft(Map.empty[List[JValue], Int]) {
        case (map, Event("tweeted", data, _)) =>
          val values = variables.map(v => data.value(JPath(v.name.nodes.drop(1))))
          map + (values -> map.get(values).map(_ + 1).getOrElse(1))
    
        case (map, _) => map
      }
    
      engine.getIntersectionSeries(TestToken, "/test", descriptors, queryTerms, Set()) must {
        whenDelivered[ResultSet[JArray, ResultSet[JObject, CountType]]] ({
          beLike { 
            case results => 
              // all returned results must have the same length of time series
              val sizes = results.map(_._2).map(_.size).filter(_ > 0)
    
              (forall(sizes.zip(sizes.tail)) { case (a, b) => a must_== b }) and
              (results.map(((_: ResultSet[JObject, CountType]).total).second).collect{ case (JArray(keys), v) if v != 0 => (keys, v) }.toMap must_== expectedCounts)
          }
        })(FutureTimeouts(1, toDuration(600000).milliseconds))
      }
    }
    
    "retrieve all values of infinitely-valued variables that co-occurred with an observation" in sampleData { sampleEvents =>
      //skip("disabled")
      val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
    
      val expectedValues = events.foldLeft(Map.empty[(String, JPath, JValue), Set[JValue]]) {
        case (map, Event(eventName, data, tags)) =>
          data.flattenWithPath.foldLeft(map) {
            case (map, (jpath, value)) =>
              val key = (eventName, jpath, value)
              map + (key -> (map.getOrElse(key, Set.empty[JValue]) + (data.value \ "~tweet")))
          }
      }
    
      forallWhen(expectedValues) {
        case ((eventName, jpath, jvalue), infiniteValues) if !jpath.endsInInfiniteValueSpace  => 
          engine.findRelatedInfiniteValues(
            TestToken, "/test", 
            JointObservation(HasValue(Variable(JPath(eventName) \ jpath), jvalue)),
            List(SpanTerm(AggregationEngine.timeSeriesEncoding, TimeSpan(minDate, maxDate)))
          ) map {
            _.map(_.value).toSet
          } must whenDelivered {
            beEqualTo(infiniteValues)
          }
      }
    }

    // this test is here because it's testing internals of the analytics service
    // which are not exposed though the analytics api
    //"AnalyticsService.serializeIntersectionResult must not create duplicate information" in {
    //  val (events, minDate, maxDate, granularity) = timeSlice(sampleEvents)
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

    //  engine.getIntersectionSeries(TestToken, "/test", descriptors, granularity, Some(minDate), Some(maxDate)).
    //  map(AnalyticsService.serializeIntersectionResult[TimeSeriesType](_, _.serialize)) must whenDelivered {
    //    beLike {
    //      case v @ JObject(_) => isFullTimeSeries(v)
    //    }
    //  }
    //}
  }

  step(stopAggregationEngineFixture)
  step(cleanupDb)
}

class ReaggregationSpec extends AggregationEngineTests with AggregationEngineFixtures with TestTokenStorage {
  import AggregationEngine._

  val genTimeClock = Clock.System

  // Ensure that DB cleanup runs at the end
  sequential

  override implicit val defaultFutureTimeouts = FutureTimeouts(40, toDuration(500).milliseconds)

  object sampleData extends Outside[List[Event]] with Scope {
    def outside = containerOfN[List, Event](10, fullEventGen).sample.get ->- {
      _ foreach { event => 
        val fut = engine.store(TestToken, "/test", event.eventName, event.messageData, Tag.Tags(Future.sync(event.tags)), 1, 0, true)
        while(!fut.isDone) { }
      }
    }
  }

  "Re-storing aggregated events" should {
    "retrieve and re-aggregate data" in sampleData { sampleEvents =>
      queryEventsdb(selectAll.from("events").where("reprocess" === true)) map { _.size } must whenDelivered {
        beLike {
          case outstanding if outstanding == sampleEvents.size =>
            ReaggregationTool.reprocess(engine, tokenManager, 0, outstanding, outstanding)

            queryEventsdb(selectAll.from("events").where("reprocess" === true)) map { _.size } must whenDelivered {
              be_== (0)
            }

            countStoredEvents(sampleEvents, engine)
        }
      }
    }
  }

  step(stopAggregationEngineFixture)
  step(cleanupDb)
}
