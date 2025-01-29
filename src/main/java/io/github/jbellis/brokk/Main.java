package io.github.jbellis.brokk;

import io.joern.javasrc2cpg.Config;
import io.joern.javasrc2cpg.JavaSrc2Cpg;
import io.joern.joerncli.CpgBasedTool;
import io.shiftleft.codepropertygraph.generated.Cpg;
import scala.Option;
import scala.collection.JavaConverters;

import java.io.File;
import java.util.Collections;

public class Main {
    public static void main(String[] args) {
        System.out.println("Hello Joern");
        scala.collection.immutable.Set<String> emptyScalaSet = JavaConverters.asScalaSet(Collections.<String>emptySet()).toSet();

        String cpgFile = "/home/jonathan/Projects/cassandra/workspace/cpg.bin";
        Cpg cpg;

        if (new File(cpgFile).exists()) {
            cpg = CpgBasedTool.loadFromFile(cpgFile);
        } else {
            System.out.print("Creating CPG... ");
            Config config = Config.apply(
                            emptyScalaSet,         // inferenceJarPaths
                            false,                 // fetchDependencies
                            Option.empty(),        // javaFeatureSetVersion
                            Option.empty(),        // delombokJavaHome
                            Option.empty(),        // delombokMode
                            false,                 // enableTypeRecovery
                            Option.empty(),        // jdkPath
                            false,                 // showEnv
                            false,                 // skipTypeInfPass
                            false,                 // dumpJavaparserAsts
                            false,                 // cacheJdkTypeSolver
                            false                  // keepTypeArguments
                    ).withInputPath("/home/jonathan/Projects/cassandra/src/java")
                    .withOutputPath(cpgFile);

            cpg = new JavaSrc2Cpg().createCpg(config).get();
        }

        System.out.println(cpg.graph().allNodes().next());
        cpg.close(); // auto-saves
    }
}