
package io.github.jbellis.brokk.difftool.diff;

public class JMChunk {
    private int anchor;
    private int size;

    public JMChunk(int anchor, int size) {
        this.anchor = anchor;
        this.size = size;
    }

    void setAnchor(int anchor) {
        this.anchor = anchor;
    }

    public int getAnchor() {
        return anchor;
    }

    void setSize(int size) {
        this.size = size;
    }

    public int getSize() {
        return size;
    }

    public boolean equals(Object o) {
        JMChunk c;

        if (!(o instanceof JMChunk)) {
            return false;
        }

        c = (JMChunk) o;

        return c.size == size && c.anchor == anchor;
    }

    public String toString() {
        return "anchor=" + anchor + ",size=" + size;
    }
}
