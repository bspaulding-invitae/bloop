package build

import java.io.File

import bintray.BintrayKeys
import ch.epfl.scala.sbt.release.Feedback
import com.typesafe.sbt.SbtPgp.{autoImport => Pgp}
import sbt.{AutoPlugin, BuildPaths, Def, Keys, PluginTrigger, Plugins, State, Task, ThisBuild, uri}
import sbt.io.IO
import sbt.io.syntax.fileToRichFile
import sbt.librarymanagement.syntax.stringToOrganization
import sbt.util.FileFunction
import sbtassembly.PathList
import sbtdynver.GitDescribeOutput
import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin.{autoImport => ReleaseEarlyKeys}
import sbt.internal.BuildLoader
import sbt.librarymanagement.MavenRepository

object BuildPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  import com.typesafe.sbt.SbtPgp
  import ch.epfl.scala.sbt.release.ReleaseEarlyPlugin
  import com.lucidchart.sbt.scalafmt.ScalafmtCorePlugin

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins =
    JvmPlugin && ScalafmtCorePlugin && ReleaseEarlyPlugin && SbtPgp
  val autoImport = BuildKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    BuildImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    BuildImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    BuildImplementation.projectSettings
}

object BuildKeys {
  import sbt.{Reference, RootProject, ProjectRef, BuildRef, file, uri}

  def inProject(ref: Reference)(ss: Seq[Def.Setting[_]]): Seq[Def.Setting[_]] =
    sbt.inScope(sbt.ThisScope.in(project = ref))(ss)

  def inProjectRefs(refs: Seq[Reference])(ss: Def.Setting[_]*): Seq[Def.Setting[_]] =
    refs.flatMap(inProject(_)(ss))

  def inCompileAndTest(ss: Def.Setting[_]*): Seq[Def.Setting[_]] =
    Seq(sbt.Compile, sbt.Test).flatMap(sbt.inConfig(_)(ss))

  // Use absolute paths so that references work even if `ThisBuild` changes
  final val AbsolutePath = file(".").getCanonicalFile.getAbsolutePath

  private val isCiDisabled = sys.env.get("CI").isEmpty
  def createScalaCenterProject(name: String, f: File): RootProject = {
    if (isCiDisabled) RootProject(f)
    else {
      val headSha = new com.typesafe.sbt.git.DefaultReadableGit(f).withGit(_.headCommitSha)
      headSha match {
        case Some(commit) => RootProject(uri(s"git://github.com/scalacenter/${name}.git#$commit"))
        case None => sys.error(s"The 'HEAD' sha of '${f}' could not be retrieved.")
      }
    }
  }

  final val BenchmarkBridgeProject =
    createScalaCenterProject("compiler-benchmark", file(s"$AbsolutePath/benchmark-bridge"))
  final val BenchmarkBridgeBuild = BuildRef(BenchmarkBridgeProject.build)
  final val BenchmarkBridgeCompilation = ProjectRef(BenchmarkBridgeProject.build, "compilation")

  import sbt.{Test, TestFrameworks, Tests}
  val buildBase = Keys.baseDirectory in ThisBuild
  val integrationStagingBase =
    Def.taskKey[File]("The base directory for sbt staging in all versions.")
  val integrationSetUpBloop =
    Def.taskKey[Unit]("Generate the bloop config for integration tests.")
  val buildIntegrationsIndex =
    Def.taskKey[File]("A csv index with complete information about our integrations.")
  val localBenchmarksIndex =
    Def.taskKey[File]("A csv index with complete information about our benchmarks (for local use).")
  val buildIntegrationsBase = Def.settingKey[File]("The base directory for our integration builds.")
  val twitterDodo = Def.settingKey[File]("The location of Twitter's dodo build tool")

  val bloopName = Def.settingKey[String]("The name to use in build info generated code")
  val nailgunClientLocation = Def.settingKey[sbt.File]("Where to find the python nailgun client")
  val updateHomebrewFormula = Def.taskKey[Unit]("Update Homebrew formula")
  val createLocalHomebrewFormula = Def.taskKey[Unit]("Create local Homebrew formula")

