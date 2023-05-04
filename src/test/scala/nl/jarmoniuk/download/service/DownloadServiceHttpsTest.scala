package nl.jarmoniuk.download.service

import nl.jarmoniuk.download.service.DownloadServiceTestBase.helloWorldHandler
import nl.jarmoniuk.download.service.{DownloadServiceTestBase, AuthOptions, DownloadOptions, DownloadService}
import nl.jarmoniuk.download.util.SimpleTextHandler
import org.apache.maven.plugin.logging.{Log, SystemStreamLog}
import org.eclipse.jetty.client.HttpProxy
import org.eclipse.jetty.client.api.Authentication.ANY_REALM
import org.eclipse.jetty.client.util.BasicAuthentication
import org.eclipse.jetty.http
import org.eclipse.jetty.http.HttpHeader.WWW_AUTHENTICATE
import org.eclipse.jetty.http.{HttpHeader, HttpStatus}
import org.eclipse.jetty.proxy.ProxyServlet
import org.eclipse.jetty.security.{ConstraintSecurityHandler, HashLoginService, UserStore}
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.eclipse.jetty.util.security.Password
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.scalatest.flatspec.*
import org.scalatest.matchers.should.*

import java.net.URI
import java.nio.file.{Files, Path}
import scala.io.Source
import scala.util.Using
import scala.util.Using.{Releasable, resource}

class DownloadServiceHttpsTest extends DownloadServiceTestBase with Matchers:

  private class HttpsServer(val handler: Server => Handler) extends Server with Releasable[HttpsServer]:
    override def release(resource: HttpsServer = this): Unit = resource.stop()
    private[this] def init(): Unit =
      val sslContextFactory = SslContextFactory.Server()
      sslContextFactory.setKeyStorePath(getClass.getResource("/keystore-testhost.jks").getPath)
      sslContextFactory.setKeyStorePassword("password")

      val src = new SecureRequestCustomizer
      src.setSniHostCheck(false)

      val httpsConfig = new HttpConfiguration
      httpsConfig.addCustomizer(src)

      val serverConnector = ServerConnector(this, sslContextFactory, new HttpConnectionFactory(httpsConfig))
      addConnector(serverConnector)
      setHandler(handler(this))
    init()

  private object HttpsServer:
    given Releasable[HttpsServer] = _.stop()

  "a client" should "download a \"Hello, world!\" message" in {
    Using.Manager { use =>
      val server = use(HttpsServer(_ => helloWorldHandler))
      val tempFile = use(new TempFile)
      server.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path,
        Option apply DownloadOptions(trustAll = true))
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }

  it should "download a \"Hello, world!\" message with basic authentication" in {
    Using.Manager { use =>
      val server = use(HttpsServer(secureHandler(helloWorldHandler, _)))
      val tempFile = use(new TempFile)
      server.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path,
        Option apply DownloadOptions(
          authOptions = Option apply AuthOptions(
            authentication = BasicAuthentication(server.getURI, ANY_REALM, "user", "password")),
          trustAll = true))
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }

  it should "download a \"Hello, world!\" message with a wrong cn record and disabled hostname verification" in {
    Using.Manager { use =>
      val server = use(HttpsServer(secureHandler(helloWorldHandler, _)))
      val tempFile = use(new TempFile)
      server.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path,
        Option apply DownloadOptions(
          authOptions = Option apply AuthOptions(
            authentication = BasicAuthentication(server.getURI, ANY_REALM, "user", "password")),
          validateHostName = false,
          validatePeerCerts = false, validateCerts = false,
          keyStorePath = Option apply getClass.getResource("/keystore-testhost.jks").getPath,
          keyStorePassword = Option apply "password"
        ))
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }

  it should "download a \"Hello, world!\" message with client authentication" in {
    Using.Manager { use =>
      val server = use(HttpsServer(secureHandler(helloWorldHandler, _)))
      val tempFile = use(new TempFile)
      server.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path,
        Option apply DownloadOptions(
          authOptions = Option apply AuthOptions(
            authentication = BasicAuthentication(server.getURI, ANY_REALM, "user",
              "password")),
          validateHostName = false,
          validatePeerCerts = false, validateCerts = false,
          keyStorePath = Option apply getClass.getResource("/keystore-testhost.jks").getPath,
          keyStorePassword = Option apply "password",
          trustStorePath = Option apply getClass.getResource("/keystore-testhost.jks").getPath,
          trustStorePassword = Option apply "password"
        ))
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }
