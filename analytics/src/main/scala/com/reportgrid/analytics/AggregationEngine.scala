package com.reportgrid.analytics

import blueeyes._
import blueeyes.concurrent.{Future, FutureDeliveryStrategySequential}
import blueeyes.persistence.mongo._
import blueeyes.persistence.cache.{Stage, ExpirationPolicy, CacheSettings}
import blueeyes.json.JsonAST._
import blueeyes.json.JsonDSL._
import blueeyes.json._
import blueeyes.json.xschema._
import blueeyes.json.xschema.DefaultSerialization._

import net.lag.configgy.ConfigMap
import net.lag.logging.Logger

import org.joda.time.{DateTime, DateTimeZone}

import java.util.concurrent.TimeUnit

import com.reportgrid.analytics.AggregatorImplicits._
import com.reportgrid.analytics.persistence.MongoSupport._
import scala.collection.SortedMap
import scalaz.Scalaz._
import Future._

class AggregationEngine private (config: ConfigMap, logger: Logger, database: MongoDatabase) extends FutureDeliveryStrategySequential {
  val EarliestTime = new DateTime(0,             DateTimeZone.UTC)
  val LatestTime   = new DateTime(Long.MaxValue, DateTimeZone.UTC)

  type CountType          = Long
  type TimeSeriesType     = TimeSeries[CountType]

  type ChildReport        = Report[CountType, HasChild]
  val  ChildReportEmpty   = Report.empty[CountType, HasChild]

  type ValueReport        = Report[TimeSeriesType, HasValue]
  val  ValueReportEmpty   = Report.empty[TimeSeriesType, HasValue]

  private def newMongoStage(prefix: String): (MongoStage, MongoCollection) = {
    val timeToIdle      = config.getLong(prefix + ".time_to_idle_millis").getOrElse(10000L)
    val timeToLive      = config.getLong(prefix + ".time_to_live_millis").getOrElse(10000L)
    val initialCapacity = config.getInt (prefix + ".initial_capacity").getOrElse(1000)
    val maximumCapacity = config.getInt (prefix + ".maximum_capacity").getOrElse(10000)

    val collection = config.getString(prefix + ".collection").getOrElse(prefix)

    (new MongoStage(
      database   = database,
      collection = collection,
      mongoStageSettings = MongoStageSettings(
        expirationPolicy = ExpirationPolicy(
          timeToIdle = Some(timeToIdle),
          timeToLive = Some(timeToLive),
          unit       = TimeUnit.MILLISECONDS
        ),
        maximumCapacity = maximumCapacity
      )
    ), collection)
  }

  private val (varSeriesS,      varSeriesC)       = newMongoStage("variable_series")
  private val (varValueSeriesS, varValueSeriesC)  = newMongoStage("variable_value_series")

  private val (varValueS,       varValueC)        = newMongoStage("variable_values")
  private val (varChildS,       varChildC)        = newMongoStage("variable_children")
  private val (pathChildS,      pathChildC)       = newMongoStage("path_children")

  private val DefaultAggregator = TimeSeriesAggregator.Default

  val ListJValueOrdering = new Ordering[List[JValue]] {
    import blueeyes.json.xschema.DefaultOrderings.JValueOrdering

    def compare(l1: List[JValue], l2: List[JValue]): Int = {
      (l1.zip(l2).map {
        case (v1, v2) => JValueOrdering.compare(v1, v2)
      }).dropWhile(_ == 0).headOption match {
        case None => l1.length compare l2.length
        
        case Some(c) => c
      }
    }
  }

