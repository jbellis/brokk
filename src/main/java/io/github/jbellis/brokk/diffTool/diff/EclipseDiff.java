
package io.github.jbellis.brokk.diffTool.diff;

import io.github.jbellis.brokk.diffTool.rangedifferencer.IRangeComparator;
import io.github.jbellis.brokk.diffTool.rangedifferencer.RangeDifference;
import io.github.jbellis.brokk.diffTool.rangedifferencer.RangeDifferencer;

public class EclipseDiff
    extends AbstractJMDiffAlgorithm
{
  public EclipseDiff()
  {
  }

  public JMRevision diff(Object[] orig, Object[] rev)
  {
    RangeDifference[] differences;

    differences = RangeDifferencer.findDifferences(new RangeComparator(orig),
            new RangeComparator(rev));

    return buildRevision(differences, orig, rev);
  }

  private JMRevision buildRevision(RangeDifference[] differences,
      Object[] orig, Object[] rev)
  {
    JMRevision result;

    if (orig == null)
    {
      throw new IllegalArgumentException("original sequence is null");
    }

    if (rev == null)
    {
      throw new IllegalArgumentException("revised sequence is null");
    }

    result = new JMRevision(orig, rev);
    for (RangeDifference rd : differences)
    {
      result.add(new JMDelta(new JMChunk(rd.leftStart(), rd.leftLength()),
          new JMChunk(rd.rightStart(), rd.rightLength())));
    }

    return result;
  }

  private static class RangeComparator
      implements IRangeComparator
  {
    private final Object[] objectArray;

    RangeComparator(Object[] objectArray)
    {
      this.objectArray = objectArray;
    }

    public int getRangeCount()
    {
      return objectArray.length;
    }

    public boolean rangesEqual(int thisIndex, IRangeComparator other,
        int otherIndex)
    {
      Object o1;
      Object o2;

      o1 = objectArray[thisIndex];
      o2 = ((RangeComparator) other).objectArray[otherIndex];

      if (o1 == o2)
      {
        return true;
      }

      if (o1 == null)
      {
        return false;
      }

      if (o2 == null)
      {
        return false;
      }

      return o1.equals(o2);
    }

  }
}
