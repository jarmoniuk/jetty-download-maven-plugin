package nl.jarmoniuk.download.service

import nl.jarmoniuk.download.service.{AuthOptions, ProxyOptions}
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugin.logging.Log
import org.eclipse.jetty.client.*
import org.eclipse.jetty.client.api.Authentication.{ANY_REALM, HeaderInfo}
import org.eclipse.jetty.client.api.{Authentication, Response}
import org.eclipse.jetty.client.util.BasicAuthentication
import org.eclipse.jetty.http.HttpHeader.CONTENT_LENGTH
import org.eclipse.jetty.http.{HttpHeader, HttpStatus}
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.ssl.SslContextFactory

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.{Channel, FileChannel}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.security.KeyStore
import java.util.Collections.singletonMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.function.LongConsumer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Promise}
import scala.util.Using.Releasable
import scala.util.{Try, Using}

case class AuthOptions(
    authentication: Authentication,
    preemptive: Boolean = false
)

case class ProxyOptions(uri: URI, authOptions: Option[AuthOptions] = None)

case class DownloadOptions(
    authOptions: Option[AuthOptions] = None,
    proxyOptions: Option[ProxyOptions] = None,
    validateHostName: Boolean = true,
    trustAll: Boolean = false,
    validateCerts: Boolean = true,
    validatePeerCerts: Boolean = true,
    keyStorePath: Option[String] = None,
    keyStorePassword: Option[String] = None,
    trustStorePath: Option[String] = None,
    trustStorePassword: Option[String] = None,
    certAlias: Option[String] = None
)