  val gradleIntegrationDirs = sbt.AttributeKey[List[File]]("gradleIntegrationDirs")
  val fetchGradleApi = Def.taskKey[Unit]("Fetch Gradle API artifact")

  // This has to be change every time the bloop config files format changes.
  val schemaVersion = Def.settingKey[String]("The schema version for our bloop build.")

  val testSettings: Seq[Def.Setting[_]] = List(
    Keys.testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
    Keys.libraryDependencies ++= List(
      Dependencies.junit % Test,
      Dependencies.difflib % Test
    ),
    nailgunClientLocation := buildBase.value / "nailgun" / "pynailgun" / "ng.py",
  )

  val integrationTestSettings: Seq[Def.Setting[_]] = List(
    integrationStagingBase :=
      BuildImplementation.BuildDefaults.getStagingDirectory(Keys.state.value),
    buildIntegrationsIndex := {
      val staging = integrationStagingBase.value
      staging / s"bloop-integrations-${BuildKeys.schemaVersion.in(sbt.Global).value}.csv"
    },
    localBenchmarksIndex := {
      new File(System.getProperty("user.dir"), ".local-benchmarks")
    },
    buildIntegrationsBase := (Keys.baseDirectory in ThisBuild).value / "build-integrations",
    twitterDodo := buildIntegrationsBase.value./("build-twitter"),
    integrationSetUpBloop := BuildImplementation.integrationSetUpBloop.value,
  )

  import ohnosequences.sbt.GithubRelease.{keys => GHReleaseKeys}
  val releaseSettings = Seq(
    GHReleaseKeys.ghreleaseNotes := { tagName =>
      IO.read(buildBase.value / "notes" / s"$tagName.md")
    },
    GHReleaseKeys.ghreleaseRepoOrg := "scalacenter",
    GHReleaseKeys.ghreleaseRepoName := "bloop",
    GHReleaseKeys.ghreleaseAssets += ReleaseUtils.versionedInstallScript.value,
    createLocalHomebrewFormula := ReleaseUtils.createLocalHomebrewFormula.value
  )

  import sbtbuildinfo.{BuildInfoKey, BuildInfoKeys}
  final val BloopBackendInfoKeys: List[BuildInfoKey] = {
    val scalaJarsKey =
      BuildInfoKey.map(Keys.scalaInstance) { case (_, i) => "scalaJars" -> i.allJars.toList }
    List(Keys.scalaVersion, Keys.scalaOrganization, scalaJarsKey)
  }

  import sbt.util.Logger.{Null => NullLogger}
  def bloopInfoKeys(
      nativeBridge: Reference,
      jsBridge06: Reference,
      jsBridge10: Reference
  ): List[BuildInfoKey] = {
    val zincKey = BuildInfoKey.constant("zincVersion" -> Dependencies.zincVersion)
    val developersKey =
      BuildInfoKey.map(Keys.developers) { case (k, devs) => k -> devs.map(_.name) }
    type Module = sbt.internal.librarymanagement.IvySbt#Module
    def fromIvyModule(id: String, e: BuildInfoKey.Entry[Module]): BuildInfoKey.Entry[String] = {
      BuildInfoKey.map(e) {
        case (_, module) =>
          id -> module.withModule(NullLogger)((_, mod, _) => mod.getModuleRevisionId.getName)
      }
    }

    // Add only the artifact name for 0.6 bridge because we replace it
    val jsBridge06Key = fromIvyModule("jsBridge06", Keys.ivyModule in jsBridge06)
    val jsBridge10Key = fromIvyModule("jsBridge10", Keys.ivyModule in jsBridge10)
    val nativeBridgeKey = fromIvyModule("nativeBridge", Keys.ivyModule in nativeBridge)
    val bspKey = BuildInfoKey.constant("bspVersion" -> Dependencies.bspVersion)
    val extra = List(zincKey, developersKey, nativeBridgeKey, jsBridge06Key, jsBridge10Key, bspKey)
    val commonKeys = List[BuildInfoKey](
      Keys.organization,
      BuildKeys.bloopName,
      Keys.version,
      Keys.scalaVersion,
      Keys.sbtVersion,
      buildIntegrationsIndex,
      localBenchmarksIndex,
      nailgunClientLocation
    )
    commonKeys ++ extra
  }

