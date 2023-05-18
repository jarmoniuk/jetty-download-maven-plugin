package nl.jarmoniuk.download.test

import scala.util.Using.Releasable
import java.nio.file.{Path, Files}

class TempFile(val prefix: String = "download"):
    private lazy val _path = Files.createTempFile(prefix + "-", ".tmp")
    def path: Path = _path

object TempFile:
    given Releasable[TempFile] = f => Files.deleteIfExists(f._path)
