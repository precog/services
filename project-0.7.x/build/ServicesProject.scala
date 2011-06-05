import sbt._
import netbeans.plugin._

class ServicesProject(info: ProjectInfo) extends ParentProject(info) {
  lazy val common    = project("common",    "common",    new CommonProject(_))
  lazy val analytics = project("analytics", "analytics", new AnalyticsProject(_), common)
  lazy val billing   = project("billing",   "billing",   new BillingProject(_), common)
  lazy val examples  = project("examples",  "examples",  new ExamplesProject(_), common)
  lazy val benchmark = project("benchmark", "benchmark", new BenchmarkProject(_), common)

  trait CommonDeps extends DefaultProject {
    val blueeyes    = "com.github.blueeyes"         % "blueeyes"          % "0.3.27"
    val configgy    = "net.lag"                     % "configgy"          % "2.0.0"
  }

  class CommonProject(info: ProjectInfo) extends DefaultProject(info) with CommonDeps with Repositories with IdeaProject with SbtNetbeansPlugin {
    val scalaspec   = "org.scala-tools.testing"     % "specs_2.8.0"       % "1.6.6-SNAPSHOT"  % "test"
    val scalacheck  = "org.scala-tools.testing"     % "scalacheck_2.8.0"  % "1.7"             % "test"

    val jodatime    = "joda-time"                   % "joda-time"         % "1.6.2"
  }

  class AnalyticsProject(info: ProjectInfo) extends DefaultProject(info) with CommonDeps with Repositories with OneJar with IdeaProject with SbtNetbeansPlugin {
    override def mainClass = Some("com.reportgrid.analytics.AnalyticsServer")

    //override def packageDocsJar = defaultJarPath("-javadoc.jar")
    //override def packageSrcJar  = defaultJarPath("-sources.jar")
  }

  class BenchmarkProject(info: ProjectInfo) extends DefaultProject(info) with CommonDeps with Repositories with OneJar with IdeaProject with SbtNetbeansPlugin {
    val api = "com.reportgrid" %% "scala-client" % "0.2.2"
    val scalacheck  = "org.scala-tools.testing"     % "scalacheck_2.8.0"  % "1.7"
    
    override def mainClass = Some("com.reportgrid.benchmark.AnalyticsBenchmark")
    //override def packageDocsJar = defaultJarPath("-javadoc.jar")
    //override def packageSrcJar  = defaultJarPath("-sources.jar")
  }
 
  class BillingProject(info: ProjectInfo) extends DefaultProject(info) with CommonDeps with Repositories with OneJar with IdeaProject {
    override def mainClass = Some("com.reportgrid.billing.BillingServer")

    //override def packageDocsJar = defaultJarPath("-javadoc.jar")
    //override def packageSrcJar  = defaultJarPath("-sources.jar")
  }

  class ExamplesProject(info: ProjectInfo) extends DefaultProject(info) with Repositories with OneJar with IdeaProject {
    val dispatch_http = "net.databinder"            %% "dispatch-http"    % "0.8.1"
    val jackmap       = "org.codehaus.jackson"      %  "jackson-mapper-asl"  % "1.8.1"
    val rosetta       = "github"                    %% "rosetta-json"     % "0.2"

    override def mainClass = Some("com.reportgrid.examples.gluecon.GlueConDemoServer")
    //override def packageDocsJar = defaultJarPath("-javadoc.jar")
    //override def packageSrcJar  = defaultJarPath("-sources.jar")
  }
}

trait Repositories {
  val scalareleases   = MavenRepository("Scala Repo Releases",        "http://scala-tools.org/repo-releases/")
  val scalasnapshots  = MavenRepository("Scala-tools.org Repository", "http://scala-tools.org/repo-snapshots/")
  val jbossreleases   = MavenRepository("JBoss Releases",             "http://repository.jboss.org/nexus/content/groups/public/")
  val sonatyperelease = MavenRepository("Sonatype Releases",          "http://oss.sonatype.org/content/repositories/releases")
  val nexusscalatools = MavenRepository("Nexus Scala Tools",          "http://nexus.scala-tools.org/content/repositories/releases")
  val mavenrepo1      = MavenRepository("Maven Repo 1",               "http://repo1.maven.org/maven2/")
  val scalablerepo    = MavenRepository("Scalable Solutions Repo",    "http://akka.io/repository/")
}

trait OneJar { self: DefaultProject =>
  lazy val oneJar = oneJarAction

  def oneJarAction = oneJarTask.dependsOn(`package`) describedAs("Creates a single JAR containing all dependencies that runs the project's mainClass")

  def oneJarTask: Task = task {
    import FileUtilities._
    import java.io.{ByteArrayInputStream, File}
    import java.util.jar.Manifest
    import org.apache.commons.io.FileUtils

    val manifest = new Manifest(new ByteArrayInputStream((
      "Manifest-Version: 1.0\n" +
      "Main-Class: " + self.mainClass.get + "\n").getBytes))

    val versionString = version match {
      case BasicVersion(major, _, _, _) => "-v" + major.toString

      case _ => version.toString
    }

    val allDependencies = jarPath +++ runClasspath +++ mainDependencies.scalaJars

    log.info("All dependencies of " + name + ": " + allDependencies)

    val destJar = (normalizedName + versionString + ".jar"): Path

    FileUtilities.withTemporaryDirectory(log) { tmpDir =>
      val tmpPath = Path.fromFile(tmpDir)

      allDependencies.get.foreach { dependency =>
        log.info("Unzipping " + dependency + " to " + tmpPath)

        if (dependency.ext.toLowerCase == "jar") {
          unzip(dependency, tmpPath, log)
        }
        else if (dependency.asFile.isDirectory) {
          FileUtils.copyDirectory(dependency.asFile, tmpDir)
        }
        else {
          copyFile(dependency.asFile, tmpDir, log)
        }
      }

      new File(tmpDir, "META-INF/MANIFEST.MF").delete

      log.info("Creating single jar out of all dependencies: " + destJar)

      jar(tmpDir.listFiles.map(Path.fromFile), destJar, manifest, true, log)

      None
    }
  }
}
