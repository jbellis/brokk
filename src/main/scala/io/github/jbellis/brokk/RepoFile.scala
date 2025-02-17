package io.github.jbellis.brokk

import java.io.IOException;
import java.nio.file.Path
import java.nio.file.Files

/**
 * Abstraction for a filename relative to the repo.  This exists to make it less difficult to ensure
 * that different filename objects can be meaningfully compared, unlike bare Paths which may
 * or may not be absolute, or may be relative to the jvm root rather than the repo root.
 */
class RepoFile(private val root: Path, private val relPath: Path) extends BrokkFile {
  def this(root: Path, relName: String) = this(root, Path.of(relName))

  require(root.isAbsolute)
  require(!relPath.isAbsolute)

  def absPath(): Path = root.resolve(relPath)

  @throws[IOException]
  def create(): Unit = {
    Files.createDirectories(absPath().getParent)
    Files.createFile(absPath())
  }

  @throws[IOException]
  def write(st: String): Unit = {
    Files.createDirectories(absPath().getParent)
    Files.writeString(absPath(), st)
  }

  /**
   * Also relative
   */
  def getParent: String = {
    Option(relPath.getParent).map(_.toString).getOrElse("")
  }

  override def equals(o: Any): Boolean = o match {
    case repoFile: RepoFile => root == repoFile.root && relPath == repoFile.relPath
    case _ => false
  }

  override def hashCode: Int = relPath.hashCode
}
