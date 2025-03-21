
package io.github.jbellis.brokk.diffTool.diff;

import io.github.jbellis.brokk.diffTool.objects.Revision;

/**
 * A simple interface for implementations of differencing algorithms.
 */
public interface DiffAlgorithm
{
  Revision diff(Object[] orig, Object[] rev);
}
