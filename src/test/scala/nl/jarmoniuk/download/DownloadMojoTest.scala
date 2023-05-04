package nl.jarmoniuk.download

import org.scalatest.*
import flatspec.*
import matchers.*

import java.net.URI
import java.io.File
import org.apache.maven.plugin.MojoExecutionException

object TestUtils {
  import java.lang.reflect.Field

  private def getFieldByNameIncludingSuperclasses(fieldName: String, clazz: Class[_]): Field = {
    var retValue: Field = null
    try retValue = clazz.getDeclaredField(fieldName)
    catch
      case _: NoSuchFieldException =>
        Option apply clazz.getSuperclass map (c => retValue = getFieldByNameIncludingSuperclasses(fieldName, c))
    retValue
  }

  def setVariableValueToObject(o: AnyRef, variable: String, value: AnyRef): Unit = {
    val field = getFieldByNameIncludingSuperclasses(variable, o.getClass)
    field.setAccessible(true)
    field.set(o, value)
  }
}

class DownloadMojoTest extends AnyFlatSpec {
  import nl.jarmoniuk.download.TestUtils.setVariableValueToObject

  "The plugin" should "raise a MavenExecutionException if the uri can't be downloaded" in {
    val mojo: DownloadMojo = DownloadMojo()
    setVariableValueToObject(mojo, "uri", URI create "bogus-uri")
    setVariableValueToObject(mojo, "outputFile", File("test"))
    assertThrows[MojoExecutionException] {
      mojo.execute()
    }
  }
}