  /** Aggregates the specified data. The object may contain multiple events or
   * just one.
   */
  def aggregate(token: Token, path: Path, time: DateTime, jobject: JObject, count: Long) = {
    Future.async {
      // Keep track of parent/child relationships:
      pathChildS putAll addPathChildrenOfPath(token, path).patches

      val accountPathFilter = forTokenAndPath(token, path)

      val seriesCount = DefaultAggregator.aggregate(time, count)

      val events = jobject.children.collect {
        case JField(eventName, properties) => (eventName, JObject(JField(eventName, properties) :: Nil))
      }

      events.foreach { tuple =>
        val (eventName, event) = tuple

        pathChildS += addChildOfPath(accountPathFilter, "." + eventName)

        val valueReport = Report.ofValues(
          event = event,
          count = seriesCount,
          order = token.limits.order,
          depth = token.limits.depth,
          limit = token.limits.limit
        )

        varValueSeriesS putAll updateTimeSeries(accountPathFilter, valueReport).patches
        varValueS       putAll updateValues(accountPathFilter, valueReport.order(1)).patches

        val childCountReport = Report.ofChildren(
          event = event,
          count = count,
          order = 1,
          depth = token.limits.depth,
          limit = token.limits.limit
        )

        varChildS putAll updateValues(accountPathFilter, childCountReport.order(1)).patches

        val childSeriesReport = Report.ofChildren(
          event = event,
          count = seriesCount,
          order = 1,
          depth = token.limits.depth,
          limit = token.limits.limit
        )

        varSeriesS putAll updateTimeSeries(accountPathFilter, childSeriesReport).patches
      }
    }
  }

  /** Retrieves children of the specified path & variable.
   */
  def getChildren(token: Token, path: Path, variable: Variable): Future[List[String]] = getChildren(token, path, Some(variable))

  /** Retrieves children of the specified path & possibly variable.
   */
  def getChildren(token: Token, path: Path, variable: Option[Variable] = None): Future[List[String]] = {
    val filter = forTokenAndPath(token, path)

    (variable match {
      case None =>
        extractValues(filter, pathChildC) { (jvalue, _) =>
          jvalue.deserialize[String]
        }

      case Some(variable) =>
        extractValues(filter & forVariable(variable), varChildC) { (jvalue, _) =>
          jvalue.deserialize[HasChild].child.toString
        }
    })
  }
  
  def getHistogram(token: Token, path: Path, variable: Variable): Future[Map[JValue, CountType]] = 
    getHistogramInternal(token, path, variable)

  def getHistogramTop(token: Token, path: Path, variable: Variable, n: Int): Future[List[(JValue, CountType)]] = 
    getHistogramInternal(token, path, variable).map(_.toList.sortBy(- _._2).take(n))

  def getHistogramBottom(token: Token, path: Path, variable: Variable, n: Int): Future[List[(JValue, CountType)]] = 
    getHistogramInternal(token, path, variable).map(_.toList.sortBy(_._2).take(n))

  /** Retrieves values of the specified variable.
   */
  def getValues(token: Token, path: Path, variable: Variable): Future[Iterable[JValue]] = 
    getHistogramInternal(token, path, variable).map(_.map(_._1))

  def getValuesTop(token: Token, path: Path, variable: Variable, n: Int): Future[List[JValue]] = 
    getHistogramTop(token, path, variable, n).map(_.map(_._1))

  def getValuesBottom(token: Token, path: Path, variable: Variable, n: Int): Future[List[JValue]] = 
    getHistogramBottom(token, path, variable, n).map(_.map(_._1))

  /** Retrieves the length of array properties, or 0 if the property is not an array.
   */
  def getVariableLength(token: Token, path: Path, variable: Variable): Future[Int] = {
    getChildren(token, path, variable).map { children =>
      children.filterNot(_.endsWith("/")).map(JPath(_)).foldLeft(0) {
        case (length, jpath) =>
          jpath.nodes match {
            case JPathIndex(index) :: Nil => (index + 1).max(length)

            case _ => length
          }
      }
    }
  }

  def getVariableStatistics(token: Token, path: Path, variable: Variable): Future[Statistics] = {
    getHistogram(token, path, variable).map { histogram =>
      (histogram.foldLeft(RunningStats.zero) {
        case (running, (value, count)) =>
          val number = value.deserialize[Double]

          running.update(number, count)
      }).statistics
    }
  }