  val GradleInfoKeys: List[BuildInfoKey] = List(
    BuildInfoKey.map(Keys.state) {
      case (_, state) =>
        val integrationDirs = state
          .get(gradleIntegrationDirs)
          .getOrElse(sys.error("Fatal: integration dirs for gradle were not computed"))
        "integrationDirs" -> integrationDirs
    }
  )

  import sbtassembly.{AssemblyKeys, MergeStrategy}
  val assemblySettings: Seq[Def.Setting[_]] = List(
    Keys.mainClass in AssemblyKeys.assembly := Some("bloop.Bloop"),
    Keys.test in AssemblyKeys.assembly := {},
    AssemblyKeys.assemblyMergeStrategy in AssemblyKeys.assembly := {
      case "LICENSE.md" => MergeStrategy.first
      case "NOTICE.md" => MergeStrategy.first
      case PathList("io", "github", "soc", "directories", _ @_*) => MergeStrategy.first
      case x =>
        val oldStrategy = (AssemblyKeys.assemblyMergeStrategy in AssemblyKeys.assembly).value
        oldStrategy(x)
    }
  )

  def sbtPluginSettings(sbtVersion: String, jsonConfig: Reference): Seq[Def.Setting[_]] = List(
    Keys.name := "sbt-bloop",
    Keys.sbtPlugin := true,
    Keys.sbtVersion := sbtVersion,
    Keys.target := (file("integrations") / "sbt-bloop" / "target" / sbtVersion).getAbsoluteFile,
    BintrayKeys.bintrayPackage := "sbt-bloop",
    BintrayKeys.bintrayOrganization := Some("sbt"),
    BintrayKeys.bintrayRepository := "sbt-plugin-releases",
    Keys.publishMavenStyle :=
      ReleaseEarlyKeys.releaseEarlyWith.value == ReleaseEarlyKeys.SonatypePublisher,
    Keys.publishLocal := Keys.publishLocal.dependsOn(Keys.publishLocal in jsonConfig).value
  )

  def benchmarksSettings(dep: Reference): Seq[Def.Setting[_]] = List(
    Keys.skip in Keys.publish := true,
    BuildInfoKeys.buildInfoKeys := {
      val fullClasspathFiles = BuildInfoKey.map(Keys.fullClasspathAsJars.in(sbt.Compile).in(dep)) {
        case (key, value) => ("fullCompilationClasspath", value.toList.map(_.data))
      }
      Seq[BuildInfoKey](
        Keys.resourceDirectory in sbt.Test in dep,
        fullClasspathFiles
      )
    },
    BuildInfoKeys.buildInfoPackage := "bloop.benchmarks",
    Keys.javaOptions ++= {
      def refOf(version: String) = {
        val HasSha = """(?:.+?)-([0-9a-f]{8})(?:\+\d{8}-\d{4})?""".r
        version match {
          case HasSha(sha) => sha
          case _ => version
        }
      }
      List(
        "-Dsbt.launcher=" + (sys
          .props("java.class.path")
          .split(java.io.File.pathSeparatorChar)
          .find(_.contains("sbt-launch"))
          .getOrElse("")),
        "-DbloopVersion=" + Keys.version.in(dep).value,
        "-DbloopRef=" + refOf(Keys.version.in(dep).value),
        "-Dgit.localdir=" + buildBase.value.getAbsolutePath
      )
    }
  )
}

object BuildImplementation {
  import sbt.{url, file}
  import sbt.{Developer, Resolver, Watched, Compile, Test}
  import sbtdynver.DynVerPlugin.{autoImport => DynVerKeys}

