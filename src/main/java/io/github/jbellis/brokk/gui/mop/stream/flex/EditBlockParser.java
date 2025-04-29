package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.parser.InlineParser;
import com.vladsch.flexmark.parser.block.*;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.regex.Matcher;

import static io.github.jbellis.brokk.gui.mop.stream.flex.EditBlockUtils.*;

/**
 * Parser for Brokk-specific EDIT BLOCK syntax.
 * Supports two formats:
 * 1. Fenced:    ```\n<filename>\n<<<<<<< SEARCH\n...\n=======\n...\n>>>>>>> REPLACE\n```
 * 2. Unfenced:  <<<<<<< SEARCH <filename>\n...\n======= <filename>\n...\n>>>>>>> REPLACE
 */
public class EditBlockParser extends AbstractBlockParser {
    private static final Logger logger = LogManager.getLogger(EditBlockParser.class);
    
    // Parser states
    private enum Phase { FENCE, FILENAME, SEARCH, DIVIDER, REPLACE, DONE }
    
    private final EditBlockNode block = new EditBlockNode();
    private final BasedSequence openingMarker;
    private final BasedSequence searchKeyword;
    
    private BasedSequence divider;
    private BasedSequence replaceKeyword;
    private BasedSequence closingMarker;
    
    // Parser state
    private Phase phase = Phase.SEARCH;
    private StringBuilder searchContent = new StringBuilder();
    private StringBuilder replaceContent = new StringBuilder();
    private boolean parsingFenced = false;
    private boolean sawHeadLine = false;
    private String currentFilename = null;
    
    EditBlockParser(BasedSequence openingMarker, BasedSequence searchKeyword, BasedSequence initialLine, boolean isFenced, String filename) {
        this.openingMarker = openingMarker;
        this.searchKeyword = searchKeyword;
        this.parsingFenced = isFenced;
        this.currentFilename = filename;
        
        // Set the block's character sequence to ensure it has a real position in the document
        block.setChars(openingMarker);
        
        if (isFenced) {
            this.phase = Phase.FILENAME;
        } else if (!initialLine.isBlank()) {
            searchContent.append(initialLine);
        }
        
        // Set filename in the node immediately if available
        if (currentFilename != null && !currentFilename.isBlank()) {
            block.setFilename(currentFilename);
        }
    }
    
    @Override
    public Block getBlock() {
        return block;
    }

    @Override
    public BlockContinue tryContinue(ParserState state) {
        BasedSequence line = state.getLine();
        String lineStr = line.toString();
        
        switch (phase) {
            case FILENAME:
                // First line after opening fence is the filename
                currentFilename = lineStr.trim();
                block.setFilename(currentFilename);
                phase = Phase.SEARCH;
                return BlockContinue.atIndex(state.getIndex());
                
            case SEARCH:
                // Check for HEAD marker (<<<<<<< SEARCH)
                if (!sawHeadLine && HEAD.matcher(lineStr).matches()) {
                    sawHeadLine = true;
                    // Extract filename from head line if none was provided before
                    if ((currentFilename == null || currentFilename.isBlank()) && lineStr.trim().length() > 14) {
                        String possibleFilename = lineStr.trim().substring(14).trim();
                        if (!possibleFilename.isBlank()) {
                            currentFilename = possibleFilename;
                            block.setFilename(currentFilename);
                        }
                    }
                    return BlockContinue.atIndex(state.getIndex());
                }
                
                // Check for divider (=======)
                if (DIVIDER.matcher(lineStr).matches()) {
                    phase = Phase.REPLACE;
                    this.divider = line;
                    return BlockContinue.atIndex(state.getIndex());
                }
                
                // Still in search part - only add content after we've seen the HEAD marker
                if (sawHeadLine || !parsingFenced) {
                    searchContent.append(searchContent.length() > 0 ? "\n" : "").append(line);
                }
                return BlockContinue.atIndex(state.getIndex());
                
            case REPLACE:
                // Check for end marker (>>>>>>> REPLACE)
                if (UPDATED.matcher(lineStr).matches()) {
                    phase = Phase.DONE;
                    this.closingMarker = line;
                    replaceKeyword = BasedSequence.of("REPLACE");
                    
                    String beforeText = stripQuotedWrapping(searchContent.toString(), currentFilename);
                    String afterText = stripQuotedWrapping(replaceContent.toString(), currentFilename);
                    
                    block.setSegments(openingMarker, searchKeyword, 
                                     BasedSequence.of(beforeText),
                                     divider, replaceKeyword, 
                                     BasedSequence.of(afterText), 
                                     closingMarker);
                    
                    if (!parsingFenced) {
                        return BlockContinue.finished();
                    }
                    return BlockContinue.atIndex(state.getIndex());
                }
                
                // Still in replace part
                replaceContent.append(replaceContent.length() > 0 ? "\n" : "").append(line);
                return BlockContinue.atIndex(state.getIndex());
                
            case DONE:
                // We're done parsing this block
                if (parsingFenced && lineStr.trim().equals(DEFAULT_FENCE[0])) {
                    // Consume the closing fence line and finish
                    return BlockContinue.finished();
                }
                return BlockContinue.atIndex(state.getIndex());
                
            default:
                return BlockContinue.none();
        }
    }

