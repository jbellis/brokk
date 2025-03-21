/*
   BrokkDiffTool is a visual diff and merge tool.
   Copyright (C) 2007  Kees Kuip
   This library is free software; you can redistribute it and/or
   modify it under the terms of the GNU Lesser General Public
   License as published by the Free Software Foundation; either
   version 2.1 of the License, or (at your option) any later version.
   This library is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
   Lesser General Public License for more details.
   You should have received a copy of the GNU Lesser General Public
   License along with this library; if not, write to the Free Software
   Foundation, Inc., 51 Franklin Street, Fifth Floor,
   Boston, MA  02110-1301  USA
 */
package io.github.jbellis.brokk.diffTool.diff;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class JMRevision
{
    private static boolean incrementalUpdateActivated = false;

    private Object[] orgArray;
    private Object[] revArray;
    private final LinkedList<JMDelta> deltaList;
    private Ignore ignore;

    public JMRevision(Object[] orgArray, Object[] revArray)
    {
        this.orgArray = orgArray;
        this.revArray = revArray;

        deltaList = new LinkedList<>();

        ignore = Ignore.NULL_IGNORE;
    }

    public void setIgnore(Ignore ignore)
    {
        this.ignore = ignore;
    }

    public void add(JMDelta delta)
    {
        deltaList.add(delta);
        delta.setRevision(this);
    }

    public List<JMDelta> getDeltas()
    {
        return deltaList;
    }

    public void update(Object[] oArray, Object[] rArray)
    {
        this.orgArray = oArray;
        this.revArray = rArray;
    }

    /** The arrays have changed! Try to change the delta's incrementally.
     * This solves a performance issue while editing one of the array's.
     */
    public boolean update(Object[] oArray, Object[] rArray, boolean original,
                          int startLine, int numberOfLines)
    {
        update(oArray, rArray);
        return incrementalUpdate(original, startLine, numberOfLines);
    }

    private boolean incrementalUpdate(boolean original, int startLine,
                                      int numberOfLines)
    {
        JMChunk chunk;
        List<JMDelta> deltaListToRemove;
        List<JMChunk> chunkListToChange;
        int endLine;
        int orgStartLine;
        int orgEndLine;
        int revStartLine;
        int revEndLine;
        JMRevision deltaRevision;
        int index;
        Object[] orgArrayDelta;
        Object[] revArrayDelta;
        JMDelta firstDelta;
        int length;

        if (!incrementalUpdateActivated)
        {
            return false;
        }

        if (original)
        {
            orgStartLine = startLine;
            orgEndLine = startLine + (Math.max(numberOfLines, 0)) + 1;
            revStartLine = DiffUtil.getRevisedLine(this, startLine);
            revEndLine = DiffUtil.getRevisedLine(this,
                    startLine + (numberOfLines > 0 ? 0 : -numberOfLines)) + 1;
        }
        else
        {
            revStartLine = startLine;
            revEndLine = startLine + (Math.max(numberOfLines, 0)) + 1;
            orgStartLine = DiffUtil.getOriginalLine(this, startLine);
            orgEndLine = DiffUtil.getOriginalLine(this,
                    startLine + (numberOfLines > 0 ? 0 : -numberOfLines)) + 1;
        }

        System.out.println("orgStartLine=" + orgStartLine);
        System.out.println("orgEndLine  =" + orgEndLine);
        System.out.println("revStartLine=" + revStartLine);
        System.out.println("revEndLine  =" + revEndLine);

        deltaListToRemove = new ArrayList<>();
        chunkListToChange = new ArrayList<>();

        // Find the delta's of this change!
        endLine = startLine + Math.abs(numberOfLines);
        for (JMDelta delta : deltaList)
        {
            chunk = original ? delta.getOriginal() : delta.getRevised();

            // The change is above this Chunk! It will not change!
            if (endLine < chunk.getAnchor() - 5)
            {
                continue;
            }

            // The change is below this chunk! The anchor of the chunk will be changed!
            if (startLine > chunk.getAnchor() + chunk.getSize() + 5)
            {
                // No need to change chunks if the numberoflines haven't changed.
                if (numberOfLines != 0)
                {
                    chunkListToChange.add(chunk);
                }
                continue;
            }

            // This chunk is affected by the change. It will eventually be removed.
            //   The lines that are affected will be compared and they will insert
            //   new delta's if necessary.
            deltaListToRemove.add(delta);

            // Revise the start and end if there are overlapping chunks.
            chunk = delta.getOriginal();
            if (chunk.getAnchor() < orgStartLine)
            {
                orgStartLine = chunk.getAnchor();
            }
            if (chunk.getAnchor() + chunk.getSize() > orgEndLine)
            {
                orgEndLine = chunk.getAnchor() + chunk.getSize();
            }

            chunk = delta.getRevised();
            if (chunk.getAnchor() < revStartLine)
            {
                revStartLine = chunk.getAnchor();
            }
            if (chunk.getAnchor() + chunk.getSize() > revEndLine)
            {
                revEndLine = chunk.getAnchor() + chunk.getSize();
            }
        }

        orgStartLine = Math.max(orgStartLine, 0);
        revStartLine = Math.max(revStartLine, 0);

        // Check with 'max' if we are dealing with the end of the file.
        length = Math.min(orgArray.length, orgEndLine) - orgStartLine;
        orgArrayDelta = new Object[length];
        System.arraycopy(orgArray, orgStartLine, orgArrayDelta, 0,
                orgArrayDelta.length);

        length = Math.min(revArray.length, revEndLine) - revStartLine;
        revArrayDelta = new Object[length];
        System.arraycopy(revArray, revStartLine, revArrayDelta, 0,
                revArrayDelta.length);

        try
        {
            for (int i = 0; i < orgArrayDelta.length; i++)
            {
                System.out.println("  org[" + i + "]:" + orgArrayDelta[i]);
            }
            for (int i = 0; i < revArrayDelta.length; i++)
            {
                System.out.println("  rev[" + i + "]:" + revArrayDelta[i]);
            }
            deltaRevision = new JMDiff().diff(orgArrayDelta, revArrayDelta, ignore);
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
            return false;
        }

        // OK, Make the changes now
        if (!deltaListToRemove.isEmpty())
        {
            for (JMDelta delta : deltaListToRemove)
            {
                deltaList.remove(delta);
            }
        }

        for (JMChunk c : chunkListToChange)
        {
            c.setAnchor(c.getAnchor() + numberOfLines);
        }

        // Prepare the diff's to be copied into this revision.
        for (JMDelta delta : deltaRevision.deltaList)
        {
            chunk = delta.getOriginal();
            chunk.setAnchor(chunk.getAnchor() + orgStartLine);

            chunk = delta.getRevised();
            chunk.setAnchor(chunk.getAnchor() + revStartLine);
        }

        // Find insertion index point
        if (!deltaRevision.deltaList.isEmpty())
        {
            firstDelta = deltaRevision.deltaList.get(0);
            index = 0;
            for (JMDelta delta : deltaList)
            {
                if (delta.getOriginal().getAnchor() > firstDelta.getOriginal()
                        .getAnchor())
                {
                    break;
                }

                index++;
            }

            for (JMDelta diffDelta : deltaRevision.deltaList)
            {
                diffDelta.setRevision(this);
                deltaList.add(index, diffDelta);
                index++;
            }
        }

        return true;
    }

    public int getOrgSize() {
        return orgArray == null ? 0 : orgArray.length;
    }

    public int getRevSize() {
        return revArray == null ? 0 : revArray.length;
    }

}