object DownloadService:
  private class ProgressTracker(using log: Log) extends Runnable:
    private var _contentLength: Option[Long] = None
    private val _bytesDownloaded = new AtomicLong(0L)

    def updateBytesDownloaded(delta: Long): Long =
      _bytesDownloaded.addAndGet(delta)
    def getBytesDownloaded: Long = _bytesDownloaded.get
    def provideContentLength(contentLength: Long): Unit = _contentLength = Some(
      contentLength
    )
    override def run(): Unit =
      val downloaded = _bytesDownloaded.get
      val status = _contentLength
        .map(totalSize =>
          "Downloaded %d bytes - %3d%%"
            .format(downloaded, (100.0 * downloaded / totalSize).round)
        )
        .orElse(Some("Downloaded %d bytes".format(downloaded)))
        .get
      log info status

  private[this] def processOptions(
      options: DownloadOptions
  )(using httpClient: HttpClient, sslContextFactory: SslContextFactory): Unit =
    options.authOptions foreach { authOptions =>
      httpClient.getAuthenticationStore.addAuthentication(
        authOptions.authentication
      )
      if authOptions.preemptive then
        Option
          .apply(
            authOptions.authentication.authenticate(
              null,
              null,
              new HeaderInfo(
                HttpHeader.AUTHORIZATION,
                null,
                singletonMap("charset", StandardCharsets.ISO_8859_1.toString)
              ),
              null
            )
          )
          .foreach(httpClient.getAuthenticationStore.addAuthenticationResult)
    }

    options.proxyOptions foreach { proxyOptions =>
      httpClient.getProxyConfiguration.getProxies.add(
        new HttpProxy(
          new Origin.Address(
            proxyOptions.uri.getHost,
            proxyOptions.uri.getPort
          ),
          "https" equalsIgnoreCase proxyOptions.uri.getScheme
        )
      )
      proxyOptions.authOptions foreach { authOptions =>
        httpClient.getAuthenticationStore.addAuthentication(
          authOptions.authentication
        )
        if authOptions.preemptive then
          Option
            .apply(
              authOptions.authentication.authenticate(
                null,
                null,
                new HeaderInfo(
                  HttpHeader.PROXY_AUTHORIZATION,
                  null,
                  singletonMap("charset", StandardCharsets.ISO_8859_1.toString)
                ),
                null
              )
            )
            .foreach(httpClient.getAuthenticationStore.addAuthenticationResult)
      }
    }

    if !options.validateHostName then
      sslContextFactory.setEndpointIdentificationAlgorithm(null)

    sslContextFactory.setTrustAll(options.trustAll)
    sslContextFactory.setValidateCerts(options.validateCerts)
    sslContextFactory.setValidatePeerCerts(options.validatePeerCerts)
    options.keyStorePath foreach sslContextFactory.setKeyStorePath
    options.keyStorePassword foreach sslContextFactory.setKeyStorePassword
    options.trustStorePath foreach sslContextFactory.setTrustStorePath
    options.trustStorePassword foreach sslContextFactory.setTrustStorePassword
    options.certAlias foreach sslContextFactory.setCertAlias

  def download(
      uri: URI,
      outputPath: Path,
      options: Option[DownloadOptions] = None
  )(using log: Log): Response =
    val progressTracker = new ProgressTracker
    val response = Promise[Response]
    val progressFuture = Executors
      .newScheduledThreadPool(1)
      .scheduleAtFixedRate(progressTracker, 1, 1, TimeUnit.SECONDS)

    val sslContextFactory = SslContextFactory.Client()
    val httpClient = HttpClient(sslContextFactory)

    try
      given HttpClient = httpClient
      given SslContextFactory = sslContextFactory
      options foreach processOptions

      Using.Manager { use =>
        val tempFile = use(Files.createTempFile("downloader-", ".bin"))(Files.deleteIfExists(_))
        val outChannel = use(
          FileChannel.open(
            tempFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          )
        )
        httpClient.start()
        if log.isDebugEnabled then
          log debug "Opening %s for download".format(uri.toString)
        httpClient
          .newRequest(uri)
          .onResponseHeaders { listener =>
            if log.isDebugEnabled then
              listener.getHeaders.stream
                .map(field =>
                  "\t%s: %s\n" format (field.getName, field.getValue)
                )
                .reduce((s1, s2) => s1 + s2)
                .ifPresent(l => log.debug("Headers:\n%s\n" format l))
              (Option apply listener.getHeaders.get(CONTENT_LENGTH))
                .map(_.toLong) foreach progressTracker.provideContentLength
          }
          .onResponseContentDemanded {
            (
                _: Response,
                demand: LongConsumer,
                byteBuffer: ByteBuffer,
                callback: Callback
            ) =>
              try
                val bytesWritten = outChannel.write(byteBuffer)
                progressTracker.updateBytesDownloaded(bytesWritten)

                demand.accept(1)
                callback.succeeded()
              catch
                case e: IOException =>
                  log warn "I/O error while writing data: " + e.getMessage
                  callback.failed(e)
                case t: Throwable =>
                  log warn "Unknown error: " + t.getMessage
                  callback.failed(t)
          }
          .send { result =>
            if log.isDebugEnabled then
              log debug ("Result: %s" format(result.getResponse.toString()))
            if !result.isFailed then response.success(result.getResponse)
            else response.failure(result.getFailure)
          }
        Await.result(response.future, Duration.Inf)
        val responseVal = response.future.value.get.get
        if responseVal.getStatus == HttpStatus.OK_200 then
          log info ("Downloaded %d bytes." format progressTracker.getBytesDownloaded)
          Files.copy(tempFile, outputPath, StandardCopyOption.REPLACE_EXISTING)
        else throw DownloadServiceException(responseVal.getStatus, responseVal.getReason())
        responseVal
      }.get
    catch
      case e: Exception =>
        log error "Caught exception %s".format(e.toString)
        Option apply e.getCause foreach (e =>
          e.getStackTrace to LazyList map (_.toString) foreach (log info "\t" + _))
        throw e
    finally
      progressFuture.cancel(false)
      // disabling since this causes a java.nio.channels.ClosedChannelException from FillInterest.onClose
      // if httpClient.isRunning then httpClient.stop()
      
