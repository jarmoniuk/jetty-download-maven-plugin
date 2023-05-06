package nl.jarmoniuk.download.test

import org.eclipse.jetty.server.*
import scala.util.Using.Releasable
import org.eclipse.jetty.util.ssl.SslContextFactory

class HttpsServer(val handler: Server => Handler) extends Server with Releasable[HttpsServer]:
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

object HttpsServer:
    given Releasable[HttpsServer] = _.stop()

