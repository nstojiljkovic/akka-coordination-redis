import sbt.Resolver.mavenLocal
import ReleaseTransformations._
import sbt.Keys.{libraryDependencies, name}

name := "Akka Coordination Redis"
val scala212Version = "2.12.8"
val scala213Version = "2.13.0"
val akkaVersion = "2.5.23"
val configVersion = "1.3.4"
val scalaJava8CompatVersion = "0.9.0"
val logbackClassicVersion = "1.2.3"
val scalaTestVersion = "3.0.8"
val specs2Version = "4.5.1"

scalaVersion := scala213Version

def updateReadmeVersion(selectVersion: sbtrelease.Versions => String) =
  ReleaseStep(action = st => {

    val newVersion = selectVersion(st.get(ReleaseKeys.versions).get)

    import scala.io.Source
    import java.io.PrintWriter

    val pattern = """"com.github.nstojiljkovic" %% "akka-coordination-redis" % "(.*)"""".r

    val fileName = "README.md"
    val content = Source.fromFile(fileName).getLines.mkString("\n")

    val newContent =
      pattern.replaceAllIn(content,
        m => m.matched.replaceAllLiterally(m.subgroups.head, newVersion))

    new PrintWriter(fileName) { write(newContent); close }

    val vcs = Project.extract(st).get(releaseVcs).get
    vcs.add(fileName).!

    st
  })

lazy val Javadoc = config("genjavadoc") extend Compile

lazy val javadocSettings = inConfig(Javadoc)(Defaults.configSettings) ++ Seq(
  addCompilerPlugin("com.typesafe.genjavadoc" %% "genjavadoc-plugin" % "0.13" cross CrossVersion.full),
  scalacOptions += s"-P:genjavadoc:out=${target.value}/java",
  packageDoc in Compile := (packageDoc in Javadoc).value,
  sources in Javadoc :=
    (target.value / "java" ** "*.java").get ++
      (sources in Compile).value.filter(_.getName.endsWith(".java")),
  javacOptions in Javadoc := Seq(),
  artifactName in packageDoc in Javadoc := ((sv, mod, art) =>
    "" + mod.name + "_" + sv.binary + "-" + mod.revision + "-javadoc.jar")
)

evictionWarningOptions in update := EvictionWarningOptions.default
  .withWarnTransitiveEvictions(false)
  .withWarnDirectEvictions(false)
  .withWarnScalaVersionEviction(true)

lazy val commonSettings =
  Seq(
    evictionWarningOptions in update := EvictionWarningOptions.default
      .withWarnTransitiveEvictions(false)
      .withWarnDirectEvictions(false)
      .withWarnScalaVersionEviction(true),
    organization := "com.github.nstojiljkovic",
    scalaVersion := scala213Version,
    crossScalaVersions := Seq(scala212Version, scala213Version),
    // publish configuration
    releaseVersionFile := baseDirectory.value / "version.sbt",
    publishMavenStyle := true,
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    pomExtra := (
      <url>https://github.com/nstojiljkovic/akka-coordination-redis</url>
        <licenses>
          <license>
            <name>Apache License 2.0</name>
            <url>https://raw.githubusercontent.com/nstojiljkovic/akka-coordination-redis/master/LICENSE</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>git@github.com:nstojiljkovic/akka-coordination-redis.git</url>
          <connection>scm:git@github.com:nstojiljkovic/akka-coordination-redis.git</connection>
        </scm>
        <developers>
          <developer>
            <id>nstojiljkovic</id>
            <name>Nikola Stojiljković</name>
            <url>https://github.com/nstojiljkovic</url>
          </developer>
        </developers>),
    publishArtifact in Test := false,
    sources in(Compile, doc) := Seq.empty,

    resolvers ++= Seq(
      // Resolver.jcenterRepo,
      mavenLocal,
      // Resolver.sonatypeRepo("public"),
    ),

    // configure sbt-release version bump
    releaseVersionBump := sbtrelease.Version.Bump.Bugfix,
    releaseTagComment := s"Releasing version ${(version in ThisBuild).value}",
    releaseCommitMessage := s"Setting new version to ${(version in ThisBuild).value}",

    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      setReleaseVersion,
      updateReadmeVersion(_._1),
      commitReleaseVersion,
      tagRelease,
      releaseStepCommand("publishSigned"),
      setNextVersion,
      // updateReadmeVersion(_._2),
      commitNextVersion,
      releaseStepCommand("sonatypeReleaseAll"),
      pushChanges),
  )

lazy val akkaRedisLease = (project in file(".")).configs(Javadoc).settings(javadocSettings: _*).
  settings(commonSettings: _*).
  settings(
    logBuffered in Test := false,
    parallelExecution in Test := false,
    name := "akka-coordination-redis",
    description := "Akka Lease implementation using Redis",

    libraryDependencies ++= Seq(
      // redisson
      "org.redisson" % "redisson" % "3.11.1",

      // akka
      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
      "com.typesafe.akka" %% "akka-coordination" % akkaVersion,

      // config
      "com.typesafe" % "config" % configVersion,

      // java 8 compat
      "org.scala-lang.modules" %% "scala-java8-compat" % scalaJava8CompatVersion,

      // logging
      "ch.qos.logback" % "logback-classic" % logbackClassicVersion,

      // scala test
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    ),
    excludeDependencies ++= Seq(
      "org.slf4j" % "slf4j-simple"
    ),
  )
