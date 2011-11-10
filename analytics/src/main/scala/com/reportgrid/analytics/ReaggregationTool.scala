package com.reportgrid.analytics

import blueeyes._
import blueeyes.health._
import blueeyes.json._
import blueeyes.json.JsonAST._
import blueeyes.json.JsonDSL._
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.persistence.mongo._
import blueeyes.concurrent.Future

import net.lag.configgy.{Config, ConfigMap}
import net.lag.logging.Logger

import scalaz._
import Scalaz._

object AggregationEnvironment {
  def apply(file: java.io.File): Future[AggregationEnvironment] = {
    apply((new Config()) ->- (_.loadFile(file.getPath)))
  }

  def apply(config: ConfigMap): Future[AggregationEnvironment] = {
    val eventsdbConfig = config.configMap("services.analytics.v1.eventsdb")
    val eventsMongo = new RealMongo(eventsdbConfig)
    val eventsdb = eventsMongo.database(eventsdbConfig("database"))

    val indexdbConfig = config.configMap("services.analytics.v1.indexdb")
    val indexMongo = new RealMongo(indexdbConfig)
    val indexdb = indexMongo.database(indexdbConfig("database"))

    val tokensCollection = config.getString("tokens.collection", "tokens")
    val deletedTokensCollection = config.getString("tokens.deleted", "deleted_tokens")

    TokenManager(indexdb, tokensCollection, deletedTokensCollection) map { 
      AggregationEnvironment(AggregationEngine.forConsole(config, Logger.get, eventsdb, indexdb, HealthMonitor.Noop), _)
    }
  }
}

case class AggregationEnvironment(engine: AggregationEngine, tokenManager: TokenManager)

object ReaggregationTool {
  def main(argv: Array[String]) {
    val argMap = parseOptions(argv.toList, Map.empty)

    val analyticsConfig = argMap.get("--configFile").getOrElse {
      throw new RuntimeException("Analytics service configuration file must be specified with the --configFile option.")
    }

    val pauseLength = argMap.get("--pause").map(_.toInt).getOrElse(30)
    val batchSize   = argMap.get("--batchSize").map(_.toInt).getOrElse(50)
    val maxRecords  = argMap.get("--maxRecords").map(_.toLong).getOrElse(5000L)

    AggregationEnvironment(new java.io.File(analyticsConfig)).foreach(reprocess(_, pauseLength, batchSize, 0L, maxRecords))
  }

  def parseOptions(opts: List[String], optMap: Map[String, String]): Map[String, String] = opts match {
    case "--configFile" :: filename :: rest => parseOptions(rest, optMap + ("--configFile" -> filename))
    case "--pause"      :: seconds  :: rest => parseOptions(rest, optMap + ("--pause" -> seconds))
    case "--batchSize"  :: records  :: rest => parseOptions(rest, optMap + ("--batchSize" -> records))
    case "--maxRecords" :: records  :: rest => parseOptions(rest, optMap + ("--maxRecords" -> records))
    case x :: rest => 
      println("Unrecognized option: " + x)
      parseOptions(rest, optMap)

    case Nil => optMap
  }

  def reprocess(env: AggregationEnvironment, pause: Int, batchSize: Int, totalRecords: Long, maxRecords: Long) {
    import env.engine._
    eventsdb(selectAll.from(events_collection).where("reprocess" === true & ("unparseable".doesNotExist)).limit(batchSize)) flatMap { results => 
      val reaggregated = results.map { jv => 
        aggregateFromStorage(env.tokenManager, jv --> classOf[JObject]) map { 
          case result @ Success(complexity) => (result, (JPath("reprocess") set (false)))
          case result @ Failure(error)      => (result, (JPath("reprocess") set (false)) |+| (JPath("unparseable") set (true)))
        } flatMap {
          case (result, mongoUpdate) => eventsdb(update(events_collection).set(mongoUpdate).where("_id" === (jv \ "_id"))).map(_ => result)
        }
      }

      Future(reaggregated.toSeq: _*) deliverTo { results => 
        val total = results.size + totalRecords
        val (errors, complexity) = results.foldLeft((List.empty[String], 0L)) {
          case ((errors, total),  Failure(err)) => (errors ++ err.list, total)
          case ((errors, total),  Success(complexity)) => (errors, total + complexity)
        }

        println("Processed " + results.size + " events with a total complexity of " + complexity)
        errors.foreach(println)

        if (total < maxRecords) {
          Thread.sleep(pause * 1000)
          reprocess(env, pause, batchSize, total, maxRecords)
        } else {
          println("Reprocessing reached its max limit for the number of events to reprocess; shutting down after " + total + " events.")
          akka.actor.Actor.registry.shutdownAll()
        }
      } ifCanceled {
        errors => 
          errors.foreach(ex => ex.printStackTrace) 
          println("Errors caused event reprocessing to be terminated after " + totalRecords + " of " + maxRecords + " events.")
          akka.actor.Actor.registry.shutdownAll()
      }
    }
  }
}

// vim: set ts=4 sw=4 et:
