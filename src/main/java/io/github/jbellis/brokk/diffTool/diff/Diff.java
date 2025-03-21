
package io.github.jbellis.brokk.diffTool.diff;


public class Diff
{
  /** The standard line separator. */
  public static final String NL = System.lineSeparator();

  /** The original sequence. */
  protected final Object[] orig;

  /** The differencing algorithm to use. */
  protected DiffAlgorithm algorithm;

  public Diff(Object[] original, DiffAlgorithm algorithm)
  {
    if (original == null)
    {
      throw new IllegalArgumentException();
    }

    this.orig = original;
    if (algorithm != null)
    {
      this.algorithm = algorithm;
    }
    else
    {
      this.algorithm = defaultAlgorithm();
    }
  }

  protected DiffAlgorithm defaultAlgorithm()
  {
    return algorithm;
  }

}