  /** Retrieves a count of how many times the specified variable appeared in
   * an event.
   */
  def getVariableSeries(token: Token, path: Path, variable: Variable, periodicity: Periodicity, start : Option[DateTime] = None, end : Option[DateTime] = None): Future[TimeSeriesType] = {
    variable.parent match {
      case None =>
        Future.lift(TimeSeries.empty)

      case Some(parent) =>
        val lastNode = variable.name.nodes.last

        internalSearchSeries(varSeriesC, token, path, periodicity, Set((parent, HasChild(lastNode))), start, end)
    }
  }

  /** Retrieves a count of how many times the specified variable appeared in
   * an event.
   */
  def getVariableCount(token: Token, path: Path, variable: Variable): Future[CountType] = {
    getVariableSeries(token, path, variable, Periodicity.Eternity).map(_.total(Periodicity.Eternity))
  }

  /** Retrieves a time series for the specified observed value of a variable.
   */
  def getValueSeries(token: Token, path: Path, variable: Variable, value: JValue, periodicity: Periodicity, start : Option[DateTime] = None, end : Option[DateTime] = None): Future[TimeSeriesType] = {
    searchSeries(token, path, Set(variable -> HasValue(value)), periodicity, start, end)
  }

  /** Retrieves a count for the specified observed value of a variable.
   */
  def getValueCount(token: Token, path: Path, variable: Variable, value: JValue): Future[Long] = {
    getValueSeries(token, path, variable, value, Periodicity.Eternity).map(_.total(Periodicity.Eternity))
  }

  /** Searches time series to locate observations matching the specified criteria.
   */
  def searchSeries(token: Token, path: Path, observation: Observation[HasValue], periodicity: Periodicity, 
    start : Option[DateTime] = None, end : Option[DateTime] = None): Future[TimeSeriesType] = {
    internalSearchSeries(varValueSeriesC, token, path, periodicity, observation, start, end)
  }

  /** Searches counts to locate observations matching the specified criteria.
   */
  def searchCount(token: Token, path: Path, observation: Observation[HasValue],
    start : Option[DateTime] = None, end : Option[DateTime] = None): Future[CountType] = {
    searchSeries(token, path, observation, Periodicity.Eternity,  start, end).map(_.total(Periodicity.Eternity))
  }

  type IntersectionResult[T] = SortedMap[List[JValue], T]

  def intersectCount(token: Token, path: Path, properties: List[VariableDescriptor], 
                     start: Option[DateTime] = None, end: Option[DateTime] = None): Future[IntersectionResult[CountType]] = {
    intersectSeries(token, path, properties, Periodicity.Eternity, start, end).map { series => 
      import series.ordering
      series.map {
        case (k, v) => (k, v.total(Periodicity.Eternity))
      }
    }
  }

  def intersectSeries(token: Token, path: Path, properties: List[VariableDescriptor], 
                      periodicity: Periodicity, start: Option[DateTime] = None, end: Option[DateTime] = None): Future[IntersectionResult[TimeSeriesType]] = {
    internalIntersectSeries(varValueSeriesC, token, path, properties, periodicity, start, end)
  }

  def stop(): Future[Unit] =  for {
    _ <- varValueSeriesS.flushAll
    _ <- varSeriesS.flushAll
    _ <- varChildS.flushAll
    _ <- varValueS.flushAll
    _ <- pathChildS.flushAll
  } yield ()

  /** Creates a bunch of patches to keep track of parent/child path relationships.
   * E.g. if you send "/foo/bar/baz", it will keep track of the following:
   *
   * "/foo" has child "bar"
   * "/foo/bar" has child "baz"
   */
  private def addPathChildrenOfPath(token: Token, path: Path): MongoPatches = {
    val patches = path.parentChildRelations.foldLeft(MongoPatches.empty) { (patches, tuple) =>
      val (parent, child) = tuple

      val (filter, update) = addChildOfPath(forTokenAndPath(token, parent), child.elements.last + "/")

      patches + (filter -> update)
    }

    patches
  }

