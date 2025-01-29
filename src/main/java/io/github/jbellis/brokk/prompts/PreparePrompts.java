package io.github.jbellis.brokk.prompts;

public abstract class PreparePrompts extends ArchitectPrompts {
    public static final PreparePrompts instance = new PreparePrompts() {};

    @Override
    public String systemIntro() {
        return """
               Act as an expert software architect and provide direction to your implementing junior engineer.
               Study the change request and the current code.
               Your job is to decide whether you and your implementor have the right files available
               both as read-only source and summaries to situate the problem, and as editable files to solve it.
               """.stripIndent();
    }
}
