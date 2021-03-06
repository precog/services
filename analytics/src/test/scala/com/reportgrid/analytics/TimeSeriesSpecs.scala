package com.reportgrid.analytics

import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.json.JsonAST._
import org.joda.time.{Instant, DateTime, Duration}

import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.scalacheck._
import org.scalacheck.Gen._
import org.scalacheck.Prop._

import Periodicity._
import Arbitrary._

import scalaz.Scalaz._

class TimeSeriesSpecs extends Specification with ArbitraryTime with ScalaCheck {
  val genTimeClock = blueeyes.util.Clock.System

  "TimeSeriesEncoding.expand" should {
    val encoding = TimeSeriesEncoding.Default

    "correctly expand over a known period" in {
      val start = Minute.floor(new DateTime("2011-06-20T17:36:33.474-06:00").toInstant)
      val end =   Minute.floor(new DateTime("2011-06-21T09:23:02.005-06:00").toInstant)

      val expectedDuration = new Duration(start, end)
       
      val actualDuration = encoding.expand(start, end).foldLeft(new Duration(0, 0)) {
        case (totalSize, period) => totalSize.plus(period.size)
      }

      actualDuration must_== expectedDuration
    }

    "create periods whose total size is close to the duration between start and end" in {
      check { (time1: Instant, time2: Instant) =>
        val (start, end) = (if (time1.getMillis < time2.getMillis) (time1, time2) else (time2, time1)).mapElements(Minute.floor, Minute.floor)

        val expectedDuration = new Duration(start, end)

        val actualDuration = encoding.expand(start, end).foldLeft(new Duration(0, 0)) {
          case (totalSize, period) => totalSize.plus(period.size)
        }

        actualDuration.getMillis mustEqual (expectedDuration.getMillis)
      } 
    }

    "create a series where smaller periods are not sandwiched by larger periods" in {
      check { (time1: Instant, time2: Instant) =>
        val (start, end) = (if (time1.getMillis < time2.getMillis) (time1, time2) else (time2, time1)).mapElements(Minute.floor, Minute.floor)

        val expansion = encoding.expand(start, end)
        expansion.foldLeft((true, Option.empty[Periodicity])) {
          case ((true, None), period) => (true, Some(period.periodicity))
          case ((true, Some(periodicity)), period) =>
            //if it is currently increasing, periodicity granularity may either increase or decrease
            (period.periodicity >= periodicity, Some(period.periodicity))

          case ((false, Some(periodicity)), period) =>
            //if it is currently decreasing, periodicity granularity must decrease
            if (period.periodicity > periodicity) sys.error("Time series has the wrong shape.")
            (false, Some(period.periodicity))
        }

        true
      } 
    }
  }

  "TimeSeriesEncoding.queriableExpansions" should {
    val encoding = TimeSeriesEncoding.Default

    "create subsequences whose total size is close to the duration between start and end" in {
      check { (time1: Instant, time2: Instant) =>
        val (start, end) = (if (time1.getMillis < time2.getMillis) (time1, time2) else (time2, time1)).mapElements(Minute.floor, Minute.floor)

        val expectedDuration = new Duration(start, end)

        val actualDuration = encoding.queriableExpansion(TimeSpan(start, end)).foldLeft(new Duration(0, 0)) {
          case (totalSize, (_, TimeSpan(start, end))) => totalSize.plus(new Duration(start, end))
        }

        actualDuration.getMillis mustEqual (expectedDuration.getMillis)
      } 
    }

    "have an expansion that has at most two elements of each periodicity" in {
      check { (time1: Instant, time2: Instant) =>
        val (start, end) = (if (time1.getMillis < time2.getMillis) (time1, time2) else (time2, time1)).mapElements(Minute.floor, Minute.floor)

        val expansion = encoding.expand(start, end)
        encoding.queriableExpansion(expansion).groupBy(_._1).forall {
          case (k, v) => v.size <= 2
        }
      }
    }
  }
}
