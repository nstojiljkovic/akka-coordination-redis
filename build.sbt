import sbt.Resolver.mavenLocal
import ReleaseTransformations._
import sbt.Keys.{libraryDependencies, name}

name := "Akka Coordination Redis"
val scalaV = "2.12.8"
val akkaVersion = "2.5.23"
val configVersion = "1.3.4"
val scalaJava8CompatVersion = "0.9.0"
val logbackClassicVersion = "1.2.3"
val scalaTestVersion = "3.0.7"
val specs2Version = "4.5.1"

scalaVersion := scalaV

def macroSettings(scaladocFor210: Boolean): Seq[Setting[_]] = Seq(
  libraryDependencies ++= Seq(
    scalaOrganization.value % "scala-compiler" % scalaVersion.value % Provided,
    scalaOrganization.value % "scala-reflect" % scalaVersion.value % Provided,
    "org.typelevel" %% "macro-compat" % "1.1.1",
    compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.patch)
  ),
  libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      // if scala 2.11+ is used, quasiquotes are merged into scala-reflect.
      case Some((2, scalaMajor)) if scalaMajor >= 11 => Nil
      // in Scala 2.10, quasiquotes are provided by macro paradise.
      case Some((2, 10)) => Seq("org.scalamacros" %% "quasiquotes" % "2.1.0" cross CrossVersion.binary)
    }
  },
  sources in(Compile, doc) := {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 10)) if !scaladocFor210 => Nil
      case _ => (sources in(Compile, doc)).value
    }
  }
)

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
    parallelExecution in Test := false,
    evictionWarningOptions in update := EvictionWarningOptions.default
      .withWarnTransitiveEvictions(false)
      .withWarnDirectEvictions(false)
      .withWarnScalaVersionEviction(true),
    organization := "com.github.nstojiljkovic",
    scalaVersion := scalaV,
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
      commitReleaseVersion,
      tagRelease,
      releaseStepCommand("publishSigned"),
      setNextVersion,
      commitNextVersion,
      releaseStepCommand("sonatypeReleaseAll"),
      pushChanges),
  )

lazy val akkaLeaseJava = (project in file(".")).configs(Javadoc).settings(javadocSettings: _*).
  settings(commonSettings: _*).
  settings(macroSettings(scaladocFor210 = false)).
  settings(
    name := "akka-coordination-redis",
    description := "Akka Lease implementation using Redis",

    libraryDependencies ++= Seq(
      // redisson
      "org.redisson" % "redisson" % "3.10.7",

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
