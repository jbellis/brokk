
package io.github.jbellis.brokk.diffTool.doc;


import com.ibm.icu.text.CharsetMatch;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Map;

public class FileDocument
    extends AbstractBufferDocument
{
  // instance variables:
  private File file;
  private Charset charset;
  private Map<String, Charset> charsetMap;

  public FileDocument(File file)
  {
    this.file = file;
    charsetMap = Charset.availableCharsets();
    try
    {
      setName(file.getCanonicalPath());
    }
    catch (Exception ex)
    {
      ex.printStackTrace();
      setName(file.getName());
    }

    setShortName(file.getName());
  }

  public int getBufferSize()
  {
    return (int) file.length();
  }


  public Reader getReader() {
    BufferedInputStream bis;

    try
    {
      // Try to create a reader that has the right charset.
      // If you use new FileReader(file) you get a reader
      //   with the default charset.
      bis = new BufferedInputStream(new FileInputStream(file));

      charset = detectCharset(bis);
      return new BufferedReader(new InputStreamReader(bis, charset));
    }
    catch (Exception ex)
    {
      return new BufferedReader(new InputStreamReader(new ByteArrayInputStream("".getBytes())));
    }
  }



  private Charset detectCharset(BufferedInputStream bis)
  {
    try
    {
      com.ibm.icu.text.CharsetDetector detector;
      CharsetMatch match;
      Charset foundCharset;

      detector = new com.ibm.icu.text.CharsetDetector();
      detector.setText(bis);

      match = detector.detect();
      if (match != null)
      {
        foundCharset = charsetMap.get(match.getName());
        if (foundCharset != null)
        {
          return foundCharset;
        }
      }
    }
    catch (Exception ex)
    {
      ex.printStackTrace();
    }

    return null;
  }

  public Writer getWriter() throws Exception {
    BufferedOutputStream bos;

    try
    {
      bos = new BufferedOutputStream(new FileOutputStream(file));
      return new BufferedWriter(new OutputStreamWriter(bos, charset));
    }
    catch (IOException ex)
    {
      throw new Exception("Cannot create FileWriter for file: "
                               + file.getName(), ex);
    }
  }
}