  // This should be added to upstream sbt.
  def GitHub(org: String, project: String): java.net.URL =
    url(s"https://github.com/$org/$project")
  def GitHubDev(handle: String, fullName: String, email: String) =
    Developer(handle, fullName, email, url(s"https://github.com/$handle"))

  final val globalSettings: Seq[Def.Setting[_]] = Seq(
    Keys.cancelable := true,
    BuildKeys.schemaVersion := "4.2",
    Keys.testOptions in Test += sbt.Tests.Argument("-oD"),
    Keys.onLoadMessage := Header.intro,
    Keys.onLoad := BuildDefaults.bloopOnLoad.value,
    Keys.publishArtifact in Test := false,
    Pgp.pgpPublicRing := {
      if (Keys.insideCI.value) file("/drone/.gnupg/pubring.asc")
      else Pgp.pgpPublicRing.value
    },
    Pgp.pgpSecretRing := {
      if (Keys.insideCI.value) file("/drone/.gnupg/secring.asc")
      else Pgp.pgpSecretRing.value
    },
  )

  private final val ThisRepo = GitHub("scalacenter", "bloop")
  final val buildSettings: Seq[Def.Setting[_]] = Seq(
    Keys.organization := "ch.epfl.scala",
    Keys.updateOptions := Keys.updateOptions.value.withCachedResolution(true),
    Keys.scalaVersion := Dependencies.Scala212Version,
    Keys.triggeredMessage := Watched.clearWhenTriggered,
    Keys.resolvers := {
      val oldResolvers = Keys.resolvers.value
      val scalacenterResolver = Resolver.bintrayRepo("scalacenter", "releases")
      val scalametaResolver = Resolver.bintrayRepo("scalameta", "maven")
      (oldResolvers :+ scalametaResolver :+ scalacenterResolver).distinct
    },
    ReleaseEarlyKeys.releaseEarlyWith := {
      // Only tag releases go directly to Maven Central, the rest go to bintray!
      val isOnlyTag = DynVerKeys.dynverGitDescribeOutput.value.map(v =>
        v.commitSuffix.isEmpty && v.dirtySuffix.value.isEmpty)
      if (isOnlyTag.getOrElse(false)) ReleaseEarlyKeys.SonatypePublisher
      else ReleaseEarlyKeys.BintrayPublisher
    },
    BintrayKeys.bintrayOrganization := Some("scalacenter"),
    Keys.startYear := Some(2017),
    Keys.autoAPIMappings := true,
    Keys.publishMavenStyle := true,
    Keys.homepage := Some(ThisRepo),
    Keys.licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    Keys.developers := List(
      GitHubDev("Duhemm", "Martin Duhem", "martin.duhem@gmail.com"),
      GitHubDev("jvican", "Jorge Vicente Cantero", "jorge@vican.me")
    ),
  )

  import sbt.{CrossVersion, compilerPlugin}
  final val metalsSettings: Seq[Def.Setting[_]] = Seq(
    Keys.scalacOptions ++= {
      if (Keys.scalaBinaryVersion.value.startsWith("2.10")) Nil
      else List("-Yrangepos")
    },
    Keys.libraryDependencies ++= {
      if (Keys.scalaBinaryVersion.value.startsWith("2.10")) Nil
      else
        List(
          compilerPlugin("org.scalameta" % "semanticdb-scalac" % "2.1.5" cross CrossVersion.full)
        )
    },
  )

