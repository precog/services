package com.reportgrid.analytics
package service

import blueeyes.concurrent.Future
import blueeyes.core.http._
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.core.service._
import blueeyes.json.JsonAST._
import blueeyes.json.xschema._
import blueeyes.json.xschema.DefaultSerialization._
import AnalyticsService._
import AggregationEngine._
import AnalyticsServiceSerialization._
import com.reportgrid.ct.Mult.MDouble._

import scalaz.Scalaz._
import scalaz.Success
import scalaz.Validation

class ExplorePathService[A](aggregationEngine: AggregationEngine) 
extends CustomHttpService[A, (Token, Path) => Future[HttpResponse[JValue]]] {
  val service = (request: HttpRequest[A]) => Success(
    (token: Token, path: Path) => {
      if (token.permissions.explore) {
        aggregationEngine.getPathChildren(token, path).map(_.serialize.ok)
      } else {
        Future.sync(HttpResponse[JValue](Unauthorized, content = Some("The specified token does not permit exploration of the virtual filesystem.")))
      }
    }
  )

  val metadata = None
}

class ExploreVariableService[A](aggregationEngine: AggregationEngine) 
extends CustomHttpService[A, (Token, Path, Variable) => Future[HttpResponse[JValue]]] {
  val service = (request: HttpRequest[A]) => Success(
    (token: Token, path: Path, variable: Variable) => {
      if (token.permissions.explore) {
        aggregationEngine.getVariableChildren(token, path, variable).map(_.map(_.child).serialize.ok)
      } else {
        Future.sync(HttpResponse[JValue](Unauthorized, content = Some("The specified token does not permit exploration of variable children.")))
      }
    }
  )

  val metadata = None
}

class ExploreValuesService[A](aggregationEngine: AggregationEngine) 
extends CustomHttpService[A, (Token, Path, Variable) => Future[HttpResponse[JValue]]] {
  val service = (request: HttpRequest[A]) => Success(
    (token: Token, path: Path, variable: Variable) => {
      if (token.permissions.explore) {
        aggregationEngine.getValues(token, path, variable).map(_.toList.serialize.ok)
      } else {
        Future.sync(HttpResponse[JValue](Unauthorized, content = Some("The specified token does not permit exploration of the virtual filesystem.")))
      }
    }
  )

  val metadata = None
}

