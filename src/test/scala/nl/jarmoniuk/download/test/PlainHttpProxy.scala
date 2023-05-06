package nl.jarmoniuk.download.test

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.servlet.ServletContextHandler
import scala.util.Using.Releasable
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.proxy.ProxyServlet

class PlainHttpProxy extends Server:
    private lazy val _connector = ServerConnector(this, 1, 1)
    private lazy val _context = ServletContextHandler(this, "/", ServletContextHandler.SESSIONS)
    def connector: ServerConnector = _connector
    def context: ServletContextHandler = _context
    private[this] def init(): Unit =
      addConnector(_connector)
      _context.addServlet(ServletHolder(classOf[ProxyServlet]), "/*")
    init()

object PlainHttpProxy:
    given Releasable[PlainHttpProxy] = _.stop()
