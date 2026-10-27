/** okay-store: journaled, versioned records typed by a Schema, as an effect — Scala 3.3 LTS and 2.13, one source; the JVM
 * always, Scala.js and Native when asked */
val scala3 = "3.3.8"
val scala2 = "2.13.18"
import sbtcrossproject.{CrossProject, CrossType}
import Platforms._

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / organizationName := "okay-effects"
ThisBuild / homepage := Some(url("https://github.com/okay-effects/okay-store"))
ThisBuild / scmInfo := Some(ScmInfo(url("https://github.com/okay-effects/okay-store"), "scm:git:https://github.com/okay-effects/okay-store.git"))
ThisBuild / developers := List(Developer("sergey-scherbina", "Sergiy Shcherbyna", "sergey.scherbina@gmail.com", url("https://github.com/sergey-scherbina")))
ThisBuild / startYear := Some(2026)
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / scalaVersion := scala3
ThisBuild / resolvers += "okay-effects" at "https://okay-effects.github.io/okay-simple/maven"
/** okay-simple (json, shape, describe), from its Pages repository */
val okayEffects = "0.1.1"

/** RELEASES, ON GITHUB PAGES: `sbt -Dplatforms=jvm,js,native publishPages` writes every published module, for both
 * compilers and every platform asked, as a Maven repository into `target/pages-maven/`; that directory goes to the
 * `gh-pages` branch under `maven/`, served at https://okay-effects.github.io/okay-store/maven (RELEASING.md) */
val pagesRepository = settingKey[File]("the Maven repository a release is written to, for GitHub Pages")
ThisBuild / pagesRepository := (ThisBuild / baseDirectory).value / "target" / "pages-maven"
ThisBuild / publishTo := Some(MavenCache("pages", (ThisBuild / pagesRepository).value))
/** no API documentation jars, as okay-simple: the sources jars are published, which IDEs read for the documentation */
ThisBuild / Compile / packageDoc / publishArtifact := false

def crossBuilt: Seq[Def.Setting[?]] = Seq(
  crossScalaVersions := Seq(scala3, scala2),
  scalacOptions ++= (if (scalaVersion.value.startsWith("3")) Seq("-deprecation", "-feature") else Seq("-deprecation", "-feature", "-Xlint")),
  libraryDependencies += "org.scalameta" %%% "munit" % "1.2.3" % Test)
val platformsAsked: Set[String] =
  (sys.props.get("platforms") orElse sys.env.get("OKAY_PLATFORMS")).getOrElse("jvm").split(',').map(_.trim.toLowerCase).toSet + "jvm"
def platformsOf(can: Set[String]): Seq[sbtcrossproject.Platform] = Seq[sbtcrossproject.Platform](JVMPlatform) ++
  (if (platformsAsked("js") && can("js")) Seq(JSPlatform) else Nil) ++ (if (platformsAsked("native") && can("native")) Seq(NativePlatform) else Nil)
def crossBoth(id: String, dir: String, can: Set[String] = Set("js", "native")): CrossProject =
  CrossProject(id, file(dir))(platformsOf(can): _*).crossType(CrossType.Pure)
    .settings(crossBuilt).jvmConfigure(_.withId(id)).onJS(_.withId(id + "JS")).onNative(_.withId(id + "Native"))

/** THE STORE: kinds with a Schema, versioned records, the journal of events and its fold; the effect Store, its handler
 * in memory, its description for the faces (REST, MCP, remote) */
lazy val core = crossBoth("core", "core").settings(name := "okay-store",
  libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-effects" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-json" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-shape" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-describe" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test,
    "io.github.okay-effects" %%% "okay-effects-free" % okayEffects % Test))
  // `at("field")` on 2.13 is a whitebox macro: scala-reflect to compile it, not to run it
  .settings(libraryDependencies ++= (if (scalaVersion.value.startsWith("2")) Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided) else Nil))
  .jvmSettings(Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala-jvm")

/** ON A DATABASE: `Data` on JDBC by a dialect (SQLite and Postgres checked), and the store directly on SQLite; the JVM */
lazy val jdbc = (project in file("jdbc")).dependsOn(core.jvm % "compile->compile;test->test").settings(crossBuilt, name := "okay-store-jdbc",
  libraryDependencies ++= Seq("org.xerial" % "sqlite-jdbc" % "3.47.1.0",
    "org.postgresql" % "postgresql" % "42.7.4" % Test,
    "io.github.okay-effects" %% "okay-effects-machine" % okayEffects % Test,
    "io.github.okay-effects" %% "okay-effects-free" % okayEffects % Test))

lazy val root = (project in file("."))
  .aggregate((core.projects.values.map(p => p: ProjectReference).toSeq :+ (jdbc: ProjectReference)): _*)
  .settings(name := "okay-store-root", publish / skip := true, crossScalaVersions := Nil)

/** a release into `target/pages-maven/`, on Scala 3.3 and 2.13 */
addCommandAlias("publishPages", "; ++3.3.8 publish ; ++2.13.18 publish ; ++3.3.8")
