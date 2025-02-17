package io.github.jbellis.brokk

import java.io.IOException
import java.nio.file.{Files, Path}

trait BrokkFile {
  def absPath(): Path

  @throws[IOException]
  def read(): String = Files.readString(absPath())

  def exists(): Boolean = {
    Files.exists(absPath())
  }

  /**
   * Just the filename, no path at all
   */
  @scala.annotation.nowarn
  def getFileName: String = {
    absPath().getFileName.toString
  }

  def toString: String
}