  final val projectSettings: Seq[Def.Setting[_]] = Seq(
    BintrayKeys.bintrayRepository := "releases",
    BintrayKeys.bintrayPackage := "bloop",
    // Add some metadata that is useful to see in every on-merge bintray release
    BintrayKeys.bintrayPackageLabels := List("productivity", "build", "server", "cli", "tooling"),
    BintrayKeys.bintrayVersionAttributes ++= {
      import bintry.Attr
      Map(
        "zinc" -> Seq(Attr.String(Dependencies.zincVersion)),
        "nailgun" -> Seq(Attr.String(Dependencies.nailgunVersion))
      )
    },
    ReleaseEarlyKeys.releaseEarlyPublish := BuildDefaults.releaseEarlyPublish.value,
    Keys.scalacOptions := reasonableCompileOptions,
    // Legal requirement: license and notice files must be in the published jar
    Keys.resources in Compile ++= BuildDefaults.getLicense.value,
    Keys.publishArtifact in Test := false,
    Keys.publishArtifact in (Compile, Keys.packageDoc) := {
      val output = DynVerKeys.dynverGitDescribeOutput.value
      val version = Keys.version.value
      BuildDefaults.publishDocAndSourceArtifact(output, version)
    },
    Keys.publishArtifact in (Compile, Keys.packageSrc) := {
      val output = DynVerKeys.dynverGitDescribeOutput.value
      val version = Keys.version.value
      BuildDefaults.publishDocAndSourceArtifact(output, version)
    },
  ) // ++ metalsSettings

  final val reasonableCompileOptions = (
    "-deprecation" :: "-encoding" :: "UTF-8" :: "-feature" :: "-language:existentials" ::
      "-language:higherKinds" :: "-language:implicitConversions" :: "-unchecked" :: "-Yno-adapted-args" ::
      "-Ywarn-numeric-widen" :: "-Ywarn-value-discard" :: "-Xfuture" :: Nil
  )

  final val jvmOptions = "-Xmx4g" :: "-Xms2g" :: "-XX:ReservedCodeCacheSize=512m" :: "-XX:MaxInlineLevel=20" :: Nil

  object BuildDefaults {
    private final val kafka =
      uri("git://github.com/apache/kafka.git#57320981bb98086a0b9f836a29df248b1c0378c3")

    /** This onLoad hook will clone any repository required for the build tool integration tests.
     * In this case, we clone kafka so that the gradle plugin unit tests can access to its directory. */
    val bloopOnLoad: Def.Initialize[State => State] = Def.setting {
      Keys.onLoad.value.andThen { state =>
        val staging = getStagingDirectory(state)
        // Side-effecting operation to clone kafka if it hasn't been cloned yet
        val newState = {
          sbt.Resolvers.git(new BuildLoader.ResolveInfo(kafka, staging, null, state)) match {
            case Some(f) => state.put(BuildKeys.gradleIntegrationDirs, List(f()))
            case None =>
              state.log.error("Kafka git reference is invalid and cannot be cloned"); state
          }
        }

        import java.util.Locale
        val isWindows: Boolean =
          System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")

        // Generate bloop configuration files for projects we use in our test suite upfront
        val resourcesDir = newState.baseDir / "frontend" / "src" / "test" / "resources"
        val pluginSourceDir = newState.baseDir / "integrations" / "sbt-bloop" / "src" / "main"
        val projectDirs = resourcesDir.listFiles().filter(_.isDirectory)
        projectDirs.foreach { projectDir =>
          val targetDir = projectDir / "target"
          val cacheDirectory = targetDir / "generation-cache-file"
          java.nio.file.Files.createDirectories(cacheDirectory.toPath)

          val projectsFiles = sbt.io.Path
            .allSubpaths(projectDir)
            .map(_._1)
            .filter { f =>
              val filename = f.toString
              filename.endsWith(".sbt") || filename.endsWith(".scala")
            }
            .toSet

          val pluginFiles = sbt.io.Path
            .allSubpaths(pluginSourceDir)
            .map(_._1)
            .filter(f => f.toString.endsWith(".scala"))
            .toSet

          import scala.sys.process.Process
          val cached = FileFunction.cached(cacheDirectory, sbt.util.FileInfo.hash) { changedFiles =>
            newState.log.info(s"Generating bloop configuration files for ${projectDir}")
            val cmdBase = if (isWindows) "cmd.exe" :: "/C" :: "sbt.bat" :: Nil else "sbt" :: Nil
            val cmd = cmdBase ::: List("bloopInstall")
            val exitGenerate = Process(cmd, projectDir).!
            if (exitGenerate != 0)
              throw new sbt.MessageOnlyException(
                s"Failed to generate bloop config for ${projectDir}.")
            newState.log.success(s"Generated bloop configuration files for ${projectDir}")
            changedFiles
          }

          cached(projectsFiles ++ pluginFiles)
        }

        newState
      }
    }