class VariableSeriesService[T: Decomposer : AbelianGroup](aggregationEngine: AggregationEngine, f: ValueStats => T) 
extends CustomHttpService[JValue, (Token, Path, Variable) => Future[HttpResponse[JValue]]] {
  val service: HttpRequest[JValue] => Validation[NotServed,(Token, Path, Variable) => Future[HttpResponse[JValue]]]  = (request: HttpRequest[JValue]) => {
    request.parameters.get('periodicity).flatMap(Periodicity.byName)
    .toSuccess(DispatchError(BadRequest, "A periodicity must be specified in order to query for a time series."))
    .map { periodicity =>
      (token: Token, path: Path, variable: Variable) => {
        val terms = List(intervalTerm(periodicity), locationTerm).flatMap(_.apply(request.parameters, request.content))

        aggregationEngine.getVariableSeries(token, path, variable, terms) 
        .map(transformTimeSeries[ValueStats](request, periodicity))
        .map(_.map(f.second).serialize.ok)
      }
    }
  }

  val metadata = None
}

class ValueSeriesService(aggregationEngine: AggregationEngine) 
extends CustomHttpService[JValue, (JValue) => (Token, Path, Variable) => Future[HttpResponse[JValue]]] {
  val service = (request: HttpRequest[JValue]) => {
    request.parameters.get('periodicity).flatMap(Periodicity.byName)
    .toSuccess(DispatchError(BadRequest, "A periodicity must be specified in order to query for a time series."))
    .map { periodicity =>
      (value: JValue) => (token: Token, path: Path, variable: Variable) => {
        val terms = List(intervalTerm(periodicity), locationTerm).flatMap(_.apply(request.parameters, request.content))

        aggregationEngine.getObservationSeries(token, path, JointObservation(HasValue(variable, value)), terms)
        .map(transformTimeSeries(request, periodicity))
        .map(_.serialize.ok)
      }
    }
  }

  val metadata = None
}

class SearchService(aggregationEngine: AggregationEngine)
extends CustomHttpService[JValue, Token => Future[HttpResponse[JValue]]] {
  import Extractor._
  val service = (request: HttpRequest[JValue]) => Success(
    (token: Token) => {
      request.content map { content => 
        val queryComponents = (content \ "select").validated[String].flatMap(s => Selection.parse(s).toSuccess(Invalid("Invalid selection type: " + s))) |@| 
                              (content \ "from").validated[String].map(token.path / _) |@|
                              (content \ "where").validated[Set[HasValue]].map(JointObservation(_))

        val result = queryComponents.apply {
          (select, from, observation) => select match {
            case Count => 
              val terms = List(timeSpanTerm, locationTerm).flatMap(_.apply(request.parameters, Some(content)))
              aggregationEngine.getObservationCount(token, from, observation, terms).map(_.serialize.ok)

            case Series(periodicity) => 
              val terms = List(intervalTerm(periodicity), locationTerm).flatMap(_.apply(request.parameters, Some(content)))
              aggregationEngine.getObservationSeries(token, from, observation, terms)
              .map(transformTimeSeries[CountType](request, periodicity))
              .map(_.serialize.ok)

            case Related => 
              val terms = List(timeSpanTerm, locationTerm).flatMap(_.apply(request.parameters, Some(content)))
              aggregationEngine.findRelatedInfiniteValues(token, from, observation, terms) map (_.toList.serialize.ok)
          }
        }

        result ||| { errors => Future.sync(HttpResponse[JValue](BadRequest, content = Some(errors.message.serialize))) }
      }
    } getOrElse Future.sync {
      HttpResponse[JValue](BadRequest, content = Some("""Request body was empty. The "select", "from", and "where" fields must be specified."""))
    }
  )

  val metadata = None
}

class IntersectionService(aggregationEngine: AggregationEngine)
extends CustomHttpService[JValue, Token => Future[HttpResponse[JValue]]] {
  import Extractor._
  val service = (request: HttpRequest[JValue]) => Success(
    (token: Token) => {
      request.content map { content => 
        val queryComponents = (content \ "select").validated[String].flatMap(s => Selection.parse(s).toSuccess(Invalid("Invalid selection type: " + s))) |@| 
                              (content \ "from").validated[String].map(token.path / _) |@|
                              (content \ "properties").validated[List[VariableDescriptor]]

        val result = queryComponents.apply {
          case (select, from, where) => select match {
            case Count => 
              val terms = List(timeSpanTerm, locationTerm).flatMap(_.apply(request.parameters, Some(content)))
              aggregationEngine.getIntersectionCount(token, from, where, terms)
              .map(serializeIntersectionResult[CountType]).map(_.ok)

            case Series(periodicity) =>
              val terms = List(intervalTerm(periodicity), locationTerm).flatMap(_.apply(request.parameters, Some(content)))
              aggregationEngine.getIntersectionSeries(token, from, where, terms)
              .map(_.map(transformTimeSeries[CountType](request, periodicity).second))
              .map(serializeIntersectionResult[ResultSet[JObject, CountType]]).map(_.ok)
          }
        }

        result ||| { errors => Future.sync(HttpResponse[JValue](BadRequest, content = Some(errors.message.serialize))) }
      } getOrElse Future.sync {
        HttpResponse[JValue](BadRequest, content = Some("""Request body was empty. The "select", "from", and "properties" fields must be specified."""))
      }
    }
  )

  val metadata = None
}


// vim: set ts=4 sw=4 et: