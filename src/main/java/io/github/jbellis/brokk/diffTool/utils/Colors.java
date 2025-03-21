
package io.github.jbellis.brokk.diffTool.utils;

import javax.swing.*;
import java.awt.*;

public class Colors
{
  public static final Color ADDED = new Color(180, 255, 180);
  public static final Color CHANGED = new Color(160, 200, 255);
  public static final Color DELETED = new Color(255, 160, 180);

  public static Color getPanelBackground()
  {
    return new JPanel().getBackground();
  }
}