    def getStagingDirectory(state: State): File = {
      // Use the default staging directory, we don't care if the user changed it.
      val globalBase = sbt.BuildPaths.getGlobalBase(state)
      sbt.BuildPaths.getStagingDirectory(state, globalBase)
    }

    import sbt.librarymanagement.Artifact
    import ch.epfl.scala.sbt.maven.MavenPluginKeys
    val mavenPluginBuildSettings: Seq[Def.Setting[_]] = List(
      MavenPluginKeys.mavenPlugin := true,
      Keys.publishLocal := Keys.publishM2.value,
      Keys.classpathTypes += "maven-plugin",
      // This is a bug in sbt, so we fix it here.
      Keys.makePomConfiguration :=
        Keys.makePomConfiguration.value.withIncludeTypes(Keys.classpathTypes.value),
      Keys.libraryDependencies ++= List(
        Dependencies.mavenCore,
        Dependencies.mavenPluginApi,
        Dependencies.mavenPluginAnnotations,
        // We add an explicit dependency to the maven-plugin artifact in the dependent plugin
        Dependencies.mavenScalaPlugin
          .withExplicitArtifacts(Vector(Artifact("scala-maven-plugin", "maven-plugin", "jar")))
      ),
    )

    import sbtbuildinfo.BuildInfoPlugin.{autoImport => BuildInfoKeys}
    val gradlePluginBuildSettings: Seq[Def.Setting[_]] = {
      sbtbuildinfo.BuildInfoPlugin.buildInfoScopedSettings(Test) ++ List(
        Keys.resolvers ++= List(
          MavenRepository("Gradle releases", "https://repo.gradle.org/gradle/libs-releases-local/")
        ),
        Keys.libraryDependencies ++= List(
          Dependencies.gradleCore,
          Dependencies.gradleToolingApi,
          Dependencies.groovy
        ),
        Keys.publishLocal := Keys.publishLocal.dependsOn(Keys.publishM2).value,
        Keys.unmanagedJars.in(Compile) := unmanagedJarsWithGradleApi.value,
        BuildKeys.fetchGradleApi := {
          val logger = Keys.streams.value.log
          // TODO: we may want to fetch it to a custom unmanaged lib directory under build
          val targetDir = (Keys.baseDirectory in Compile).value / "lib"
          GradleIntegration.fetchGradleApi(Dependencies.gradleVersion, targetDir, logger)
        },
        // Only generate for tests (they are not published and can contain user-dependent data)
        BuildInfoKeys.buildInfo in Compile := Nil,
        BuildInfoKeys.buildInfoKeys in Test := BuildKeys.GradleInfoKeys,
        BuildInfoKeys.buildInfoPackage in Test := "bloop.internal.build",
        BuildInfoKeys.buildInfoObject in Test := "BloopGradleIntegration",
      )
    }

    lazy val unmanagedJarsWithGradleApi: Def.Initialize[Task[Keys.Classpath]] = Def.taskDyn {
      val unmanagedJarsTask = Keys.unmanagedJars.in(Compile).taskValue
      val _ = BuildKeys.fetchGradleApi.value
      Def.task(unmanagedJarsTask.value)
    }

    val millModuleBuildSettings: Seq[Def.Setting[_]] = List(
      Keys.libraryDependencies ++= List(
        Dependencies.mill
      )
    )

