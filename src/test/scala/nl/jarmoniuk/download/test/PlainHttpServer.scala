package nl.jarmoniuk.download.test

import org.eclipse.jetty.server.*
import scala.util.Using.Releasable

class PlainHttpServer(val handler: Server => Handler) extends Server:
    private lazy val serverConnector = ServerConnector(this, 1, 1)
    private[this] def init(): Unit =
        addConnector(serverConnector)
        setHandler(handler(this))
    init()

object PlainHttpServer:
    given Releasable[PlainHttpServer] = _.stop()
