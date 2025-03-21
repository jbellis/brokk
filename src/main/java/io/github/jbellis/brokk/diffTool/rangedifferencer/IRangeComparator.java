
package io.github.jbellis.brokk.diffTool.rangedifferencer;


/**
 * For breaking an object to compare into a sequence of comparable entities.
 * <p>
 * It is used by <code>RangeDifferencer</code> to find longest sequences of
 * matching and non-matching ranges.
 * <p>
 * For example, to compare two text documents and find longest common sequences
 * of matching and non-matching lines, the implementation must break the document
 * into lines. <code>getRangeCount</code> would return the number of lines in the
 * document, and <code>rangesEqual</code> would compare a specified line given
 * with one in another <code>IRangeComparator</code>.
 * </p>
 * <p>
 * Clients should implement this interface; there is no standard implementation.
 * </p>
 */
public interface IRangeComparator
{
  /**
   * Returns the number of comparable entities.
   *
   * @return the number of comparable entities
   */
  int getRangeCount();

  /**
   * Returns whether the comparable entity given by the first index
   * matches an entity specified by the other <code>IRangeComparator</code> and index.
   *
   * @param thisIndex the index of the comparable entity within this <code>IRangeComparator</code>
   * @param other the IRangeComparator to compare this with
   * @param otherIndex the index of the comparable entity within the other <code>IRangeComparator</code>
   * @return <code>true</code> if the comparable entities are equal
   */
  boolean rangesEqual(int thisIndex, IRangeComparator other, int otherIndex);

}
