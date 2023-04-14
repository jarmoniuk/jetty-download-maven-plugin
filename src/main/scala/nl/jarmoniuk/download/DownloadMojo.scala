package nl.jarmoniuk.download

import nl.jarmoniuk.download.DownloadMojo.basicAuthenticationFor
import nl.jarmoniuk.download.service.{AuthOptions, DownloadOptions, DownloadService, ProxyOptions}
import org.apache.maven.plugin.logging.Log
import org.apache.maven.plugin.{AbstractMojo, MojoExecutionException, MojoFailureException}
import org.apache.maven.plugins.annotations.LifecyclePhase.PROCESS_RESOURCES
import org.apache.maven.plugins.annotations.{Mojo, Parameter}
import org.eclipse.jetty.client.api.{ContentResponse, Response}
import org.eclipse.jetty.client.api.Authentication.ANY_REALM
import org.eclipse.jetty.client.util.BasicAuthentication
import org.eclipse.jetty.client.{HttpClient, HttpResponse}
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.http.HttpHeader.CONTENT_LENGTH
import org.eclipse.jetty.util.Callback

import java.io.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.{Channel, FileChannel}
import java.nio.file.{Files, OpenOption, Path, StandardOpenOption}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.function.LongConsumer
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps

/**
 * Downloads the requested resource.
 *
 * @author Andrzej Jarmoniuk
 * @since 0.1.0
 */
@Mojo(name = "download", defaultPhase = PROCESS_RESOURCES, threadSafe = true)
class DownloadMojo extends AbstractMojo:

  /**
   * URI of the resource do download
   * @since 0.1.0
   */
  @Parameter(alias = "uri", property = "uri", required = true)
  private var uri: URI = _

  /**
   * File to which the downloaded contents should be written
   */
  @Parameter(property = "outputFile")
  private var outputFile: File = _

  /**
   * Authentication realm (optional)
   */
  @Parameter(property = "realm")
  private var realm: String = _

  /**
   * Username for basic authentication (optional)
   */
  @Parameter(property = "username")
  private var username: String = _

  /**
   * Password for basic authentication (optional)
   */
  @Parameter(property = "password")
  private var password: String = _

  /**
   * Whether to use pre-emptive authentication. Has no meaning if no authentication is used (optional)
   */
  @Parameter(property = "preemptive", defaultValue = "false")
  private var preemptive: Boolean = false

  /**
   * Proxy uri, consisting of scheme, host name, and port, e.g. https://proxy:8445 (optional)
   */
  @Parameter(property = "proxyUri")
  private var proxyUri: URI = _

  /**
   * Realm for basic proxy authentication (optional)
   */
  @Parameter(property = "proxyRealm")
  private var proxyRealm: String = _

  /**
   * Username for basic proxy authentication (optional)
   */
  @Parameter(property = "proxyUsername")
  private var proxyUsername: String = _

  /**
   * Password for basic proxy authentication (optional)
   */
  @Parameter(property = "proxyPassword")
  private var proxyPassword: String = _

  /**
   * Whether to use pre-emptive authentication for authenticating with the proxy server (optional)
   */
  @Parameter(property = "proxyPreemptive", defaultValue = "false")
  private var proxyPreemptive: Boolean = false

  /**
   * Whether to trust all certificates if there is no keystore or truststore (default is false)
   */
  @Parameter(property = "trustAll")
  private var trustAll: Boolean = false

  /**
   * Whether host name should be checked against CN of the certificate (default is true)
   */
  @Parameter(property = "validateHostName", defaultValue = "true")
  private var validateHostName: Boolean = true

  /**
   * Whether certificates should be validated (default is true)
   */
  @Parameter(property = "validateCerts", defaultValue = "true")
  private var validateCerts: Boolean = true

  /**
   * Whether peer certificates should be validated (default is true)
   */
  @Parameter(property = "validatePeerCerts", defaultValue = "true")
  private var validatePeerCerts: Boolean = true

  /**
   * Path or URI of the keystore (optional)
   */
  @Parameter(property = "keyStorePath")
  private var keyStorePath: String = _

  /**
   * Keystore password (optional)
   */
  @Parameter(property = "keyStorePassword")
  private var keyStorePassword: String = _

  /**
   * Path or URI of the truststore (optional)
   */
  @Parameter(property = "trustStorePath")
  private var trustStorePath: String = _

  /**
   * Truststore password (optional)
   */
  @Parameter(property = "trustStorePath")
  private var trustStorePassword: String = _

  /**
   * Default certificate alias (optional)
   */
  @Parameter(property = "certAlias")
  private var certAlias: String = _

  given log: Log = getLog

  override def execute(): Unit =
    try
      DownloadService.download(uri, outputFile.toPath, Option apply DownloadOptions(
        authOptions = Option apply username map (_.trim) filter (_.nonEmpty) map { username =>
          AuthOptions(
            authentication = basicAuthenticationFor(uri, realm, username, password),
            preemptive = preemptive
          )
        },
        proxyOptions = Option apply proxyUri filter (_.toString.trim.nonEmpty) map { proxyUri =>
          ProxyOptions(
            uri = proxyUri,
            authOptions = if proxyUsername.trim.nonEmpty then Option apply AuthOptions(
              authentication = basicAuthenticationFor(proxyUri, proxyRealm, proxyUsername, proxyPassword),
              preemptive = proxyPreemptive
            ) else None,
          )
        },
        validateHostName = validateHostName,
        validateCerts = validateCerts,
        validatePeerCerts = validatePeerCerts,
        trustAll = trustAll,
        keyStorePath = Option apply keyStorePath map (_.trim) filter (_.nonEmpty),
        keyStorePassword = Option apply keyStorePassword map (_.trim) filter (_.nonEmpty),
        trustStorePath = Option apply trustStorePath map (_.trim) filter (_.nonEmpty),
        trustStorePassword = Option apply trustStorePassword map (_.trim) filter (_.nonEmpty),
        certAlias = Option apply certAlias map (_.trim) filter (_.nonEmpty)
      ))
    catch
      case e: Exception => throw MojoFailureException(e)

object DownloadMojo:
  private def basicAuthenticationFor(uri: URI, realm: String, username: String, password: String) =
    BasicAuthentication(uri,
      if realm.trim.nonEmpty then realm.trim else ANY_REALM,
      username.trim,
      password.trim
    )
