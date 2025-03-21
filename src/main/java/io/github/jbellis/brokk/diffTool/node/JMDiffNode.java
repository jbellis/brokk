package io.github.jbellis.brokk.diffTool.node;


import io.github.jbellis.brokk.diffTool.diff.Ignore;
import io.github.jbellis.brokk.diffTool.diff.JMDiff;
import io.github.jbellis.brokk.diffTool.diff.JMRevision;
import io.github.jbellis.brokk.diffTool.doc.BufferDocumentIF;

import javax.swing.tree.TreeNode;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class JMDiffNode
        implements TreeNode
{
  private String text;
  private String name;
  private String id;
  private String shortName;
  private String parentName;
  private JMDiffNode parent;
  private List<JMDiffNode> children;
  private BufferNode nodeLeft;
  private BufferNode nodeRight;
  private final boolean leaf;
    private JMDiff diff;
  private JMRevision revision;
  private final Ignore ignore;

  public JMDiffNode(String name, boolean leaf)
  {
    this.name = name;
    this.shortName = name;
    this.leaf = leaf;
    ignore=new Ignore();

    children = new ArrayList<>();
    calculateNames();
  }

  public String getId()
  {
    return id;
  }

  private void initId()
  {
    id = (nodeLeft != null ? nodeLeft.getName() : "x")
            + (nodeRight != null ? nodeRight.getName() : "x");
  }

  public String getName()
  {
    return name;
  }

  public Ignore getIgnore()
  {
    return ignore;
  }

  public void setBufferNodeLeft(BufferNode bufferNode)
  {
    nodeLeft = bufferNode;
    initId();
  }

  public BufferNode getBufferNodeLeft()
  {
    return nodeLeft;
  }

  public void setBufferNodeRight(BufferNode bufferNode)
  {
    nodeRight = bufferNode;
    initId();
  }

  public BufferNode getBufferNodeRight()
  {
    return nodeRight;
  }

  public Enumeration<JMDiffNode> children()
  {
    return Collections.enumeration(children);
  }

  public boolean getAllowsChildren()
  {
    return isLeaf();
  }

  public JMDiffNode getChildAt(int childIndex)
  {
    return children.get(childIndex);
  }

  public int getChildCount()
  {
    return children.size();
  }

  public int getIndex(TreeNode node)
  {
    return children.indexOf(node);
  }

  public JMDiffNode getParent()
  {
    return parent;
  }

  public boolean isLeaf()
  {
    return leaf;
  }

  private void calculateNames()
  {
    int index;

    index = name.lastIndexOf(File.separator);
    if (index == -1)
    {
      parentName = null;
      return;
    }

    parentName = name.substring(0, index);
    shortName = name.substring(index + 1);
  }

  public void diff()
  {
    BufferDocumentIF documentLeft;
    BufferDocumentIF documentRight;
    Object[] left, right;
    try {
      documentLeft = null;
      documentRight = null;

      if (nodeLeft != null) {
        documentLeft = nodeLeft.getDocument();
        if (documentLeft != null) {
          documentLeft.read();
        }
      }

      if (nodeRight != null) {
        documentRight = nodeRight.getDocument();
        if (documentRight != null) {
          documentRight.read();
        }
      }
      diff = new JMDiff();
      left = documentLeft == null ? null : documentLeft.getLines();
      right = documentRight == null ? null : documentRight.getLines();

      revision = diff.diff(left, right, ignore);
    } catch (Exception ignore) {
    }
  }

  public JMDiff getDiff()
  {
    return diff;
  }

  public JMRevision getRevision()
  {
    return revision;
  }

  @Override
  public String toString()
  {
    String pn;

    if (text == null)
    {
      text = name;
      if (parent != null)
      {
        pn = parent.getName();
        if (name.startsWith(pn))
        {
          text = name.substring(pn.length() + 1);
        }
      }
    }

    return text;
  }
}
