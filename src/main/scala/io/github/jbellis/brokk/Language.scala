package io.github.jbellis.brokk

sealed trait Language

object Language {
  case object Java extends Language

  case object Python extends Language
}
