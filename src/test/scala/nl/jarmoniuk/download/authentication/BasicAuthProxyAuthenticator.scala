package nl.jarmoniuk.download.authentication

import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.security.authentication.{DeferredAuthentication, LoginAuthenticator}
import org.eclipse.jetty.security.{ServerAuthException, UserAuthentication}
import org.eclipse.jetty.server.Authentication
import org.eclipse.jetty.util.security.Constraint

import java.io.IOException
import java.nio.charset.{Charset, StandardCharsets}
import java.util.Base64
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import javax.servlet.{ServletRequest, ServletResponse}


class BasicAuthProxyAuthenticator extends LoginAuthenticator:
  override def getAuthMethod: String = Constraint.__BASIC_AUTH
  @throws[ServerAuthException]
  override def validateRequest(req: ServletRequest,
                               res: ServletResponse,
                               mandatory: Boolean): Authentication =
    val request = req.asInstanceOf[HttpServletRequest]
    val response = res.asInstanceOf[HttpServletResponse]
    try
      if !mandatory then return DeferredAuthentication(this)
      val userAuth = Option.apply(request.getHeader(HttpHeader.PROXY_AUTHORIZATION.asString))
        .map(_ split ' ')
        .filter(a => a.length > 1 && (a(0) equalsIgnoreCase getAuthMethod))
        .map(a => String(Base64.getDecoder.decode(a(1) getBytes StandardCharsets.ISO_8859_1)))
        .map(_ split ':')
        .filter(_.length > 1)
        .flatMap(c => Option apply login(c(0), c(1), request))
        .map(UserAuthentication(getAuthMethod, _))
      if userAuth.isDefined then return userAuth.get
      if DeferredAuthentication.isDeferred(response) then return Authentication.UNAUTHENTICATED
      val value = "Basic realm=" + _loginService.getName
      response.setHeader(HttpHeader.PROXY_AUTHENTICATE.asString, value)
      response.sendError(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED)
      Authentication.SEND_CONTINUE
    catch
      case e: IOException => throw ServerAuthException(e)

  @throws[ServerAuthException]
  override def secureResponse(req: ServletRequest,
                     res: ServletResponse,
                     mandatory: Boolean,
                     validatedUser: Authentication.User): Boolean = true

