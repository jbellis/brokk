package io.github.jbellis.brokk.diffTool.objects;

import java.util.LinkedList;
import java.util.List;

/**
 * A Revision holds the series of deltas that describe the differences
 * between two sequences.
 */
public class Revision
{
  private final List<?> deltas_ = new LinkedList<>();

  /**
   * Retrieves a delta from this revision by position.
   * @param i the position of the delta to retrieve.
   * @return the specified delta
   */
  public Delta getDelta(int i)
  {
    return (Delta) deltas_.get(i);
  }

  /**
   * Returns the number of deltas in this revision.
   * @return the number of deltas.
   */
  public int size()
  {
    return deltas_.size();
  }

}
