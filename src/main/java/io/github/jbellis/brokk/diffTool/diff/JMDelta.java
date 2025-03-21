
package io.github.jbellis.brokk.diffTool.diff;


public class JMDelta {

    private static boolean debug = false;

    private JMChunk original;
    private JMChunk revised;
    private TypeDiff type;
    private JMRevision revision;
    private JMRevision changeRevision;

    public JMDelta(JMChunk original, JMChunk revised) {
        this.original = original;
        this.revised = revised;

        initType();
    }

    public void setRevision(JMRevision revision) {
        this.revision = revision;
    }

    public JMChunk getOriginal() {
        return original;
    }

    public JMChunk getRevised() {
        return revised;
    }

    public boolean isAdd() {
        return type == TypeDiff.ADD;
    }

    public boolean isDelete() {
        return type == TypeDiff.DELETE;
    }

    public boolean isChange() {
        return type == TypeDiff.CHANGE;
    }

    public void invalidateChangeRevision() {
        setChangeRevision(null);
    }

    public void setChangeRevision(JMRevision changeRevision) {
        this.changeRevision = changeRevision;
    }

    public JMRevision getChangeRevision() {
        if (changeRevision == null) {
            changeRevision = createChangeRevision();
        }

        return changeRevision;
    }

    //TODO: Creates a Delta with chunks from the algorithm
    private JMRevision createChangeRevision() {
        return null;
    }

    void initType() {
        if (original.getSize() > 0 && revised.getSize() == 0) {
            type = TypeDiff.DELETE;
        } else if (original.getSize() == 0 && revised.getSize() > 0) {
            type = TypeDiff.ADD;
        } else {
            type = TypeDiff.CHANGE;
        }
    }

    @Override
    public boolean equals(Object o) {
        JMDelta d;

        if (!(o instanceof JMDelta)) {
            return false;
        }

        d = (JMDelta) o;
        if (revision != d.revision) {
            return false;
        }

        if (!original.equals(d.original) || !revised.equals(d.revised)) {
            return false;
        }

        return true;
    }

    private void debug(String s) {
        if (debug) {
            System.out.println(s);
        }
    }

    public TypeDiff getType() {
        return type;
    }

    @Override
    public String toString() {
        return type + ": org[" + original + "] rev[" + revised + "]";
    }
}