    import sbt.ScriptedPlugin.{autoImport => ScriptedKeys}
    val scriptedSettings: Seq[Def.Setting[_]] = List(
      ScriptedKeys.scriptedBufferLog := false,
      ScriptedKeys.scriptedLaunchOpts := {
        ScriptedKeys.scriptedLaunchOpts.value ++
          Seq("-Xmx1024M", "-Dplugin.version=" + Keys.version.value)
      }
    )

    val releaseEarlyPublish: Def.Initialize[Task[Unit]] = Def.task {
      val logger = Keys.streams.value.log
      val name = Keys.name.value
      // We force publishSigned for all of the modules, yes or yes.
      if (ReleaseEarlyKeys.releaseEarlyWith.value == ReleaseEarlyKeys.SonatypePublisher) {
        logger.info(Feedback.logReleaseSonatype(name))
      } else {
        logger.info(Feedback.logReleaseBintray(name))
      }

      Pgp.PgpKeys.publishSigned.value
    }

    val fixScalaVersionForSbtPlugin: Def.Initialize[String] = Def.setting {
      val orig = Keys.scalaVersion.value
      val is013 = (Keys.sbtVersion in Keys.pluginCrossBuild).value.startsWith("0.13")
      if (is013) "2.10.7" else orig
    }

    // From sbt-sensible https://gitlab.com/fommil/sbt-sensible/issues/5, legal requirement
    val getLicense: Def.Initialize[Task[Seq[File]]] = Def.task {
      val orig = (Keys.resources in Compile).value
      val base = Keys.baseDirectory.value
      val root = (Keys.baseDirectory in ThisBuild).value

      def fileWithFallback(name: String): File =
        if ((base / name).exists) base / name
        else if ((root / name).exists) root / name
        else throw new IllegalArgumentException(s"legal file $name must exist")

      Seq(fileWithFallback("LICENSE.md"), fileWithFallback("NOTICE.md"))
    }

    /**
     * This setting figures out whether the version is a snapshot or not and configures
     * the source and doc artifacts that are published by the build.
     *
     * Snapshot is a term with no clear definition. In this code, a snapshot is a revision
     * that is dirty, e.g. has time metadata in its representation. In those cases, the
     * build will not publish doc and source artifacts by any of the publishing actions.
     */
    def publishDocAndSourceArtifact(info: Option[GitDescribeOutput], version: String): Boolean = {
      val isStable = info.map(_.dirtySuffix.value.isEmpty)
      !isStable.exists(stable => !stable || version.endsWith("-SNAPSHOT"))
    }
  }

