
package com.reportgrid
package analytics

import org.specs.{Specification, ScalaCheck}
import org.scalacheck._
import org.scalacheck.Gen._
import org.scalacheck.Prop._

class PathSpec extends Specification with ScalaCheck {
  "rollups for a path" should {
    "not roll up when flag is false" in {
      val sample = analytics.Path("/my/fancy/path")
      sample.rollups(false) must_== List(sample)
    }

    "include the original path" in {
      val sample = analytics.Path("/my/fancy/path")
      sample.rollups(true) must haveTheSameElementsAs(
        sample :: 
        analytics.Path("/my/fancy") :: 
        analytics.Path("/my") :: 
        analytics.Path("/") :: Nil
      )
    }
  }
}
