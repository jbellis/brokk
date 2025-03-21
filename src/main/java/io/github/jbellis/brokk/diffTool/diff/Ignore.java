package io.github.jbellis.brokk.diffTool.diff;


public class Ignore {
  static public final Ignore NULL_IGNORE = new Ignore();

  public boolean ignoreWhitespaceAtBegin;
  public boolean ignoreWhitespaceInBetween;
  public boolean ignoreWhitespaceAtEnd;
  public boolean ignoreEOL;
  public boolean ignoreBlankLines;
  public boolean ignoreCase;
  // Transient:
  public boolean ignore;
  public boolean ignoreWhitespace;

  public Ignore()
  {
    this(false, false, false);
  }

  public Ignore(boolean ignoreWhitespace, boolean ignoreEOL,
      boolean ignoreBlankLines)
  {
    this(ignoreWhitespace, ignoreWhitespace, ignoreWhitespace, ignoreEOL,
         ignoreBlankLines, false);
  }

  public Ignore(boolean ignoreWhitespaceAtBegin,
      boolean ignoreWhitespaceInBetween, boolean ignoreWhitespaceAtEnd,
      boolean ignoreEOL, boolean ignoreBlankLines, boolean ignoreCase)
  {
    this.ignoreWhitespaceAtBegin = ignoreWhitespaceAtBegin;
    this.ignoreWhitespaceInBetween = ignoreWhitespaceInBetween;
    this.ignoreWhitespaceAtEnd = ignoreWhitespaceAtEnd;
    this.ignoreEOL = ignoreEOL;
    this.ignoreBlankLines = ignoreBlankLines;
    this.ignoreCase = ignoreCase;

    init();
  }

  private void init()
  {
    this.ignore = (ignoreWhitespaceAtBegin || ignoreWhitespaceInBetween
                   || ignoreWhitespaceAtEnd || ignoreEOL || ignoreBlankLines || ignoreCase);
    this.ignoreWhitespace = (ignoreWhitespaceAtBegin
                             || ignoreWhitespaceInBetween || ignoreWhitespaceAtEnd);
  }

  @Override
  public String toString()
  {
    return "ignore: " + (!ignore ? "nothing" : "")
           + (ignoreWhitespaceAtBegin ? "whitespace[begin] " : "")
           + (ignoreWhitespaceInBetween ? "whitespace[in between] " : "")
           + (ignoreWhitespaceAtEnd ? "whitespace[end] " : "")
           + (ignoreEOL ? "eol " : "")
           + (ignoreBlankLines ? "blanklines " : "")
           + (ignoreCase ? "case " : "");
  }
}
