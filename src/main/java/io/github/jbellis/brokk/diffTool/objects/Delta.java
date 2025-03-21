
package io.github.jbellis.brokk.diffTool.objects;


import io.github.jbellis.brokk.diffTool.diff.Diff;

/**
 * Holds a "delta" difference between to revisions of a text.
 */
public abstract class Delta
{
  protected Chunk original;
  protected Chunk revised;
  static Class<?>[][] DeltaClass;

  static
  {
    DeltaClass = new Class[2][2];
    try
    {
      DeltaClass[0][0] = ChangeDelta.class;
      DeltaClass[0][1] = AddDelta.class;
      DeltaClass[1][0] =DeleteDelta.class;
      DeltaClass[1][1] = ChangeDelta.class;
    }
    catch (Throwable throwable)
    {
      throwable.printStackTrace();
    }
  }

  /**
   * Creates an uninitialized delta.
   */
  protected Delta()
  {
  }


  /**
   * Initializaes the delta with the given chunks from the original
   * and revised texts.
   */
  protected void init(Chunk orig, Chunk rev)
  {
    original = orig;
    revised = rev;
  }

  /**
   * Converts this delta into its Unix diff style string representation.
   * @param s a {@link StringBuffer StringBuffer} to which the string
   * representation will be appended.
   */
  public void toString(StringBuffer s)
  {
    original.rangeString(s);
    s.append("x");
    revised.rangeString(s);
    s.append(Diff.NL);
    original.toString(s, "> ", "\n");
    s.append("---");
    s.append(Diff.NL);
    revised.toString(s, "< ", "\n");
  }

  /**
   * Accessor method to return the chunk representing the original
   * sequence of items
   *
   * @return the original sequence
   */
  public Chunk getOriginal()
  {
    return original;
  }

  /**
   * Accessor method to return the chunk representing the updated
   * sequence of items.
   *
   * @return the updated sequence
   */
  public Chunk getRevised()
  {
    return revised;
  }

}
