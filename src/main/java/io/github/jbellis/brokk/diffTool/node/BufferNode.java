
package io.github.jbellis.brokk.diffTool.node;

import io.github.jbellis.brokk.diffTool.doc.BufferDocumentIF;

public interface BufferNode
{
  String getName();

  long getSize();

  BufferDocumentIF getDocument();

}
