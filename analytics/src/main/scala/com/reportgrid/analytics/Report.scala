package com.reportgrid.analytics

import blueeyes.json.JsonAST._
import blueeyes.json.{JPath, JPathIndex, JPathField}

import com.reportgrid.util.MapUtil._

/** 
 * A report counts observations of a particular type.
 * An observation is a value of type Set[(Variable, HasValue | HasChild)]
 */
case class Report[S <: Predicate, T: AbelianGroup](observationCounts: Map[Observation[S], T]) {
  
  private def aggregator: AbelianGroup[T] = implicitly[AbelianGroup[T]]

  /** Creates a new report containing all the data in this report, plus all the
   * data in that report.
   */
  def + (that: Report[S, T]): Report[S, T] = {
    Report[S, T](merge2WithDefault(aggregator.zero)(this.observationCounts, that.observationCounts) { (count1, count2) =>
      aggregator.aggregate(count1, count2)
    })
  }

  /** Maps the report based on the type of count.
   */
  def map[TT: AbelianGroup](f: T => TT): Report[S, TT] = {
    Report(observationCounts.transform { (k, v) => f(v) })
  }

  /** Groups the report by order of observation.
   */
  def groupByOrder: Map[Int, Report[S, T]] = {
    observationCounts.groupBy(_._1.size).transform { (order, group) =>
      Report(group)
    }
  }

  /** Creates a new report derived from this one containing only observations
   * of the specified order.
   */
  def order(n: Int): Report[S, T] = Report(observationCounts.collect { case tuple if (tuple._1.size == n) => tuple })

  /** Groups the report by period, for a time-series report (or one that's
   * isomorphic to a time series report).
   */
  def groupByPeriod[V](implicit witness: T => TimeSeries[V], group: AbelianGroup[V]): Map[Period, Report[S, TimeSeries[V]]] = {
    val flipped: Map[Period, Map[Observation[S], TimeSeries[V]]] = flip {
      map(witness).observationCounts.transform { (_, count) =>
        count.groupByPeriod
      }
    }

    flipped.transform { (_, map) => Report(map) }
  }

  //def groupByPeriodicity[V](implicit witness: T => TimeSeries[V], group: AbelianGroup[V]): Map[Periodicity, 
}

object Report {
  def empty[S <: Predicate, T: AbelianGroup]: Report[S, T] = Report[S, T](Map.empty)

  /** Creates a report of values.
   */
  def ofValues[T: AbelianGroup](event: JValue, count: T, order: Int, depth: Int, limit: Int): Report[HasValue, T] = {
    val flattened = event.flattenWithPath.take(limit).map {
      case (jpath, jvalue) => (Variable(jpath), HasValue(jvalue))
    }
  
    /*def factorial(n: Int) : Long = {
      def factorial0(n: Int, acc: Long): Long = {
        if (n <= 1) acc
        else factorial0(n - 1, acc * n)
      }

      factorial0(n, 1L)
    }

    def nchoosek(n: Int, k: Int): Long = factorial(n) / (factorial(k) * factorial(n - k))

    val ss = sublists(flattened, order)

    println("actual length: " + ss.length + ", expected length: " + (1 to order).foldLeft(0L)((length, order) => length + nchoosek(flattened.length, order)))*/

    Report(Map(sublists(flattened, order).map { subset =>
      (subset.toSet, count)
    }: _*))
  }

  /** Creates a report of children. Although the "order" parameter is supported,
   * it's recommended to always use a order = 1, because higher order counts do
   * not contain much additional information.
   */
  def ofChildren[T: AbelianGroup](event: JValue, count: T, order: Int, depth: Int, limit: Int): Report[HasChild, T] = {
    val agg = implicitly[AbelianGroup[T]]

    val empty = Set.empty[(Variable, HasChild)]

    val flattened = event.foldDownWithPath(empty) { (set, jpath, jvalue) =>
      val parent = jpath.parent

      parent.map { parent =>
        val child  = jpath.nodes.last

        set + (Variable(parent) -> HasChild(child))
      }.getOrElse(set)
    }.toList

    Report(Map(sublists(flattened, order).map { subset => (subset.toSet, count) }: _*))
  }
}
