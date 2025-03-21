package io.github.jbellis.brokk.diffTool.diff;


import io.github.jbellis.brokk.diffTool.objects.Chunk;
import io.github.jbellis.brokk.diffTool.objects.Delta;
import io.github.jbellis.brokk.diffTool.objects.Revision;

public class MyersDiff
    extends AbstractJMDiffAlgorithm
{
  public MyersDiff()
  {
  }

  public JMRevision diff(Object[] orig, Object[] rev)
  {
   MyersDiff diff;
    Revision revision = new Revision();

    try
    {
      diff = new MyersDiff();
      diff.checkMaxTime(isMaxTimeChecked());
    }
    catch (Exception ex)
    {
     ex.printStackTrace();
    }

    return buildRevision(revision, orig, rev);
  }

  private JMRevision buildRevision(Revision revision, Object[] orig,
      Object[] rev)
  {
    JMRevision result;
    Delta delta;
    Chunk original;
    Chunk revised;

    if (orig == null)
    {
      throw new IllegalArgumentException("original sequence is null");
    }

    if (rev == null)
    {
      throw new IllegalArgumentException("revised sequence is null");
    }

    result = new JMRevision(orig, rev);
    for (int i = 0; i < revision.size(); i++)
    {
      delta = revision.getDelta(i);
      original = delta.getOriginal();
      revised = delta.getRevised();

      result.add(new JMDelta(new JMChunk(original.anchor(), original.size()),
          new JMChunk(revised.anchor(), revised.size())));
    }

    return result;
  }
}
