package nl.jarmoniuk.download

import nl.jarmoniuk.download.test.*
import org.apache.maven.plugin.{MojoExecutionException, MojoFailureException}
import org.eclipse.jetty.http.HttpStatus
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.AbstractHandler
import org.scalatest.*
import org.scalatest.flatspec.*
import org.scalatest.matchers.*
import matchers.should.Matchers.*

import java.io.File
import java.net.URI
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import scala.util.Using

object TestUtils {
  import java.lang.reflect.Field

  private def getFieldByNameIncludingSuperclasses(
      fieldName: String,
      clazz: Class[_]
  ): Field = {
    var retValue: Field = null
    try retValue = clazz.getDeclaredField(fieldName)
    catch
      case _: NoSuchFieldException =>
        Option apply clazz.getSuperclass map (c =>
          retValue = getFieldByNameIncludingSuperclasses(fieldName, c)
        )
    retValue
  }

  def setVariableValueToObject(
      o: AnyRef,
      variable: String,
      value: AnyRef
  ): Unit = {
    val field = getFieldByNameIncludingSuperclasses(variable, o.getClass)
    field.setAccessible(true)
    field.set(o, value)
  }
}

class DownloadMojoTest extends AnyFlatSpec {
  import nl.jarmoniuk.download.TestUtils.setVariableValueToObject

  "Plugin" should "raise a MojoFailureException if the server can't be found" in {
    val mojo: DownloadMojo = DownloadMojo()
    setVariableValueToObject(mojo, "uri", URI create "http://bogus-uri")
    setVariableValueToObject(mojo, "outputFile", File("test"))
    a [MojoFailureException] should be thrownBy mojo.execute()
  }

  it should "raise a MojoFailureException if the resource can't be found on the server" in {
    val server = PlainHttpServer { _ =>
      new AbstractHandler:
        override def handle(
            target: String,
            baseRequest: Request,
            request: HttpServletRequest,
            response: HttpServletResponse
        ): Unit =
          response setStatus HttpStatus.NOT_FOUND_404
          baseRequest setHandled true
    }
    server.start()

    val mojo: DownloadMojo = DownloadMojo()
    setVariableValueToObject(mojo, "uri", server.getURI)
    setVariableValueToObject(mojo, "outputFile", File("test"))
    
    try
      a [MojoFailureException] should be thrownBy mojo.execute()
    finally
      server.stop()
  }
}