    @Override
    public void parseInlines(InlineParser inlineParser) {
        // No inline parsing needed for EDIT blocks
    }
    
    @Override
    public void closeBlock(ParserState state) {
        // If we haven't already set segments, do it now
        if (block.getOpeningMarker() == null) {
            String beforeText = stripQuotedWrapping(searchContent.toString(), currentFilename);
            String afterText = stripQuotedWrapping(replaceContent.toString(), currentFilename);
            
            block.setSegments(openingMarker, searchKeyword, 
                             BasedSequence.of(beforeText),
                             divider != null ? divider : BasedSequence.NULL, 
                             replaceKeyword != null ? replaceKeyword : BasedSequence.NULL, 
                             BasedSequence.of(afterText), 
                             closingMarker != null ? closingMarker : BasedSequence.NULL);
        }
        
        // Ensure filename is set
        if (currentFilename != null && !currentFilename.isBlank() && block.getFilename() == BasedSequence.NULL) {
            block.setFilename(currentFilename);
        }
    }
    
    /**
     * Factory for creating EditBlockParser.
     */
    public static class Factory implements com.vladsch.flexmark.parser.block.CustomBlockParserFactory {
        @Override
        public BlockParserFactory apply(DataHolder options) {
            return new BlockParserFactory() {
                @Override
                public BlockStart tryStart(ParserState state, MatchedBlockParser matchedBlockParser) {
                    BasedSequence line = state.getLine();
                    String lineStr = line.toString();
                    Set<ProjectFile> projectFiles = null; // Would need to be passed from elsewhere
                    String previousFilename = null; // Would need tracking
                    
                    // We can't use sophisticated paragraph interruption in this version
                    // of flexmark, so we'll proceed with standard block handling
                    
                    // Check if this line is a fence opening
                    Matcher fenceMatcher = OPENING_FENCE.matcher(lineStr);
                    if (fenceMatcher.matches()) {
                        logger.debug("Found potential fence opening: {}", line);
                        
                        // Look-ahead: is there a <<<<<<< SEARCH before the closing fence?
                        var doc = line.getBaseSequence();          // whole document
                        int pos = line.getEndOffset();             // character after the opening ```
                        int linesChecked = 0;
                        boolean headFound = false;
                        boolean fenceClosed = false;

                        while (pos < doc.length() && !headFound && !fenceClosed && linesChecked < 25) {
                            int eol = doc.indexOf('\n', pos);
                            if (eol == -1) eol = doc.length();
                            var nextLine = doc.subSequence(pos, eol).toString();

                            if (OPENING_FENCE.matcher(nextLine).matches()) fenceClosed = true;
                            if (HEAD.matcher(nextLine).matches())          headFound = true;

                            pos = eol + 1;
                            linesChecked++;
                        }

                        if (!headFound) {
                            // No SEARCH marker before the closing fence -> not an edit block
                            return BlockStart.none();               // let Flexmark parse it as code-fence
                        }
                        
                        // SEARCH found -> treat as fenced edit-block
                        return BlockStart.of(new EditBlockParser(
                                line, BasedSequence.NULL, BasedSequence.NULL, true, null))
                                .atIndex(state.getIndex());
                    }
                    
                    // Check if this line matches the edit block start pattern (<<<<<<< SEARCH)
                    Matcher headMatcher = HEAD.matcher(lineStr);
                    if (headMatcher.matches()) {
                        BasedSequence openingMarker = line.subSequence(0, 5); // The five "<"
                        BasedSequence searchKeyword = line.subSequence(6, 12); // "SEARCH"
                        BasedSequence searchText = line.subSequence(12); // File name and rest
                        
                        // Extract filename from the SEARCH line or guess
                        String filename = null;
                        String filenameText = headMatcher.group(1);
                        
                        if (filenameText != null && !filenameText.isBlank()) {
                            filename = filenameText.trim();
                        } else {
                            // Try to find filename nearby using shared utility
                            String[] allLines =
                                    state.getLineSegments()             // List<BasedSequence>
                                            .stream()
                                            .map(BasedSequence::toString)   // remove flexmark wrapper
                                            .toArray(String[]::new);
                            filename = findFileNameNearby(
                                allLines,
                                state.getLineNumber(),
                                projectFiles != null ? projectFiles : Set.of(),
                                previousFilename);
                        }
                        
                        logger.debug("Found edit block start: {}, filename: {}", line, filename);
                        
                        return BlockStart.of(new EditBlockParser(openingMarker, searchKeyword, searchText, false, filename))
                                .atIndex(state.getIndex());
                    }
                    
                    return BlockStart.none();
                }
            };
        }
        
        @Override
        public boolean affectsGlobalScope() {
            return false;
        }
        
        @Override
        public Set<Class<?>> getAfterDependents() {
            return null;
        }
        
        @Override
        public Set<Class<?>> getBeforeDependents() {
            return null;
        }
    }
}