  /** Pushes the specified name onto a ".values" member of a document. This
   * function is used to keep track of the layout of the virtual file system.
   */
  private def addChildOfPath(filter: MongoFilter, child: String): (MongoFilter, MongoUpdate) = {
    val childField = MongoEscaper.encode(renderNormalized(child.serialize))

    val valuesUpdate = (".values." + childField) inc 1

    (filter -> valuesUpdate)
  }

  /** Creates patches to record variable observations.
   */
  private def updateValues[T, P <: Predicate](filter: MongoFilter, report: Report[T, P])
    (implicit tsUpdater: (JPath, TimeSeriesType) => MongoUpdate, pDecomposer: Decomposer[P]): MongoPatches = {
    report.observationCounts.foldLeft(MongoPatches.empty) { (patches, tuple) =>
      val (observation, _) = tuple

      observation.foldLeft(patches) {
        case (patches, (variable, predicate)) =>

          val filterVariable = filter & forVariable(variable)
          val predicateField = MongoEscaper.encode(renderNormalized(predicate.serialize))

          val valuesUpdate = (JPath(".values") \ JPathField(predicateField)) inc 1

          patches + (filterVariable -> valuesUpdate)
      }
    }
  }

  private implicit def filterWhereObserved(filter: MongoFilter) = FilterWhereObserved(filter)
  private case class FilterWhereObserved(filter: MongoFilter) {
    /*
     * "where": {
     *   "variable1":  ".click.gender",
     *   "predicate1": "male"
     * }
     */
    def whereVariablesEqual[P <: Predicate : Decomposer](observation: Observation[P]): MongoFilter = {
      observation.toSeq.sortBy(_._1).zipWithIndex.foldLeft[MongoFilter](filter) {
        case (filter, ((variable, predicate), index)) =>
          val varName  = ".variable"  + index
          val predName = ".predicate" + index

          filter & 
          JPath(".where" + varName)  === variable.serialize &
          JPath(".where" + predName) === predicate.serialize
      }
    }

    def whereVariablesExist(variables: Seq[Variable]): MongoFilter = {
      variables.sorted.zipWithIndex.foldLeft[MongoFilter](filter) {
        case (filter, (variable, index)) =>
          val varName  = ".variable"  + index

          filter & 
          (JPath(".where" + varName) === variable.serialize)
      }
    }
  }


  private def updateTimeSeries[P <: Predicate](filter: MongoFilter, report: Report[TimeSeriesType, P])
    (implicit tsUpdater: (JPath, TimeSeriesType) => MongoUpdate, pDecomposer: Decomposer[P]): MongoPatches = {
    
    report.groupByOrder.flatMap { 
      case (order, report) =>
        report.groupByPeriod.map { 
          case (period, report) => ((order, period), report)
        }
    }.foldLeft(MongoPatches.empty) {
      case (patches, ((order, period), report)) =>
        val filterOrderPeriod = (filter & {
          ".order"  === order.serialize &
          ".period" === period.serialize
        })

        report.observationCounts.foldLeft(patches) { 
          case (patches, (observation, count)) =>
            val filterWhereClause = filterOrderPeriod.whereVariablesEqual(observation)
            val timeSeriesUpdate = tsUpdater(".count", count)

            patches + (filterWhereClause -> timeSeriesUpdate)
        }
    }
  }

