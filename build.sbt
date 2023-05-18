val jettyVersion = "9.4.51.v20230217"
val mavenVersion = "3.3.9"

version := "1.1.0-SNAPSHOT"

ThisBuild / name := "Jetty Download Maven Plugin"
ThisBuild / organization := "nl.jarmoniuk"
ThisBuild / organizationName := "Andrzej Jarmoniuk"
ThisBuild / versionScheme := Option apply "semver-spec"
ThisBuild / description := "Simple plugin for downloading resources based on Eclipse Jetty"
scalaVersion := "3.2.2"
ThisBuild / homepage := Option apply url("https://www.jarmoniuk.nl/jetty-download/")
ThisBuild / licenses += "Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
ThisBuild / developers := List(
  Developer("jarmoni", "Andrzej Jarmoniuk", "gh at jarmoniuk.nl", url("https://github.com/ajarmoniuk"))
)
ThisBuild / scmInfo := Option apply ScmInfo(
  url("https://github.com/ajarmoniuk/jetty-download-maven-plugin/tree/master"),
  "scm:git:https://github.com/ajarmoniuk/jetty-download-maven-plugin.git",
  "scm:git@github.com:ajarmoniuk/jetty-download-maven-plugin.git"
)
publishM2Configuration := publishM2Configuration.value.withOverwrite(true)
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishArtifact := true
ThisBuild / sonatypeProfileName := "jarmoni"
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org"
  if (isSnapshot.value) Some("snapshots" at nexus + "/content/repositories/snapshots")
  else Some("releases" at nexus + "/service/local/staging/deploy/maven2")
}
ThisBuild / javacOptions ++= Seq("-source", "1.8", "-target", "1.8") 
ThisBuild / test / parallelExecution := false
ThisBuild / crossPaths := false
ThisBuild / pomPostProcess := {
  import scala.xml.{Node, NodeSeq, *}
  import scala.xml.transform.*

  new RuleTransformer(new RewriteRule {
    override def transform(n: Node): NodeSeq = n match {
      case e: Elem if e != null && e.label == "packaging" => <packaging>maven-plugin</packaging>
      case _ => n
    }
  }).transform(_).head
}
ThisBuild / pomExtra :=
  <prerequisites>
    <maven>
      {mavenVersion}
    </maven>
  </prerequisites>


doc / target := target.value / "site/javadoc"
Global / doc / scalacOptions := Seq("-private", "-groups")

// site
enablePlugins(SiteScaladocPlugin, PreprocessPlugin, AsciidoctorPlugin, SitePreviewPlugin)
Preprocess / preprocessIncludeFilter := "*.adoc"
Preprocess / preprocessVars := Map(
  "groupId" -> organization.value,
  "artifactId" -> normalizedName.value,
  "version" -> version.value,
  "jettyVersion" -> jettyVersion,
)
Preprocess / sourceDirectory := sourceDirectory.value / "site"
Preprocess / target := target.value / "preprocess"
Asciidoctor / sourceDirectory := target.value / "preprocess"
Asciidoctor / siteSubdirName := ""
SiteScaladoc / siteSubdirName := "javadoc"


lazy val root = project in file(".") settings (
  name := "Jetty Download Maven Plugin",
  normalizedName := "jetty-download-maven-plugin",
  libraryDependencies ++= Seq(
    "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % "3.8.1" % Provided,
    "org.apache.maven" % "maven-plugin-api" % "3.9.1" % Provided,
    "org.eclipse.jetty" % "jetty-client" % jettyVersion,
    "javax.inject" % "javax.inject" % "1" % Provided,

    "org.slf4j" % "slf4j-simple" % "2.0.7" % Test,
    "org.eclipse.jetty" % "jetty-server" % jettyVersion % Test,
    "org.eclipse.jetty" % "jetty-security" % jettyVersion % Test,
    "org.eclipse.jetty" % "jetty-proxy" % jettyVersion % Test,
    "org.eclipse.jetty" % "jetty-servlet" % jettyVersion % Test,
    "org.scalatest" %% "scalatest" % "3.2.15" % Test,
  )
)

lazy val mavenTests = taskKey[Unit]("Execute integration tests using Maven")
Test / mavenTests := Resolvers.run("mvn", "verify", "-f", "it/pom.xml",
    "-DpluginGroupId=" + (root / organization value),
    "-DpluginArtifactId=" + (root / normalizedName value),
    "-DpluginVersion=" + (root / version value))
