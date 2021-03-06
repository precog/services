import AssemblyKeys._

name := "billing"

version      := "1.1.4"

organization := "com.reportgrid"

scalaVersion := "2.9.1"

scalacOptions ++= Seq("-deprecation", "-unchecked")

libraryDependencies ++= Seq(
  "commons-codec"           %  "commons-codec"      % "1.5",
  "commons-httpclient"      %  "commons-httpclient" % "3.1",
  "joda-time"               %  "joda-time"          % "1.6.2",
  "org.scalaz"              %% "scalaz-core"        % "6.0.2",
  "ch.qos.logback"          %  "logback-classic"    % "1.0.0",
  "org.specs2"              %% "specs2"             % "1.8"  % "test",
  "org.scala-tools.testing" %% "scalacheck"         % "1.9"  % "test",
  "com.braintreegateway"    %  "braintree-java"     % "2.19.0"
)

mainClass := Some("com.reportgrid.billing.BillingServer")

seq(assemblySettings: _*)

resolvers += "braintree-public-repo" at "http://braintree.github.com/braintree_java/releases"