  private def internalIntersectSeries[P <: Predicate](
      col: MongoCollection, token: Token, path: Path, variableDescriptors: List[VariableDescriptor], 
      periodicity: Periodicity, start : Option[DateTime], end : Option[DateTime]): Future[IntersectionResult[TimeSeriesType]] = { 
    val variables = variableDescriptors.map(_.variable)

    val histograms = Future(variableDescriptors.map { 
      case VariableDescriptor(variable, maxResults, SortOrder.Ascending) =>
        getHistogramBottom(token, path, variable, maxResults).map(_.toMap)

      case VariableDescriptor(variable, maxResults, SortOrder.Descending) =>
        getHistogramTop(token, path, variable, maxResults).map(_.toMap)
    }: _*)

    histograms.flatMap { hist => 
      implicit def ordering: scala.math.Ordering[List[JValue]] = new scala.math.Ordering[List[JValue]] {
        override def compare(l1: List[JValue], l2: List[JValue]) = {
          val valueOrder = (l1 zip l2).zipWithIndex.foldLeft(0) {
            case (0, ((v1, v2), i)) => 
              val m = hist(i)  
              variableDescriptors(i).sortOrder match {
                case SortOrder.Ascending  => 
                  -(m(v1) compare m(v2))

                case SortOrder.Descending => 
                  m(v1) compare m(v2)
              }
             
            case (x, _) => x
          }

          if (valueOrder == 0) ListJValueOrdering.compare(l1, l2) else valueOrder
        }
      }

      val filterTokenAndPath = forTokenAndPath(token, path)

      val aggregator = implicitly[Aggregator[TimeSeriesType]]

      database {
        select(".count", ".where").from(col).where {
          (filterTokenAndPath &
          JPath(".period.periodicity") === periodicity.serialize &
          MongoFilterBuilder(JPath(".period.start"))        >= MongoPrimitiveOption(start.map(_.serialize)) &
          MongoFilterBuilder(JPath(".period.start"))        <  MongoPrimitiveOption(end.map(_.serialize)) &
          JPath(".order") === variableDescriptors.length).
          whereVariablesExist(variableDescriptors.map(_.variable))
        }
      } map { results =>
        results.foldLeft(SortedMap.empty[List[JValue], TimeSeriesType]) { 
          case (m, result) =>
            // generate the key for the count in the results
            val values: List[JValue] = variableDescriptors.sortBy(_.variable).zipWithIndex.map { 
              case (vd, i) => (
                variables.indexOf(result.get(JPath(".where.variable" + i)).deserialize[Variable]), 
                result.get(JPath(".where.predicate" + i))
              )
            }.sortBy(_._1).map(_._2).toList

            // ensure that all the variables are within the set of values selected by
            // the histogram that is used for sorting.
            if (values.zipWithIndex.forall { case (v, i) => hist(i).isDefinedAt(v) }) {
              val count = (result \ "count").deserialize[TimeSeriesType]
              m + (values -> (m.getOrElse(values, TimeSeries.empty[CountType]) + count))
            } else m
        }
      }
    }
  }

  /** Retrieves a histogram of the values a variable acquires over its lifetime.
   */
  private def getHistogramInternal(token: Token, path: Path, variable: Variable): Future[Map[JValue, CountType]] = {
    getVariableLength(token, path, variable).flatMap { 
      case 0 =>
        (extractValues(forTokenAndPath(token, path) & forVariable(variable), varValueC) { 
          (jvalue, count) => (jvalue.deserialize[HasValue].value, count)
        }).map(_.toMap)

      case length =>
        Future((0 until length).map { index =>
          getHistogramInternal(token, path, Variable(variable.name \ JPathIndex(index)))
        }: _*).map { results =>
          results.foldLeft(Map.empty[JValue, CountType]) {
            case (all, cur) => MapMonoid[JValue, CountType].append(all, cur)
          }
        }
    }    
  }

  /*private def expandPredicate[P <: Predicate](token: Token, path: Path, variable: Variable, predicate: P): Future[List[(Variable, P)]] = {    
    getVariableLength(token, path, variable).map { 
      case 0 => (variable, predicate) :: Nil

      case length =>
        (0 until length).toList.map { index =>
          (Variable(variable.name \ JPathIndex(index)), predicate)
        }
    }
  }*/

  private def internalSearchSeries[P <: Predicate](col: MongoCollection, token: Token, path: Path, periodicity: Periodicity, observation: Observation[P],
    start : Option[DateTime] = None, end : Option[DateTime] = None)(implicit decomposer: Decomposer[P]): Future[TimeSeriesType] = {
    val filterTokenAndPath = forTokenAndPath(token, path)

    database {
      select(".count").from(col).where {
        (filterTokenAndPath &
        JPath(".period.periodicity") === periodicity.serialize &
        MongoFilterBuilder(JPath(".period.start"))      >=  MongoPrimitiveOption(start.map(_.serialize)) &
        MongoFilterBuilder(JPath(".period.start"))       <  MongoPrimitiveOption(end.map(_.serialize)) &
        JPath(".order") === observation.size).whereVariablesEqual(observation)
      } 
    }.map { results =>
      results.map { result =>
        ((result \ "count").deserialize[TimeSeriesType])

      }.foldLeft[TimeSeriesType](TimeSeries.empty) { _ + _ }.fillGaps
    }
  }

