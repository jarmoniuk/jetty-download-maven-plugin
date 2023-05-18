package nl.jarmoniuk.download.test

import org.eclipse.jetty.http.{HttpHeader, HttpStatus}
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler

import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

class SimpleTextHandler(val content: String) extends AbstractHandler:
  override def handle(target: String,
                      baseRequest: Request,
                      request: HttpServletRequest,
                      response: HttpServletResponse): Unit =
    response setStatus HttpStatus.OK_200
    response setHeader(HttpHeader.CONTENT_LENGTH.asString, content.length.toString)
    response setContentType "text/plain"
    response.getWriter write content
    baseRequest setHandled true