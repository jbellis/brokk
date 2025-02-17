package io.github.jbellis.brokk

import java.nio.file.Path

/**
 * A BrokkFile that represents a file not relative to any repo, just specified by its absolute path.
 */
class ExternalFile(private val path: Path) extends BrokkFile {
  require(path.isAbsolute)

  def absPath(): Path = path

  override def toString: String = path.toString
}