  import scala.sys.process.Process
  val integrationSetUpBloop = Def.task {
    import sbt.MessageOnlyException
    import java.util.Locale

    val buildIntegrationsBase = BuildKeys.buildIntegrationsBase.value
    val buildIndexFile = BuildKeys.buildIntegrationsIndex.value
    val schemaVersion = BuildKeys.schemaVersion.value
    val stagingBase = BuildKeys.integrationStagingBase.value.getCanonicalFile.getAbsolutePath
    val cacheDirectory = file(stagingBase) / "integrations-cache"
    val targetSchemaVersion = Keys.target.value / "schema-version.json"
    IO.write(targetSchemaVersion, schemaVersion)

    val buildFiles = Set(
      targetSchemaVersion,
      buildIntegrationsBase / "sbt-0.13" / "build.sbt",
      buildIntegrationsBase / "sbt-0.13" / "project" / "Integrations.scala",
      buildIntegrationsBase / "sbt-0.13-2" / "build.sbt",
      buildIntegrationsBase / "sbt-0.13-2" / "project" / "Integrations.scala",
      buildIntegrationsBase / "sbt-0.13-3" / "build.sbt",
      buildIntegrationsBase / "sbt-0.13-3" / "project" / "Integrations.scala",
      buildIntegrationsBase / "sbt-1.0" / "build.sbt",
      buildIntegrationsBase / "sbt-1.0" / "project" / "Integrations.scala",
      buildIntegrationsBase / "sbt-1.0-2" / "build.sbt",
      buildIntegrationsBase / "sbt-1.0-2" / "project" / "Integrations.scala",
      buildIntegrationsBase / "global" / "src" / "main" / "scala" / "bloop" / "build" / "integrations" / "IntegrationPlugin.scala",
    )

    val cachedGenerate =
      FileFunction.cached(cacheDirectory, sbt.util.FileInfo.hash) { builds =>
        val isWindows: Boolean =
          System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")

        if (!isWindows) {
          // Twitter projects are not added to the community build under Windows
          val cmd = "bash" :: BuildKeys.twitterDodo.value.getAbsolutePath :: "--no-test" :: "finagle" :: Nil
          val dodoSetUp = Process(cmd, buildIntegrationsBase).!
          if (dodoSetUp != 0)
            throw new MessageOnlyException(
              "Failed to publish locally dodo snapshots for twitter projects.")
        }

        val globalPluginsBase = buildIntegrationsBase / "global"
        val globalSettingsBase = globalPluginsBase / "settings"
        val stagingProperty = s"-D${BuildPaths.StagingProperty}=${stagingBase}"
        val settingsProperty = s"-D${BuildPaths.GlobalSettingsProperty}=${globalSettingsBase}"
        val pluginsProperty = s"-D${BuildPaths.GlobalPluginsProperty}=${globalPluginsBase}"
        val indexProperty = s"-Dbloop.integrations.index=${buildIndexFile.getAbsolutePath}"
        val schemaProperty = s"-Dbloop.integrations.schemaVersion=$schemaVersion"
        val properties = stagingProperty :: indexProperty :: pluginsProperty :: settingsProperty :: schemaProperty :: Nil
        val toRun = "cleanAllBuilds" :: "bloopInstall" :: "buildIndex" :: Nil
        val cmdBase = if (isWindows) "cmd.exe" :: "/C" :: "sbt.bat" :: Nil else "sbt" :: Nil
        val cmd = cmdBase ::: properties ::: toRun

        IO.delete(buildIndexFile)

        val exitGenerate013 = Process(cmd, buildIntegrationsBase / "sbt-0.13").!
        if (exitGenerate013 != 0)
          throw new MessageOnlyException("Failed to generate bloop config with sbt 0.13.")

        val exitGenerate0132 = Process(cmd, buildIntegrationsBase / "sbt-0.13-2").!
        if (exitGenerate0132 != 0)
          throw new MessageOnlyException("Failed to generate bloop config with sbt 0.13 (2).")

        val exitGenerate0133 = Process(cmd, buildIntegrationsBase / "sbt-0.13-3").!
        if (exitGenerate0133 != 0)
          throw new MessageOnlyException("Failed to generate bloop config with sbt 0.13 (3).")

        val exitGenerate10 = Process(cmd, buildIntegrationsBase / "sbt-1.0").!
        if (exitGenerate10 != 0)
          throw new MessageOnlyException("Failed to generate bloop config with sbt 1.0.")

        val exitGenerate102 = Process(cmd, buildIntegrationsBase / "sbt-1.0-2").!
        if (exitGenerate102 != 0)
          throw new MessageOnlyException("Failed to generate bloop config with sbt 1.0 (2).")

        val exitGenerate103 = Process(cmd, buildIntegrationsBase / "sbt-1.0-3").!
        if (exitGenerate103 != 0)
          throw new MessageOnlyException("Failed to generate bloop config with sbt 1.0 (3).")

        Set(buildIndexFile)
      }
    cachedGenerate(buildFiles)
  }
}

object Header {
  val intro: String =
    """      _____            __         ______           __
      |     / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____
      |     \__ \/ ___/ __ `/ / __ `/  / /   / _ \/ __ \/ __/ _ \/ ___/
      |    ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /
      |   /____/\___/\__,_/_/\__,_/   \____/\___/_/ /_/\__/\___/_/
      |
      |   ***********************************************************
      |   ***       Welcome to the build of `loooooooooop`        ***
      |   ***        An effort funded by the Scala Center         ***
      |   ***********************************************************
    """.stripMargin
}