  private def updateCount[P <: Predicate](filter: MongoFilter, report: Report[CountType, P])
    (implicit cUpdater: (JPath, CountType) => MongoUpdate, pDecomposer: Decomposer[P]): MongoPatches = {
    report.groupByOrder.foldLeft(MongoPatches.empty) { (patches, tuple) =>
      val (order, report) = tuple

      val filterOrder = (filter & {
        ".order"  === order.serialize
      })

      report.observationCounts.foldLeft(patches) {
        case (patches, (observation, count)) =>
          val filterWhereClause = filterOrder.whereVariablesEqual(observation)
          val countUpdate = cUpdater(".count", count)

          patches + (filterWhereClause -> countUpdate)
      }
    }
  }


  private def extractValues[T](filter: MongoFilter, collection: MongoCollection)(extractor: (JValue, CountType) => T): Future[List[T]] = {
    database {
      selectOne(".values").from(collection).where(filter)
    } map { 
      case None => Nil

      case Some(result) =>
        (result \ "values").children.collect {
          case JField(name, count) =>
            val jvalue = JsonParser.parse(MongoEscaper.decode(name))

            extractor(jvalue, count.deserialize[CountType])
        }
    }
  }

  private def forTokenAndPath(token: Token, path: Path): MongoFilter = {
    (".accountTokenId" === token.accountTokenId) &
    (".path"           === path.toString)
  }

  private def forVariable(variable: Variable): MongoFilter = {
     ".variable" === variable.serialize
  }
}

object AggregationEngine extends FutureDeliveryStrategySequential {
  private val CollectionIndices = Map(
    "variable_series" -> List(
      "path",
      "accountTokenId",
      "period",
      "order",
      "where.variable0",
      "where.variable1",
      "where.variable2",
      "where.variable3",
      "where.variable4",
      "where.variable5",
      "where.variable6",
      "where.variable7",
      "where.variable8",
      "where.variable9",
      "where.predicate0",
      "where.predicate1",
      "where.predicate2",
      "where.predicate3",
      "where.predicate4",
      "where.predicate5",
      "where.predicate6",
      "where.predicate7",
      "where.predicate8",
      "where.predicate9"
    ),
    "variable_value_series" -> List(
      "path",
      "accountTokenId",
      "period.start",
      "period.end",
      "period.periodicity",
      "order",
      "where.variable0",
      "where.variable1",
      "where.variable2",
      "where.variable3",
      "where.variable4",
      "where.variable5",
      "where.variable6",
      "where.variable7",
      "where.variable8",
      "where.variable9",
      "where.predicate0",
      "where.predicate1",
      "where.predicate2",
      "where.predicate3",
      "where.predicate4",
      "where.predicate5",
      "where.predicate6",
      "where.predicate7",
      "where.predicate8",
      "where.predicate9"
    ),
    "variable_values" -> List(
      "path",
      "accountTokenId",
      "variable"
    ),
    "variable_children" -> List(
      "path",
      "accountTokenId",
      "variable"
    ),
    "path_children" -> List(
      "path",
      "accountTokenId"
    )
  )

  private def createIndices(database: MongoDatabase): Future[Unit] = {
    (CollectionIndices.foldLeft(Future.lift(())) {
      case (future, (collection, indices)) =>
        future.zip[JNothing.type](database[JNothing.type] {
          ensureIndex(collection + "_index").on(indices.map(j => JPath(j)): _*).in(collection)
        }).toUnit
    })
  }

  def apply(config: ConfigMap, logger: Logger, database: MongoDatabase): Future[AggregationEngine] = {
    createIndices(database).map(_ => new AggregationEngine(config, logger, database))
  }
}
