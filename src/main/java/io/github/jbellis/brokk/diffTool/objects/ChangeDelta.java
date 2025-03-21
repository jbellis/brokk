
package io.github.jbellis.brokk.diffTool.objects;


import io.github.jbellis.brokk.diffTool.diff.Diff;

/**
 * Holds an change-delta between to revisions of a text.
 */
public class ChangeDelta
    extends Delta
{

  public void toString(StringBuffer s)
  {
    original.rangeString(s);
    s.append("c");
    revised.rangeString(s);
    s.append(Diff.NL);
    original.toString(s, "< ", "\n");
    s.append("---");
    s.append(Diff.NL);
    revised.toString(s, "> ", "\n");
  }
}
