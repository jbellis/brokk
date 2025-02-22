package io.github.jbellis.brokk

sealed trait CodeUnit extends Comparable[CodeUnit] {
  def reference: String

  def isClass: Boolean = this match {
    case _: CodeUnit.ClassType => true
    case _ => false
  }

  def isFunction: Boolean = this match {
    case _: CodeUnit.FunctionType => true
    case _ => false
  }

  override def toString: String = this match {
    case CodeUnit.ClassType(ref) => s"CLASS[$ref]"
    case CodeUnit.FunctionType(ref) => s"FUNCTION[$ref]"
    case CodeUnit.FieldType(ref) => s"FIELD[$ref]"
  }

  override def hashCode(): Int = reference.hashCode()

  override def equals(obj: Any): Boolean = obj.isInstanceOf[CodeUnit] && this.reference == obj.asInstanceOf[CodeUnit].reference

  override def compareTo(other: CodeUnit): Int = this.reference.compareTo(other.reference)
}

object CodeUnit {
  case class ClassType(reference: String) extends CodeUnit

  case class FunctionType(reference: String) extends CodeUnit

  case class FieldType(reference: String) extends CodeUnit

  def cls(reference: String): CodeUnit = ClassType(reference)

  def fn(reference: String): CodeUnit = FunctionType(reference)

  def field(reference: String): CodeUnit = FieldType(reference)
}
