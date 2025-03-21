
package io.github.jbellis.brokk.diffTool.diff;

public abstract class AbstractJMDiffAlgorithm
    implements JMDiffAlgorithmIF
{
  private boolean checkMaxTime;

  public AbstractJMDiffAlgorithm()
  {
  }

  public void checkMaxTime(boolean checkMaxTime)
  {
    this.checkMaxTime = checkMaxTime;
  }

  public boolean isMaxTimeChecked()
  {
    return checkMaxTime;
  }
}
