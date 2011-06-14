package com.reportgrid.analytics

import Periodicity._

import blueeyes.json.JsonAST._
import blueeyes.json.JsonParser._
import blueeyes.json.xschema._
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.persistence.mongo._

import org.joda.time.{DateTime, DateTimeZone}

import com.reportgrid.util.MapUtil._
import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scalaz._
import Scalaz._

case class TimeSeriesSpan[T: AbelianGroup] private (series: SortedMap[Period, T]) {
  def toTimeSeries(periodicity: Periodicity): TimeSeries[T] = error("todo")

  def flatten = series.values

  def total = series.values.asMA.sum
}

object TimeSeriesSpan {
  def apply[T: AbelianGroup](entries: (Period, T)*) = {
    val isValidSpan = entries.size <= 1 || {
      val sortedPeriods = entries.view.map(_._1).sorted
      sortedPeriods.zip(sortedPeriods.tail).foldLeft(true) { 
        case (false, _)     => false
        case (true, (a, b)) => a.end == b.start 
      }
    }

    if (isValidSpan) new TimeSeriesSpan(SortedMap(entries: _*))
    else error("The periods provided do not form a contiguous span")
  }
}

/** A time series stores an unlimited amount of time series data.
 */
case class TimeSeries[T] private (periodicity: Periodicity, series: Map[DateTime, T])(implicit aggregator: AbelianGroup[T]) {
  /** Fill all gaps in the returned time series -- i.e. any period that does
   * not exist will be mapped to a count of 0. Note that this function may
   * behave strangely if the time series contains periods of a different
   * periodicity.
   */
  def fillGaps: TimeSeries[T] = {
    if (series.isEmpty) this
    else {
      import blueeyes.util._
      val startTimes = series.keys

      TimeSeries(
        periodicity,
        (periodicity.period(startTimes.min) to startTimes.max).foldLeft(series) { (series, period) =>
          if (series.contains(period.start)) series
          else series + (period.start -> aggregator.zero)
        }
      )
    }
  }

  def aggregates = {
    @tailrec def superAggregates(periodicity: Periodicity, acc: List[TimeSeries[T]]): List[TimeSeries[T]] = {
      periodicity.nextOption match {
        case Some(p) => superAggregates(p, this.withPeriodicity(p).get :: acc)
        case None => acc
      }
    }

    superAggregates(periodicity, List(this))
  }

  def withPeriodicity(newPeriodicity: Periodicity): Option[TimeSeries[T]] = {
    if (newPeriodicity < periodicity) None
    else if (newPeriodicity == periodicity) Some(this)
    else Some(
      TimeSeries(
        newPeriodicity,
        series.foldLeft(Map.empty[DateTime, T]) {
          case (series, entry) => addToMap(newPeriodicity, series, entry)
        }
      )
    )
  }

  /** Combines the data in this time series with the data in that time series.
   */
  def + (that: TimeSeries[T]): TimeSeries[T] = {
    TimeSeries(periodicity, series <+> that.series)
  }

  def + (entry: (DateTime, T)): TimeSeries[T] = TimeSeries(periodicity, addToMap(periodicity, series, entry))

  def total: T = series.values.asMA.sum

  def toJValue(implicit d: Decomposer[T]): JValue = JObject(List(
    JField(
      periodicity.name, 
      JArray(series.toList.sortWith(_._1 < _._1).map { 
        case (time, count) => JArray(JInt(time.getMillis) :: count.serialize :: Nil)
      })
    )
  ))

  private def addToMap(p: Periodicity, m: Map[DateTime, T], entry: (DateTime, T)) = {
    val countTime = p.floor(entry._1)
    m + (countTime -> (m.getOrElse(countTime, aggregator.zero) |+| entry._2))
  }
}

object TimeSeries {
  def empty[T: AbelianGroup](periodicity: Periodicity): TimeSeries[T] = {
    new TimeSeries(periodicity, Map.empty[DateTime, T])
  }

  def point[T: AbelianGroup](periodicity: Periodicity, time: DateTime, value: T) = {
    new TimeSeries(periodicity, Map(periodicity.floor(time) -> value))
  }

  implicit def Semigroup[T: AbelianGroup] = Scalaz.semigroup[TimeSeries[T]](_ + _)
}

object TimeSeriesEncoding {
  private val DefaultGrouping = Map[Periodicity, Periodicity](
    Minute   -> Month,
    Hour     -> Year,
    Day      -> Year,
    Week     -> Eternity,
    Month    -> Eternity,
    Year     -> Eternity,
    Eternity -> Eternity
  )

  /** Returns an encoding for all periodicities from minute to eternity */
  val Default = new TimeSeriesEncoding(DefaultGrouping)

  /** Returns an aggregator for all periodicities from second to eternity */
  val All = new TimeSeriesEncoding(DefaultGrouping + (Second -> Day))
}

class TimeSeriesEncoding(val grouping: Map[Periodicity, Periodicity]) {
  def expand(start: DateTime, end: DateTime): Stream[Period] = {
    def expand0(start: DateTime, end: DateTime, periodicity: Periodicity): Stream[Period] = {
      import Stream._

      def expandFiner(start: DateTime, end: DateTime, periodicity: Periodicity): Stream[Period] = {
        periodicity.previousOption.filter(grouping.contains).
        map(expand0(start, end, _)).
        getOrElse {
          val length = end.getMillis - start.getMillis
          val period = periodicity.period(start)

          if (length.toDouble / period.size.getMillis >= 0.5) period +: Empty else Empty
        }
      }

      if (start.getMillis >= end.getMillis) Empty 
      else {
        val periods = periodicity.period(start) to end
        val head = periods.head
        val tail = periods.tail

        if (periods.tail.isEmpty) expandFiner(start, end, periodicity)
        else {
          expandFiner(start, head.end, periodicity) ++ 
          tail.init ++ 
          expandFiner(tail.last.start, end, periodicity)
        }
      }
    }

    expand0(start, end, Periodicity.Year)
  }
}
