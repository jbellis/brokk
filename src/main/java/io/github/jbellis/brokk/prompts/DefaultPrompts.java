package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.ContextManager;

import java.util.ArrayList;
import java.util.List;

public abstract class DefaultPrompts {
    public static final DefaultPrompts instance = new DefaultPrompts() {};

    public static final String LAZY_REMINDER = """
    You are diligent and tireless!
    You NEVER leave comments describing code without implementing it!
    You always COMPLETELY IMPLEMENT the needed code!
    """;

    public static final String OVEREAGER_REMINDER = """
    Pay careful attention to the scope of the user's request. Do what he asks, but no more.
    """;

    public List<ChatMessage> collectMessages(ContextManager cm, String reminder) {
        var messages = new ArrayList<ChatMessage>();

        messages.add(new SystemMessage(formatIntro(cm)));
        messages.addAll(exampleMessages());
        messages.addAll(cm.getReadOnlyMessages());
        messages.addAll(cm.getHistoryMessages());
        messages.add(new UserMessage(toolUsageReminder(reminder)));
        messages.add(new AiMessage("I will use these tools accordingly."));
        messages.addAll(cm.getEditableMessages());

        return messages;
    }

    protected String formatIntro(ContextManager cm) {
        var editableContents = cm.getEditableSummary();
        var readOnlyContents = cm.getReadOnlySummary();
        var styleGuide = cm.getProject().getStyleGuide();

        var workspaceBuilder = new StringBuilder();
        workspaceBuilder.append("- Root: ").append(cm.getRoot().getFileName());
        if (!editableContents.isBlank()) {
            workspaceBuilder.append("\n- Editable files: ").append(editableContents);
        }
        if (!readOnlyContents.isBlank()) {
            workspaceBuilder.append("\n- Read-only snippets: ").append(readOnlyContents);
        }

        return """
                <instructions>
                %s
                </instructions>
                <workspace>
                %s
                </workspace>
                <style_guide>
                %s
                </style_guide>
                """.stripIndent().formatted(
                systemIntro(),
                workspaceBuilder.toString(),
                styleGuide
        ).trim();
    }

    public String systemIntro() {
        return """
               Act as an expert software developer.
               Always use best practices when coding.
               Respect and use existing conventions, libraries, etc. that are already present in the code base.

               You are diligent and tireless!
               You ALWAYS follow the existing code style!
               You NEVER leave comments describing code without implementing it!
               You always COMPLETELY IMPLEMENT the needed code without pausing to ask if you should continue!

               Take requests for changes to the supplied code.
               If the request is ambiguous, ask questions.

               Once you understand the request you MUST do the following:

               1. Plan the changes you will make.
               2. Explain them in plain English, in a few short sentences.
               3. Use the correct tools to apply the changes. You can call any of these tools:
                  - replaceFile
                  - replaceLines
                  - replaceFunction

               Each tool has a well-defined JSON structure for arguments. Provide a JSON object
               with the fields it requires, e.g.:

               For "replaceFile":
               {
                 "name": "replaceFile",
                 "arguments": {
                   "filename": "path/to/MyClass.java",
                   "text": "entire new file content here"
                 }
               }

               For "replaceLines":
               {
                 "name": "replaceLines",
                 "arguments": {
                   "filename": "someFile.java",
                   "oldText": "lines to search",
                   "newText": "lines to replace"
                 }
               }

               For "replaceFunction":
               {
                 "name": "replaceFunction",
                 "arguments": {
                   "fullyQualifiedFunctionName": "com.example.MyClass.doStuff",
                   "functionParameterNames": ["arg1","arg2"],
                   "newFunctionBody": "body code here (omit outer braces)"
                 }
               }

               Include as many tool calls as necessary to fulfill the requested changes.
               If you need to add or modify multiple files, simply provide multiple tool calls.
               Always provide your final proposed solution as a JSON list of tool calls
               with no additional code blocks or explanation beyond them.

               If you need to create a brand new file, specify it with "replaceFile" and
               use an empty or nonexistent file as the old version.

               If a file is read-only or unavailable, ask the user to add or make it editable.
               """.stripIndent();
    }

    public List<ChatMessage> exampleMessages() {
        return List.of(
                new UserMessage("Change get_factorial() to use math.factorial"),
                new AiMessage("""
                  To make this change, we need to modify `mathweb/flask/app.py`:
                  
                  1. Import the math package.
                  2. Remove the existing factorial() function.
                  3. Update get_factorial() to call math.factorial instead.

                  Here is how we can do that with the tools:

                  [
                    {
                      "name": "replaceLines",
                      "arguments": {
                        "filename": "mathweb/flask/app.py",
                        "oldText": "from flask import Flask",
                        "newText": "import math\\nfrom flask import Flask"
                      }
                    },
                    {
                      "name": "replaceLines",
                      "arguments": {
                        "filename": "mathweb/flask/app.py",
                        "oldText": "def factorial(n):\\n    if n == 0: ...",
                        "newText": ""
                      }
                    },
                    {
                      "name": "replaceLines",
                      "arguments": {
                        "filename": "mathweb/flask/app.py",
                        "oldText": "return str(factorial(n))",
                        "newText": "return str(math.factorial(n))"
                      }
                    }
                  ]
                  """.stripIndent()),
                new UserMessage("Refactor hello() into its own filename."),
                new AiMessage("""
                  We can extract hello() into a new file named hello.py, then import it in main.py.
                  
                  Here is how we can do it with tools:

                  [
                    {
                      "name": "replaceFile",
                      "arguments": {
                        "filename": "hello.py",
                        "text": "def hello():\\n    print(\\"hello\\")\\n"
                      }
                    },
                    {
                      "name": "replaceLines",
                      "arguments": {
                        "filename": "main.py",
                        "oldText": "def hello():\\n    print(\\"hello\\")",
                        "newText": "from hello import hello"
                      }
                    }
                  ]
                  """.stripIndent())
        );
    }

    /**
     * Provides a reminder or instructions about how to call the tools properly.
     */
    private String toolUsageReminder(String reminder) {
        return """
               <rules>
               # Tips for editing code

               - If you are replacing large amounts of code, or if this file is tiny, use replaceFile.
               - replaceFunction is the most frequently useful but it can be fragile if the file is not
                 syntactically correct enough to parse.
               - replaceLines can handle any edit, but it is the most manual and you have to get the
                 oldLines exactly right.

               ALWAYS MAKE ALL TOOL CALL EDITS IN A SINGLE RESPONSE!

               %s
               </rules>
               """.formatted(reminder).stripIndent();
    }
}
