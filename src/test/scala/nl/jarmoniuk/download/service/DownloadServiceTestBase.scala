package nl.jarmoniuk.download.service

import nl.jarmoniuk.download.authentication.BasicAuthProxyAuthenticator
import nl.jarmoniuk.download.test.SimpleTextHandler
import org.apache.maven.plugin.logging.{Log, SystemStreamLog}
import org.eclipse.jetty.proxy.ProxyServlet
import org.eclipse.jetty.security.*
import org.eclipse.jetty.security.authentication.{BasicAuthenticator, LoginAuthenticator}
import org.eclipse.jetty.server.*
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.eclipse.jetty.util.security.Constraint
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.file.{Files, Path}
import scala.util.Using.Releasable
import nl.jarmoniuk.download.test.*

object DownloadServiceTestBase:
  lazy val helloWorldHandler = SimpleTextHandler("Hello, world!")

abstract class DownloadServiceTestBase extends AnyFlatSpec with BeforeAndAfterEach:
  import DownloadServiceTestBase.*

  private lazy val _log = new SystemStreamLog
  protected given Log = _log
  protected given LoginAuthenticator = new BasicAuthenticator

  class HttpsProxy extends Server:
    private var _connector: ServerConnector = _
    def connector: ServerConnector = _connector
    private[this] def init(): Unit =
      val sslContextFactory = SslContextFactory.Server()
      sslContextFactory.setKeyStorePath(getClass.getResource("/keystore-testhost.jks").getPath)
      sslContextFactory.setKeyStorePassword("password")

      val src = new SecureRequestCustomizer
      src.setSniHostCheck(false)

      val httpsConfig = new HttpConfiguration
      httpsConfig.addCustomizer(src)

      _connector = ServerConnector(this, sslContextFactory)
      addConnector(_connector)

      ServletContextHandler(this, "/", ServletContextHandler.SESSIONS)
        .addServlet(ServletHolder(classOf[ProxyServlet]), "/*")
    init()

  object HttpsProxy:
    given Releasable[HttpsProxy] = _.stop()

  def secureHandler(handler: Handler = null, server: Server)(using authenticator: LoginAuthenticator): SecurityHandler =
    val loginService = HashLoginService("TestRealm", getClass.getClassLoader
      .getResource("testrealm.properties").toExternalForm)
    server.addBean(loginService)

    val securityHandler = new ConstraintSecurityHandler
    securityHandler.setAuthenticator(authenticator)
    securityHandler.setLoginService(loginService)
    if handler != null then securityHandler.setHandler(handler)

    val constraint = Constraint("auth", "**") //allow any authenticated user
    constraint.setAuthenticate(true)

    val constraintMapping = new ConstraintMapping
    constraintMapping.setPathSpec("/*")
    constraintMapping.setConstraint(constraint)
    securityHandler.addConstraintMapping(constraintMapping)

    securityHandler
