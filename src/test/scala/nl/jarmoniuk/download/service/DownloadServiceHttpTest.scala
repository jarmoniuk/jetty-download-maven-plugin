package nl.jarmoniuk.download.service

import nl.jarmoniuk.download.authentication.BasicAuthProxyAuthenticator
import nl.jarmoniuk.download.service.DownloadServiceTestBase.helloWorldHandler
import nl.jarmoniuk.download.service.{DownloadServiceTestBase, AuthOptions, DownloadOptions, DownloadService, ProxyOptions}
import nl.jarmoniuk.download.util.SimpleTextHandler
import org.apache.maven.plugin.logging.{Log, SystemStreamLog}
import org.eclipse.jetty.client.api.Authentication
import org.eclipse.jetty.client.api.Authentication.ANY_REALM
import org.eclipse.jetty.client.util.BasicAuthentication
import org.eclipse.jetty.client.{HttpProxy, Origin, ProxyConfiguration, api}
import org.eclipse.jetty.http.HttpHeader.WWW_AUTHENTICATE
import org.eclipse.jetty.http.{HttpHeader, HttpStatus}
import org.eclipse.jetty.proxy.{ConnectHandler, ProxyServlet}
import org.eclipse.jetty.security.*
import org.eclipse.jetty.security.authentication.{BasicAuthenticator, LoginAuthenticator}
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.{AbstractHandler, ContextHandler, ErrorHandler, HandlerWrapper}
import org.eclipse.jetty.servlet.{FilterHolder, ServletContextHandler, ServletHolder}
import org.eclipse.jetty.util.security.{Constraint, Password}
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.{http, server}
import org.scalatest.flatspec.*
import org.scalatest.matchers.should.*

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util
import java.util.Base64
import javax.servlet.*
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import scala.io.Source
import scala.util.Using
import scala.util.Using.Releasable

