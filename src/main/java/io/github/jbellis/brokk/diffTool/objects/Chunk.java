
package io.github.jbellis.brokk.diffTool.objects;


import java.util.List;

/**
 * Holds a information about a parrt of the text involved in
 * a differencing or patching operation.
 */
public class Chunk
{
  protected int anchor;
  protected int count;
  protected List<?> chunk;

  /**
   * Creates a chunk that doesn't copy the original text.
   * @param pos the start position in the text.
   * @param count the size of the chunk.
   */
  public Chunk(int pos, int count)
  {
    this.anchor = pos;
    this.count = (Math.max(count, 0));
  }

  /**
   * Returns the anchor position of the chunk.
   * @return the anchor position.
   */
  public int anchor()
  {
    return anchor;
  }

  /**
   * Returns the size of the chunk.
   * @return the size.
   */
  public int size()
  {
    return count;
  }

  /**
   * Returns the index of the first line of the chunk.
   */
  public int first()
  {
    return anchor();
  }

  /**
   * Returns the index of the last line of the chunk.
   */
  public int last()
  {
    return anchor() + size() - 1;
  }

  /**
   * Returns the <i>from</i> index of the chunk in RCS terms.
   */
  public int rcsfrom()
  {
    return anchor + 1;
  }

  /**
   * Returns the <i>to</i> index of the chunk in RCS terms.
   */
  public int rcsto()
  {
    return anchor + count;
  }

  /**
   * Returns the text saved for this chunk.
   * @return the text.
   */
  public List<?> chunk()
  {
    return chunk;
  }

  /**
   * Verifies that this chunk's saved text matches the corresponding
   * text in the given sequence.
   * @param target the sequence to verify against.
   * @return true if the texts match.
   */
  public boolean verify(List<?> target)
  {
    if (chunk == null)
    {
      return true;
    }

    if (last() > target.size())
    {
      return false;
    }

    for (int i = 0; i < count; i++)
    {
      if (!target.get(anchor + i).equals(chunk.get(i)))
      {
        return false;
      }
    }

    return true;
  }

  /**
   * Delete this chunk from he given text.
   * @param target the text to delete from.
   */
  public void applyDelete(List<?> target)
  {
      if (last() >= first()) {
          target.subList(first(), last() + 1).clear();
      }
  }

  /**
   * Add the text of this chunk to the target at the given position.
   * @param start where to add the text.
   * @param target the text to add to.
   */
  public void applyAdd(int start, List target)
  {

      for (Object o : chunk) {
          target.add(start++, o);
      }
  }

  /**
   * Provide a string image of the chunk using the an empty prefix and
   * postfix.
   */
  public void toString(StringBuffer s)
  {
    toString(s, "", "");
  }

  /**
   * Provide a string image of the chunk using the given prefix and
   * postfix.
   * @param s where the string image should be appended.
   * @param prefix the text thatshould prefix each line.
   * @param postfix the text that should end each line.
   */
  public StringBuffer toString(StringBuffer s, String prefix, String postfix)
  {
    if (chunk != null)
    {

        for (Object o : chunk) {
            s.append(prefix);
            s.append(o);
            s.append(postfix);
        }
    }

    return s;
  }

  /**
   * Provide a string representation of the numeric range of this chunk.
   */
  public String rangeString()
  {
    StringBuffer result = new StringBuffer();

    rangeString(result);
    return result.toString();
  }

  /**
   * Provide a string representation of the numeric range of this chunk.
   * @param s where the string representation should be appended.
   */
  public void rangeString(StringBuffer s)
  {
    if (size() <= 1)
    {
      s.append(rcsfrom());
    }
    else
    {
      s.append(rcsfrom());
      s.append(",");
      s.append(rcsto());
    }
  }
}
