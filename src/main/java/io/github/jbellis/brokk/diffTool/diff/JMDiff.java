
package io.github.jbellis.brokk.diffTool.diff;

import io.github.jbellis.brokk.diffTool.doc.AbstractBufferDocument;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JMDiff
{
  public static int BUFFER_SIZE=100000;
  // Class variables:
  // Allocate a charBuffer once for performance. The charbuffer is used to
  //   store a 'line' without it's ignored characters. 
  static final private CharBuffer inputLine = CharBuffer.allocate(BUFFER_SIZE);
  static final private CharBuffer outputLine = CharBuffer.allocate(BUFFER_SIZE);
  // Instance variables:
  private final List<JMDiffAlgorithmIF> algorithms;

  public JMDiff()
  {
    MyersDiff myersDiff;

    myersDiff = new MyersDiff();
    myersDiff.checkMaxTime(true);

    // MyersDiff is the fastest but can be very slow when 2 files
    // are very different.
    algorithms = new ArrayList<>();
    //algorithms.add(myersDiff);

    // EclipseDiff looks like Myersdiff but is slower.
    // It performs much better if the files are totally different
    algorithms.add(new EclipseDiff());
  }

  public JMRevision diff(List<String> a, List<String> b, Ignore ignore)
      throws Exception
  {
    if (a == null)
    {
      a = Collections.emptyList();
    }
    if (b == null)
    {
      b = Collections.emptyList();
    }
    return diff(a.toArray(), b.toArray(), ignore);
  }

  public JMRevision diff(Object[] a, Object[] b, Ignore ignore)
  {
    JMRevision revision;

    boolean filtered;
    Object[] org;
    Object[] rev;

      org = a;
    rev = b;

    if (org == null)
    {
      org = new Object[] {};
    }
    if (rev == null)
    {
      rev = new Object[] {};
    }

      filtered = org instanceof AbstractBufferDocument.Line[]
              && rev instanceof AbstractBufferDocument.Line[];


    if (filtered)
    {
      org = filter(ignore, org);
      rev = filter(ignore, rev);
    }

    for (JMDiffAlgorithmIF algorithm : algorithms)
    {
      try
      {
        revision = algorithm.diff(org, rev);
        revision.setIgnore(ignore);
        revision.update(a, b);
        if (filtered)
        {
          adjustRevision(revision, a, (JMString[]) org, b, (JMString[]) rev);
        }

        return revision;
      }
      catch (Exception ex)
      {
        ex.printStackTrace();
      }
    }

    return null;
  }

  private void adjustRevision(JMRevision revision, Object[] orgArray,
      JMString[] orgArrayFiltered, Object[] revArray,
      JMString[] revArrayFiltered)
  {
    JMChunk chunk;
    int anchor;
    int size;
    int index;

    for (JMDelta delta : revision.getDeltas())
    {
      chunk = delta.getOriginal();
      index = chunk.getAnchor();
      if (index < orgArrayFiltered.length)
      {
        anchor = orgArrayFiltered[index].lineNumber;
      }
      else
      {
        anchor = orgArray.length;
      }

      size = chunk.getSize();
      if (size > 0)
      {
        index += chunk.getSize() - 1;
        if (index < orgArrayFiltered.length)
        {
          size = orgArrayFiltered[index].lineNumber - anchor + 1;
        }
      }
      chunk.setAnchor(anchor);
      chunk.setSize(size);

      chunk = delta.getRevised();
      index = chunk.getAnchor();
      if (index < revArrayFiltered.length)
      {
        anchor = revArrayFiltered[index].lineNumber;
      }
      else
      {
        anchor = revArray.length;
      }
      size = chunk.getSize();
      if (size > 0)
      {
        index += chunk.getSize() - 1;
        if (index < revArrayFiltered.length)
        {
          size = revArrayFiltered[index].lineNumber - anchor + 1;
        }
      }
      chunk.setAnchor(anchor);
      chunk.setSize(size);
    }
  }

  private JMString[] filter(Ignore ignore, Object[] array)
  {
    List<JMString> result;
    JMString jms;
    int lineNumber;

    synchronized (inputLine)
    {
      result = new ArrayList<>(array.length);
      lineNumber = -1;
      for (Object o : array)
      {
        lineNumber++;

        inputLine.clear();
        inputLine.put(o.toString());
        removeIgnoredChars(inputLine, ignore, outputLine);
        if (outputLine.remaining() == 0)
        {
          continue;
        }

        jms = new JMString();
        jms.s = outputLine.toString();
        jms.lineNumber = lineNumber;
        result.add(jms);
      }
    }

    return result.toArray(new JMString[result.size()]);
  }

  static class JMString
  {
    String s;
    int lineNumber;

    @Override
    public int hashCode()
    {
      return s.hashCode();
    }

    @Override
    public boolean equals(Object o)
    {
      return s.equals(((JMString) o).s);
    }

    @Override
    public String toString()
    {
      return "[" + lineNumber + "] " + s;
    }
  }


  public static boolean isEOL(int character)
  {
    return character == '\n' || character == '\r';
  }

  /** Remove all characters from the 'line' that can be ignored.
   *  @param  inputLine char[] representing a line.
   *  @param  ignore an object with the ignore options.
   *  @param  outputLine return value which contains all characters from line that cannot be
   *          ignored. It is a parameter that can be reused (which is important for
   *          performance)
   */
  public static void removeIgnoredChars(CharBuffer inputLine, Ignore ignore,
                                        CharBuffer outputLine)
  {
    boolean whitespaceAtBegin;
    boolean blankLine;
    int lineEndingEndIndex;
    int whitespaceEndIndex;
    int length;
    char c;
    boolean whiteSpaceInBetweenIgnored;

    inputLine.flip();
    outputLine.clear();

    length = inputLine.remaining();
    lineEndingEndIndex = length;
    blankLine = true;
    whiteSpaceInBetweenIgnored = false;

    for (int index = lineEndingEndIndex - 1; index >= 0; index--)
    {
      if (!isEOL(inputLine.charAt(index)))
      {
        break;
      }

      lineEndingEndIndex--;
    }

    whitespaceEndIndex = lineEndingEndIndex;
    for (int index = whitespaceEndIndex - 1; index >= 0; index--)
    {
      if (!Character.isWhitespace(inputLine.charAt(index)))
      {
        break;
      }

      whitespaceEndIndex--;
    }

    whitespaceAtBegin = true;
    for (int i = 0; i < length; i++)
    {
      c = inputLine.get(i);

      if (i < whitespaceEndIndex)
      {
        if (Character.isWhitespace(c))
        {
          if (whitespaceAtBegin)
          {
            if (ignore.ignoreWhitespaceAtBegin)
            {
              continue;
            }
          }
          else
          {
            if (ignore.ignoreWhitespaceInBetween)
            {
              whiteSpaceInBetweenIgnored = true;
              continue;
            }
          }
        }

        whitespaceAtBegin = false;
        blankLine = false;

        // The character won't be ignored!
      }
      else if (i < lineEndingEndIndex)
      {
        if (ignore.ignoreWhitespaceAtEnd)
        {
          continue;
        }
        blankLine = false;

        // The character won't be ignored!
      }
      else
      {
        if (ignore.ignoreEOL)
        {
          continue;
        }
        // The character won't be ignored!
      }

      if (ignore.ignoreCase)
      {
        c = Character.toLowerCase(c);
      }

      if(whiteSpaceInBetweenIgnored)
      {
        //outputLine.put(' ');
        whiteSpaceInBetweenIgnored = false;
      }
      outputLine.put(c);
    }

    if (outputLine.position() == 0 && !ignore.ignoreBlankLines)
    {
      outputLine.put('\n');
    }

    if (blankLine && ignore.ignoreBlankLines)
    {
      outputLine.clear();
    }

    outputLine.flip();
  }
}
