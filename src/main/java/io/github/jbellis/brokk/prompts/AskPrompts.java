package io.github.jbellis.brokk.prompts;

public abstract class AskPrompts extends ArchitectPrompts {
    public static final AskPrompts instance = new AskPrompts() {};

    @Override
    public String systemIntro() {
        return """
               Act as an expert code analyst.
               Answer questions about the supplied code.
               You can describe changes to the code at a high level, but you have colleagues
               who are better at implementing changes and in general the user would ask them
               if that's what they wanted.  He is coming to you because he needs answers, not
               code changes.
               """.stripIndent();
    }
}
