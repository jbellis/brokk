
package io.github.jbellis.brokk.diffTool.doc;


import javax.swing.text.PlainDocument;
import java.io.Reader;

public interface BufferDocumentIF {
    String ORIGINAL = "Original";
    String REVISED = "Revised";

    String getName();

    String getShortName();

    void addChangeListener(BufferDocumentChangeListenerIF listener);

    void removeChangeListener(BufferDocumentChangeListenerIF listener);

    boolean isChanged();

    PlainDocument getDocument();

    AbstractBufferDocument.Line[] getLines();

  int getOffsetForLine(int lineNumber);

    int getLineForOffset(int offset);

    void read()
            throws Exception;

    void write()
            throws Exception;

    Reader getReader()
            throws Exception;

    boolean isReadonly();
}