class DownloadServiceHttpTest extends DownloadServiceTestBase with Matchers:

  private class PlainHttpServer(val handler: Server => Handler) extends Server:
    private lazy val serverConnector = ServerConnector(this, 1, 1)
    private[this] def init(): Unit =
      addConnector(serverConnector)
      setHandler(handler(this))
    init()

  private object PlainHttpServer:
    given Releasable[PlainHttpServer] = _.stop()

  "a client" should "download a \"Hello, world!\" message" in {
    Using.Manager { use =>
      val server = use(PlainHttpServer(_ => helloWorldHandler))
      val tempFile = use(new TempFile)
      server.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path)
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }

  it should "download a large file" in {
    lazy val contentLength = 0x30_000
    Using.Manager { use =>
      val server = use(PlainHttpServer(_ => new AbstractHandler:
        override def handle(target: String,
                            baseRequest: Request,
                            request: HttpServletRequest,
                            response: HttpServletResponse): Unit =
          response setStatus HttpStatus.OK_200
          response setHeader(HttpHeader.CONTENT_LENGTH.asString, contentLength.toString)
          response setContentType "text/plain"
          for
            i <- 1 to contentLength
          do
            response.getWriter.write(".")
          baseRequest setHandled true
      ))
      val tempFile = use(new TempFile)
      server.start()
      DownloadService.download(server.getURI, tempFile.path).getStatus shouldBe HttpStatus.OK_200
    }
  }

  it should "return code 500 if the server returns code 500" in {
    Using(PlainHttpServer(_ => new AbstractHandler:
      override def handle(target: String,
                          baseRequest: Request,
                          request: HttpServletRequest,
                          response: HttpServletResponse): Unit =
        response setStatus HttpStatus.INTERNAL_SERVER_ERROR_500
        baseRequest setHandled true
    )) { server =>
      server.start()
      DownloadService.download(server.getURI, Paths get "").getStatus shouldBe HttpStatus.INTERNAL_SERVER_ERROR_500
    }
  }

  it should "return code 401 if the request is not authenticated" in {
    Using(PlainHttpServer(s => secureHandler(helloWorldHandler, s))) { server =>
      server.start()
      DownloadService.download(server.getURI, Paths get "").getStatus shouldBe HttpStatus.UNAUTHORIZED_401
    }
  }

  it should "download a \"Hello, world!\" message with basic authentication" in {
    Using.Manager { use =>
      val server = use(PlainHttpServer(s => secureHandler(helloWorldHandler, s)))
      val tempFile = use(new TempFile)
      server.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path, Option apply DownloadOptions(
        authOptions = Option apply AuthOptions(
          authentication = BasicAuthentication(server.getURI, "TestRealm", "user", "password")))
      )
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }

  it should "return code 401 with basic authentication and wrong realm" in {
    Using(PlainHttpServer(s => secureHandler(helloWorldHandler, s))) { server =>
      server.start()
      val httpResponse = DownloadService.download(server.getURI, Paths get "",
        Option apply DownloadOptions(authOptions = Option apply AuthOptions(
          authentication = BasicAuthentication(server.getURI, "WrongRealm", "user", "password")))
      )
      httpResponse.getStatus shouldBe HttpStatus.UNAUTHORIZED_401
    }
  }

  it should "download a \"Hello, world!\" message with basic authentication and no realm provided" in {
    Using.Manager { use =>
      val server = use(PlainHttpServer(s => secureHandler(helloWorldHandler, s)))
      val tempFile = use(new TempFile)
      server.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path,
        Option apply DownloadOptions(authOptions = Option apply AuthOptions(
          authentication = BasicAuthentication(server.getURI, ANY_REALM, "user", "password")))
      )
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }

  it should "download a \"Hello, world!\" message with preemptive basic authentication" in {
    Using.Manager { use =>
      val handler = new HandlerWrapper:
        override def handle(target: String,
                            baseRequest: Request,
                            request: HttpServletRequest,
                            response: HttpServletResponse): Unit =
          if request.getHeader(HttpHeader.AUTHORIZATION.asString) == null then
            response.setStatus(HttpStatus.FORBIDDEN_403)
            baseRequest.setHandled(true)
          else
            super.handle(target, baseRequest, request, response)
      val server = use(PlainHttpServer(_ => handler))
      handler.setHandler(secureHandler(helloWorldHandler, server))
      val tempFile = use(new TempFile)
      server.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path,
        Option apply DownloadOptions(authOptions = Option apply AuthOptions(preemptive = true,
          authentication = BasicAuthentication(server.getURI, ANY_REALM, "user", "password"))))
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }

  it should "download a \"Hello, world!\" message via a proxy" in {
    Using.Manager { use =>
      val server = use(PlainHttpServer(_ => helloWorldHandler))
      val proxy = use(PlainHttpProxy())
      val tempFile = use(new TempFile)
      server.start()
      proxy.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path,
        Option apply DownloadOptions(proxyOptions = Option apply ProxyOptions(
          uri = URI.create("http://localhost:" + proxy.connector.getLocalPort))
        )
      )
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }

  it should "download a \"Hello, world!\" message via a proxy with basic authentication" in {
    Using.Manager { use =>
      val server = use(PlainHttpServer(_ => helloWorldHandler))
      val proxy = use(PlainHttpProxy())
      val tempFile = use(new TempFile)
      given LoginAuthenticator = new BasicAuthProxyAuthenticator
      proxy.context.setHandler(secureHandler(null, proxy))
      server.start()
      proxy.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path,
        Option apply DownloadOptions(proxyOptions = Option apply ProxyOptions(
          uri = URI.create("http://localhost:" + proxy.connector.getLocalPort),
          authOptions = Option apply AuthOptions(
            authentication = BasicAuthentication(URI.create("http://localhost:" + proxy.connector.getLocalPort),
              "TestRealm", "user", "password")))
        )
      )
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }

  it should "download a \"Hello, world!\" message via an https proxy" in {
    Using.Manager { use =>
      val server = use(PlainHttpServer(_ => helloWorldHandler))
      val proxy = use(HttpsProxy())
      val tempFile = use(new TempFile)
      server.start()
      proxy.start()
      val httpResponse = DownloadService.download(server.getURI, tempFile.path,
        Option apply DownloadOptions(proxyOptions = Option apply ProxyOptions(
          uri = URI.create("https://localhost:" + proxy.connector.getLocalPort)
        ), trustAll = true)
      )
      httpResponse.getStatus shouldBe HttpStatus.OK_200
      String.join("", Files.readAllLines(tempFile.path)) shouldEqual "Hello, world!"
    }
  }