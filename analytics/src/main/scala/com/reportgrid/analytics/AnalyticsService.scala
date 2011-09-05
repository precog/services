package com.reportgrid.analytics
import  external._

import blueeyes._
import blueeyes.concurrent.Future
import blueeyes.core.data.{BijectionsChunkJson, BijectionsChunkString}
import blueeyes.core.http._
import blueeyes.core.http.MimeTypes.{application, json}
import blueeyes.core.service._
import blueeyes.json.JsonAST._
import blueeyes.json.JsonDSL._
import blueeyes.json.{JPath, JsonParser, JPathField}
import blueeyes.json.xschema._
import blueeyes.json.xschema.DefaultOrderings._
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.json.xschema.JodaSerializationImplicits._
import blueeyes.persistence.mongo._
import blueeyes.persistence.cache.{Stage, ExpirationPolicy, CacheSettings}
import blueeyes.util.{Clock, ClockSystem, PartialFunctionCombinators}
import HttpStatusCodes.{BadRequest, Unauthorized, Forbidden}

import net.lag.configgy.{Configgy, ConfigMap}

import org.joda.time.Instant
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

import java.net.URL
import java.util.concurrent.TimeUnit

import com.reportgrid.analytics.AggregatorImplicits._
import com.reportgrid.blueeyes.ReportGridInstrumentation
import com.reportgrid.api.ReportGridTrackingClient
import scala.collection.immutable.SortedMap
import scala.collection.immutable.IndexedSeq

import scalaz.Semigroup
import scalaz.Scalaz._

case class AnalyticsState(aggregationEngine: AggregationEngine, tokenManager: TokenManager, clock: Clock, auditClient: ReportGridTrackingClient[JValue], yggdrasil: Yggdrasil[JValue], jessup: Jessup)

trait AnalyticsService extends BlueEyesServiceBuilder with BijectionsChunkJson with BijectionsChunkString with ReportGridInstrumentation {
  import AggregationEngine._
  import AnalyticsService._
  import AnalyticsServiceSerialization._

  def mongoFactory(configMap: ConfigMap): Mongo

  def auditClient(configMap: ConfigMap): ReportGridTrackingClient[JValue] 

  def yggdrasil(configMap: ConfigMap): Yggdrasil[JValue]

  def jessup(configMap: ConfigMap): Jessup

  val analyticsService = service("analytics", "1.0") {
    logging { logger =>
      healthMonitor { monitor => context =>
        startup {
          import context._

          val mongoConfig = config.configMap("mongo")

          val mongo = mongoFactory(mongoConfig)

          val database = mongo.database(mongoConfig.getString("database").getOrElse("analytics"))

          val tokensCollection = mongoConfig.getString("tokensCollection").getOrElse("tokens")

          for {
            tokenManager      <- TokenManager(database, tokensCollection)
            aggregationEngine <- AggregationEngine(config, logger, database)
          } yield {
            AnalyticsState(
              aggregationEngine, tokenManager, ClockSystem.realtimeClock, 
              auditClient(config.configMap("audit")),
              yggdrasil(config.configMap("yggdrasil")),
              jessup(config.configMap("jessup")))
          }
        } ->
        request { (state: AnalyticsState) =>
          import state.{aggregationEngine, tokenManager}

          def tokenOf(request: HttpRequest[_]): Future[Token] = {
            request.parameters.get('tokenId) match {
              case None =>
                throw HttpException(BadRequest, "A tokenId query parameter is required to access this URL")

              case Some(tokenId) =>
                tokenManager.lookup(tokenId).map { token =>
                  token match {
                    case None =>
                      throw HttpException(BadRequest, "The specified token does not exist")

                    case Some(token) =>
                      if (token.expired) throw HttpException(Unauthorized, "The specified token has expired")

                      token
                  }
                }
            }
          }

          def withTokenAndPath[T](request: HttpRequest[_])(f: (Token, Path) => Future[T]): Future[T] = {
            tokenOf(request) flatMap { token => f(token, fullPathOf(token, request)) }
          }

          val audit = auditor[JValue, JValue](state.auditClient, state.clock, tokenOf)

          def aggregate(request: HttpRequest[JValue], tagExtractors: List[Tag.TagExtractor]) = {
            val count: Int = request.parameters.get('count).map(_.toInt).getOrElse(1)

            withTokenAndPath(request) { (token, path) => 
              request.content.foreach { 
                case obj @ JObject(fields) => for (JField(eventName, event: JObject) <- fields) {
                  val (tagResults, remainder) = Tag.extractTags(tagExtractors, event)
                  for (tags <- getTags(tagResults)) {
                    aggregationEngine.aggregate(token, path, eventName, tags, remainder, count)
                  }
                }

                case err => 
                  throw new HttpException(BadRequest, "Expected a JSON object but got " + pretty(render(err)))
              }

              Future.sync(HttpResponse[JValue](content = None))
            }
          }


          jsonp {
            /* The virtual file system, which is used for storing data,
             * retrieving data, and querying for metadata.
             */
            path("""/vfs/store/(?:(?<prefixPath>(?:[^\n.](?:[^\n/]|/[^\n\.])+)/?)?)""") { 
              $ {
                audit("store") {
                  state.yggdrasil {
                    post { request: HttpRequest[JValue] =>
                      val tagExtractors = Tag.timeTagExtractor(timeSeriesEncoding, state.clock.instant(), false) ::
                                          Tag.locationTagExtractor(state.jessup(request.remoteHost))      :: Nil

                      aggregate(request, tagExtractors)
                    }
                  }
                }
              }
            } ~
            path("""/vfs/(?:(?<prefixPath>(?:[^\n.](?:[^\n/]|/[^\n\.])+)/?)?)""") { 
              $ {
                /* Post data to the virtual file system.
                 */
                audit("track") {
                  state.yggdrasil {
                    post { request: HttpRequest[JValue] =>
                      val tagExtractors = Tag.timeTagExtractor(timeSeriesEncoding, state.clock.instant(), true) ::
                                          Tag.locationTagExtractor(state.jessup(request.remoteHost))      :: Nil

                      aggregate(request, tagExtractors)
                    }
                  }
                } ~
                audit("explore paths") {
                  get { request: HttpRequest[JValue] =>
                    withTokenAndPath(request) { (token, path) => 
                      if (token.permissions.explore) {
                        aggregationEngine.getPathChildren(token, path).map(_.serialize.ok)
                      } else {
                        throw new HttpException(Unauthorized, "The specified token does not permit exploration of the virtual filesystem.")
                      }
                    }
                  }
                }
              } ~
              path("""(?<variable>\.[^\n/]+)""") {
                $ {
                  audit("explore variables") {
                    get { request: HttpRequest[JValue] =>
                      val variable = variableOf(request)

                      withTokenAndPath(request) { (token, path) => 
                        if (token.permissions.explore) {
                          aggregationEngine.getVariableChildren(token, path, variable).map(_.map(_.child).serialize.ok)
                        } else {
                          throw new HttpException(Unauthorized, "The specified token does not permit exploration of the virtual filesystem.")
                        }
                      }
                    }
                  }
                } ~
                path("/") {
                  path("statistics") {
                    audit("variable statistics") {
                      get { request: HttpRequest[JValue] =>
                        val variable = variableOf(request)

                        withTokenAndPath(request) { (token, path) => 
                          aggregationEngine.getVariableStatistics(token, path, variable).map(_.serialize.ok)
                        }
                      }
                    }
                  } ~
                  path("count") {
                    audit("variable occurrence count") {
                      post { request: HttpRequest[JValue] =>
                        val variable = variableOf(request)

                        withTokenAndPath(request) { (token, path) => 
                          aggregationEngine.getVariableCount(token, path, variable, tagTerms(request.parameters, request.content, None)).map(_.serialize.ok)
                        }
                      }
                    }
                  } ~
                  path("series/") {
                    audit("variable occurrence series") {
                      path('periodicity) {
                        $ {
                          queryVariableSeries(tokenOf, _.count, aggregationEngine)
                        } ~ 
                        path("/") {
                          path("means") {
                            queryVariableSeries(tokenOf, _.mean, aggregationEngine)
                          } ~ 
                          path("standardDeviations") {
                            queryVariableSeries(tokenOf, _.standardDeviation, aggregationEngine) 
                          } 
                        }
                      }
                    }
                  } ~
                  path("histogram/") {
                    $ {
                      audit("variable histogram") {
                        get { request: HttpRequest[JValue] =>
                          val variable = variableOf(request)

                          withTokenAndPath(request) { (token, path) => 
                            aggregationEngine.getHistogram(token, path, variable).map(renderHistogram).map(_.ok)
                          }
                        }
                      }
                    } ~
                    path("top/'limit") {
                      audit("variable histogram top") {
                        get { request: HttpRequest[JValue] =>
                          val variable = variableOf(request)
                          val limit    = request.parameters('limit).toInt

                          withTokenAndPath(request) { (token, path) => 
                            aggregationEngine.getHistogramTop(token, path, variable, limit).map(renderHistogram).map(_.ok)
                          }
                        }
                      }
                    } ~
                    path("bottom/'limit") {
                      audit("variable histogram bottom") {
                        get { request: HttpRequest[JValue] =>
                          val variable = variableOf(request)
                          val limit    = request.parameters('limit).toInt
                          
                          withTokenAndPath(request) { (token, path) => 
                            aggregationEngine.getHistogramBottom(token, path, variable, limit).map(renderHistogram).map(_.ok)
                          }
                        }
                      }
                    }
                  } ~
                  path("length") {
                    audit("count of variable values") {
                      get { request: HttpRequest[JValue] =>
                        val variable = variableOf(request)

                        withTokenAndPath(request) { (token, path) => 
                          aggregationEngine.getVariableLength(token, path, variable).map(_.serialize.ok)
                        }
                      }
                    }
                  } ~
                  path("values/") {
                    $ {
                      audit("list of variable values") {
                        get { request: HttpRequest[JValue] =>
                          val variable = variableOf(request)

                          withTokenAndPath(request) { (token, path) => 
                            if (token.permissions.explore) {
                              aggregationEngine.getValues(token, path, variable).map(_.toList.serialize.ok)
                            } else {
                              throw new HttpException(Unauthorized, "The specified token does not permit exploration of the virtual filesystem.")
                            }
                          }
                        }
                      }
                    } ~
                    path("top/'limit") {
                      audit("list of top variable values") {
                        get { request: HttpRequest[JValue] =>
                          val variable = variableOf(request)
                          val limit    = request.parameters('limit).toInt

                          withTokenAndPath(request) { (token, path) => 
                            aggregationEngine.getValuesTop(token, path, variable, limit).map(_.serialize.ok)
                          }
                        }
                      }
                    } ~
                    path("bottom/'limit") {
                      audit("list of bottom variable values") {
                        get { request: HttpRequest[JValue] =>
                          val variable = variableOf(request)
                          val limit    = request.parameters('limit).toInt

                          withTokenAndPath(request) { (token, path) => 
                            aggregationEngine.getValuesBottom(token, path, variable, limit).map(_.serialize.ok)
                          }
                        }
                      }
                    } ~
                    path('value) {
                      $ {
                        audit("explore a variable value") {
                          get { request: HttpRequest[JValue] =>
                            // return a list of valid subpaths
                            Future.sync(JArray(JString("count") :: JString("series/") :: Nil).ok[JValue])
                          }
                        }
                      } ~
                      path("/") {
                        path("count") {
                          audit("count occurrences of a variable value") {
                            post { request: HttpRequest[JValue] =>
                              val observation = JointObservation(HasValue(variableOf(request), valueOf(request)))

                              withTokenAndPath(request) { (token, path) => 
                                aggregationEngine.getObservationCount(token, path, observation, tagTerms(request.parameters, request.content, None)) map (_.serialize.ok)
                              }
                            }
                          }
                        } ~
                        path("series/") {
                          audit("variable value series") {
                            get { request: HttpRequest[JValue] =>
                              // simply return the names of valid periodicities that can be used for series queries
                              Future.sync(JArray(Periodicity.Default.map(p => JString(p.name))).ok[JValue])
                            } ~
                            path('periodicity) {
                              post { request: HttpRequest[JValue] =>
                                val periodicity = periodicityOf(request)
                                val observation = JointObservation(HasValue(variableOf(request), valueOf(request)))
                                val terms = tagTerms(request.parameters, request.content, Some(periodicity))

                                withTokenAndPath(request) { (token, path) => 
                                  aggregationEngine.getObservationSeries(token, path, observation, terms)
                                  .map(groupTimeSeries(periodicity, seriesGrouping(request)))
                                  .map(_.serialize.ok)
                                }
                              } 
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            } ~
            path("/search") {
              audit("count or series query") {
                post { request: HttpRequest[JValue] =>
                  tokenOf(request).flatMap { token =>
                    val content = request.content.getOrElse {
                      throw new HttpException(BadRequest, """request body was empty. "select", "from", and "where" fields must be specified.""")
                    }
                      
                    val select = (content \ "select").deserialize[String].toLowerCase
                    val from = token.path / ((content \ "from").deserialize[String])
                    val observation = JointObservation((content \ "where").deserialize[Set[HasValue]])

                    Selection(select) match {
                      case Count => 
                        val terms = tagTerms(request.parameters, Some(content), None)
                        aggregationEngine.getObservationCount(token, from, observation, terms) map (_.serialize.ok)

                      case Series(periodicity) => 
                        val terms = tagTerms(request.parameters, Some(content), Some(periodicity))
                        aggregationEngine.getObservationSeries(token, from, observation, terms)
                        .map(groupTimeSeries(periodicity, seriesGrouping(request)))
                        .map(_.serialize.ok)

                      case Related => 
                        val finiteSpan = timeSpan(request.parameters, content).getOrElse {
                          throw new HttpException(BadRequest, "Start and end dates must be specified to query for values related to an observation.")
                        }

                        aggregationEngine.findRelatedInfiniteValues(token, from, observation, finiteSpan) map (_.serialize.ok)
                    }
                  }
                }
              }
            } ~
            path("/intersect") {
              audit("intersection query") {
                post { request: HttpRequest[JValue] => 
                  tokenOf(request).flatMap { token => 
                    import VariableDescriptor._
                    val content = request.content.getOrElse {
                      throw new HttpException(BadRequest, """request body was empty. "select", "from", and "where" fields must be specified.""")
                    }
                      
                    val select = Selection((content \ "select").deserialize[String].toLowerCase)
                    val from: Path = token.path + "/" + (content \ "from").deserialize[String]
                    val where = (content \ "where").deserialize[List[VariableDescriptor]]

                    select match {
                      case Count => 
                        aggregationEngine.getIntersectionCount(token, from, where, tagTerms(request.parameters, Some(content), None))
                        .map(serializeIntersectionResult[CountType]).map(_.ok)

                      case Series(periodicity) =>
                        aggregationEngine.getIntersectionSeries(token, from, where, tagTerms(request.parameters, Some(content), Some(periodicity)))
                        //.map(_.map((groupTimeSeries(grouping)(_: TimeSeriesType).fold(_.serialize, _.serialize)).second)(collection.breakOut))
                        .map(serializeIntersectionResult[ResultSet[JObject, CountType]]).map(_.ok)
                    }
                  }
                } 
              }
            } ~ 
            path("/tokens/") {
              audit("token") {
                get { request: HttpRequest[JValue] =>
                  tokenOf(request) flatMap { token =>
                    tokenManager.listDescendants(token) map { descendants =>
                      descendants.map { descendantToken =>
                        descendantToken.tokenId.serialize
                      }
                    } map { 
                      JArray(_).ok[JValue]
                    }
                  }
                } ~
                post { request: HttpRequest[JValue] =>
                  tokenOf(request).flatMap { token =>
                    val content: JValue = request.content.getOrElse {
                      throw new HttpException(BadRequest, "New token must be contained in POST content")
                    }

                    val path        = (content \ "path").deserialize[Option[String]].getOrElse("/")
                    val permissions = (content \ "permissions").deserialize[Option[Permissions]].getOrElse(token.permissions)
                    val expires     = (content \ "expires").deserialize[Option[DateTime]].getOrElse(token.expires)
                    val limits      = (content \ "limits").deserialize[Option[Limits]].getOrElse(token.limits)

                    tokenManager.issueNew(token, path, permissions, expires, limits).map { newToken =>
                      HttpResponse[JValue](content = Some(newToken.tokenId.serialize))
                    }
                  }
                } ~
                path('descendantTokenId) {
                  get { request: HttpRequest[JValue] =>
                    tokenOf(request).flatMap { token =>
                      if (token.tokenId == request.parameters('descendantTokenId)) {
                        token.parentTokenId.map { parTokenId =>
                          tokenManager.lookup(parTokenId).map { parent => 
                            val sanitized = parent.map(token.relativeTo).map(_.copy(parentTokenId = None, accountTokenId = ""))
                            HttpResponse[JValue](content = sanitized.map(_.serialize))
                          }
                        } getOrElse {
                          Future.sync(HttpResponse[JValue](status = Forbidden))
                        }
                      } else {
                        tokenManager.getDescendant(token, request.parameters('descendantTokenId)).map { 
                          _.map { _.relativeTo(token).copy(accountTokenId = "").serialize }
                        } map { descendantToken =>
                          HttpResponse[JValue](content = descendantToken)
                        }
                      }
                    }
                  } ~
                  delete { (request: HttpRequest[JValue]) =>
                    tokenOf(request).flatMap { token =>
                      tokenManager.deleteDescendant(token, request.parameters('descendantTokenId)).map { _ =>
                        HttpResponse[JValue](content = None)
                      }
                    }
                  }
                }
              }
            }
          } 
        } ->
        shutdown { state =>
          state.aggregationEngine.stop
        }
      }
    }
  }
}

object AnalyticsService extends HttpRequestHandlerCombinators with PartialFunctionCombinators {
  import AnalyticsServiceSerialization._
  import AggregationEngine._

  def groupTimeSeries[V: Semigroup](original: Periodicity, grouping: Option[(Periodicity, Set[Int])]) = (resultSet: ResultSet[JObject, V]) => {
    grouping match {
      case Some((grouping, groups)) => 
        resultSet.foldLeft(SortedMap.empty[JObject, V](JObjectOrdering)) {
          case (acc, (obj, v)) => 
            val key = JObject(
              obj.fields.flatMap {
                case JField("timestamp", instant) => 
                  val index = original.indexOf(instant.deserialize[Instant], grouping).getOrElse {
                    sys.error("Cannot group time series of periodicity " + original + " by " + grouping)
                  }
                  
                  (groups.isEmpty || groups.contains(index)) option JField(grouping.name, index)
                  
                case field => Some(field)
              })

            acc + (key -> acc.get(key).map(_ |+| v).getOrElse(v))
        }.toSeq

      case None => resultSet
    }
  }

  def fullPathOf(token: Token, request: HttpRequest[_]): Path = {
    val prefixPath = request.parameters.get('prefixPath) match {
      case None | Some(null) => ""
      case Some(s) => s
    }

    token.path + "/" + prefixPath
  }

  def variableOf(request: HttpRequest[_]): Variable = Variable(JPath(request.parameters.get('variable).getOrElse("")))

  def valueOf(request: HttpRequest[_]): JValue = {
    import java.net.URLDecoder

    val value = request.parameters('value) // URLDecoder.decode(request.parameters('value), "UTF-8")

    try JsonParser.parse(value)
    catch {
      case _ => JString(value)
    }
  }

  def periodicityOf(request: HttpRequest[_]): Periodicity = {
    try Periodicity(request.parameters('periodicity))
    catch {
      case _ => throw HttpException(BadRequest, "Unknown or unspecified periodicity")
    }
  }

  def seriesGrouping(request: HttpRequest[_]): Option[(Periodicity, Set[Int])] = {
    request.parameters.get('groupBy).flatMap(Periodicity.byName).map { grouping =>
      (grouping, request.parameters.get('groups).toSet.flatMap((_:String).split(",").map(_.toInt)))
    }
  }

  def grouping(content: JValue) = (content \ "groupBy") match {
    case JNothing | JNull => None
    case jvalue   => Periodicity.byName(jvalue)
  }

  val timeStartKey = Symbol("@start")
  val timeEndKey   = Symbol("@end")
  def timeSpan(parameters: Map[Symbol, String], content: JValue): Option[TimeSpan.Finite] = {
    val start = parameters.get(timeStartKey).map(new DateTime(_: String, DateTimeZone.UTC).toInstant).orElse {
      (content \ timeStartKey.name) match {
        case JNothing | JNull => None
        case jvalue   => 
          jvalue.validated[Instant].toOption
      }
    }

    val end = parameters.get(timeEndKey).map(new DateTime(_: String, DateTimeZone.UTC).toInstant).orElse {
      (content \ timeEndKey.name) match {
        case JNothing | JNull => None
        case jvalue  => 
          jvalue.validated[Instant].toOption
      }
    }

    (start <**> end)(TimeSpan(_, _))
  }

  def timeSpanTerm(parameters: Map[Symbol, String], content: JValue, p: Option[Periodicity]): Option[TagTerm] = {
    val periodicity = (content \ "periodicity") match {
      case JNothing | JNull | JBool(true) => p.orElse(Some(Periodicity.Eternity))
      //only if it is explicitly stated that no timestamp was used on submission do we exclude a time term
      case JBool(false) => None 
      case JString("none") => None
      case jvalue => p.orElse(Some(jvalue.deserialize[Periodicity]))
    }

    periodicity flatMap {
      case Periodicity.Eternity => 
        timeSpan(parameters, content).map(SpanTerm(timeSeriesEncoding, _)).orElse(Some(SpanTerm(timeSeriesEncoding, TimeSpan.Eternity)))

      case other => 
        timeSpan(parameters, content).map(IntervalTerm(timeSeriesEncoding, other, _)).orElse {
          throw new HttpException(BadRequest, "A periodicity was specified, but no finite time span could be determined.")
        }
    }
  }

  val locationKey = Symbol("@location")
  def locationTerm(parameters: Map[Symbol, String], content: JValue): Option[TagTerm] = {
    parameters.get(locationKey) map (p => Hierarchy.AnonLocation(Path(p))) orElse {
      (content \ locationKey.name) match {
        case JNothing | JNull => None
        case jvalue => Some(jvalue.deserialize[Hierarchy.Location])
      }
    } map (HierarchyLocationTerm("location", _))
  }

  def tagTerms(parameters: Map[Symbol, String], requestContent: Option[JValue], p: Option[Periodicity]): List[TagTerm] = {
    requestContent.toList.flatMap { content => 
      List(timeSpanTerm(parameters, content, p), locationTerm(parameters, content)).flatten 
    } 
  }

  def queryVariableSeries[T: Decomposer : AbelianGroup](tokenOf: HttpRequest[_] => Future[Token], f: ValueStats => T, aggregationEngine: AggregationEngine) = {
    post { request: HttpRequest[JValue] =>
      tokenOf(request).flatMap { token =>
        val path        = fullPathOf(token, request)
        val variable    = variableOf(request)
        val periodicity = periodicityOf(request)

        aggregationEngine.getVariableSeries(token, path, variable, tagTerms(request.parameters, request.content, Some(periodicity))) 
        .map(groupTimeSeries(periodicity, seriesGrouping(request)))
        .map(_.map(f.second).serialize.ok)
      }
    } 
  }

  def getTags(result: Tag.ExtractionResult) = result match {
    case Tag.Tags(tags) => tags
    case Tag.Skipped => Future.sync(Nil)
    case Tag.Errors(errors) =>
      val errmsg = "Errors occurred extracting tag information: " + errors.map(_.toString).mkString("; ")
      throw new HttpException(BadRequest, errmsg)
  }

  def renderHistogram(histogram: Traversable[(JValue, Long)]): JObject = {
    histogram.foldLeft(JObject.empty) {
      case (content, (value, count)) =>
        val name = JPathField(renderNormalized(value))

        content.set(name, JInt(count)) --> classOf[JObject]
    }
  }
}

object AnalyticsServiceSerialization extends AnalyticsSerialization {
  import AggregationEngine._

  // Decomposer is invariant, which means that this can't be reasonably implemented
  // as a Decomposer and used both for intersection results and grouped intersection
  // results.
  def serializeIntersectionResult[T: Decomposer](result: Seq[(JArray, T)]) = {
    result.foldLeft[JValue](JObject(Nil)) {
      case (result, (key, value)) => 
        result.set(JPath(key.elements.map(v => JPathField(renderNormalized(v)))), value.serialize)
    }
  }

  implicit def SortedMapDecomposer[K: Decomposer, V: Decomposer]: Decomposer[SortedMap[K, V]] = new Decomposer[SortedMap[K, V]] {
    override def decompose(m: SortedMap[K, V]): JValue = JArray(
      m.map(t => JArray(List(t._1.serialize, t._2.serialize))).toList
    )
  }

  implicit def TimeSeriesDecomposer[T: Decomposer]: Decomposer[TimeSeries[T]] = new Decomposer[TimeSeries[T]] {
    override def decompose(t: TimeSeries[T]) = JObject(
      JField("type", JString("timeseries")) ::
      JField("periodicity", t.periodicity.serialize) ::
      JField("data", t.series.serialize) ::
      Nil
    )
  }

  implicit def DeltaSetDecomposer[A: Decomposer, D: Decomposer, V : Decomposer : AbelianGroup]: Decomposer[DeltaSet[A, D, V]] = new Decomposer[DeltaSet[A, D, V]] {
    def decompose(value: DeltaSet[A, D, V]): JValue = JObject(
      JField("type", JString("deltas")) ::
      JField("zero", value.zero.serialize) ::
      JField("data", value.data.serialize) ::
      Nil
    )
  }

  implicit val StatisticsDecomposer: Decomposer[Statistics] = new Decomposer[Statistics] {
    def decompose(v: Statistics): JValue = JObject(
      JField("n",  v.n.serialize) ::
      JField("min",  v.min.serialize) ::
      JField("max",  v.max.serialize) ::
      JField("mean", v.mean.serialize) ::
      JField("variance", v.variance.serialize) ::
      JField("standardDeviation", v.standardDeviation.serialize) ::
      Nil
    )
  }

  implicit val VariableValueDecomposer: Decomposer[(Variable, HasValue)] = new Decomposer[(Variable, HasValue)] {
    def decompose(v: (Variable, HasValue)): JValue = JObject(
      JField("variable", v._1.serialize) :: 
      JField("value", v._2.serialize) ::
      Nil
    )
  }

//  implicit val HasValueExtractor: Extractor[HasValue] = new Extractor[HasValue] {
//    def extract(v: JValue): HasValue = {
//      (v \ "where").children.collect {
//        case JField(name, value) => HasValue(Variable(JPath(name)), value)
//      }.toSet
//    }
//  }
}
