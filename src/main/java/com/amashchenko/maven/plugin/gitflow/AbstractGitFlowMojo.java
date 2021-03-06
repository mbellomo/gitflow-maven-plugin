/*
 * Copyright 2014-2015 Aleksandr Mashchenko.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amashchenko.maven.plugin.gitflow;

import java.io.FileReader;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.components.interactivity.Prompter;
import org.codehaus.plexus.util.Os;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils.StringStreamConsumer;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.DefaultConsumer;
import org.codehaus.plexus.util.cli.StreamConsumer;

/**
 * Abstract git flow mojo.
 * 
 * @author Aleksandr Mashchenko
 * 
 */
public abstract class AbstractGitFlowMojo extends AbstractMojo {

    /** Git flow configuration. */
    @Parameter(defaultValue = "${gitFlowConfig}")
    protected GitFlowConfig gitFlowConfig;

    /** Whether to call Maven install goal during the mojo execution. */
    @Parameter(property = "installProject", defaultValue = "false")
    protected boolean installProject = false;

    /** Whether to print commands output into the console. */
    @Parameter(property = "verbose", defaultValue = "false")
    private boolean verbose = false;

    /**
     * The path to the Maven executable. Defaults to either "mvn" or "mvn.bat"
     * depending on the operating system.
     */
    @Parameter(property = "mvnExecutable")
    private String mvnExecutable;
    /**
     * The path to the Git executable. Defaults to either "git" or "git.exe"
     * depending on the operating system.
     */
    @Parameter(property = "gitExecutable")
    private String gitExecutable;

    /** Command line for Git executable. */
    private final Commandline cmdGit = new Commandline();
    /** Command line for Maven executable. */
    private final Commandline cmdMvn = new Commandline();

    /** A full name of the versions-maven-plugin set goal. */
    private static final String VERSIONS_MAVEN_PLUGIN_SET_GOAL = "org.codehaus.mojo:versions-maven-plugin:2.1:set";

    /** Maven project. */
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;
    /** Default prompter. */
    @Component
    protected Prompter prompter;
    /** Maven settings. */
    @Parameter(defaultValue = "${settings}", readonly = true)
    protected Settings settings;

    /** System line separator. */
    protected static final String LS = System.getProperty("line.separator");

    /** String representing success exit code. */
    private static final String SUCCESS_EXIT_CODE = "0";

    /**
     * Initializes command line executables.
     * 
     */
    private void initExecutables() {
        if (StringUtils.isBlank(cmdMvn.getExecutable())) {
            if (StringUtils.isBlank(mvnExecutable)) {
                mvnExecutable = "mvn"
                        + (Os.isFamily(Os.FAMILY_WINDOWS) ? ".bat" : "");
            }
            cmdMvn.setExecutable(mvnExecutable);
        }
        if (StringUtils.isBlank(cmdGit.getExecutable())) {
            if (StringUtils.isBlank(gitExecutable)) {
                gitExecutable = "git"
                        + (Os.isFamily(Os.FAMILY_WINDOWS) ? ".exe" : "");
            }
            cmdGit.setExecutable(gitExecutable);
        }
    }

    /**
     * Gets current project version from pom.xml file.
     * 
     * @return Current project version.
     * @throws MojoFailureException
     */
    protected String getCurrentProjectVersion() throws MojoFailureException {
        try {
            // read pom.xml
            final MavenXpp3Reader mavenReader = new MavenXpp3Reader();
            final FileReader fileReader = new FileReader(project.getFile()
                    .getAbsoluteFile());
            try {
                final Model model = mavenReader.read(fileReader);

                if (model.getVersion() == null) {
                    throw new MojoFailureException(
                            "Cannot get current project version. This plugin should be executed from the parent project.");
                }

                return model.getVersion();
            } finally {
                if (fileReader != null) {
                    fileReader.close();
                }
            }
        } catch (Exception e) {
            throw new MojoFailureException("", e);
        }
    }

    /**
     * Checks uncommitted changes.
     * 
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void checkUncommittedChanges() throws MojoFailureException,
            CommandLineException {
        getLog().info("Checking for uncommitted changes.");
        if (executeGitHasUncommitted()) {
            throw new MojoFailureException(
                    "You have some uncommitted files. Commit or discard local changes in order to proceed.");
        }
    }

    /**
     * Executes git commands to check for uncommitted changes.
     * 
     * @return <code>true</code> when there are uncommitted changes,
     *         <code>false</code> otherwise.
     * @throws CommandLineException
     * @throws MojoFailureException
     */
    protected boolean executeGitHasUncommitted() throws MojoFailureException,
            CommandLineException {
        boolean uncommited = false;

        // 1 if there were differences and 0 means no differences

        // git diff --no-ext-diff --ignore-submodules --quiet --exit-code
        final String diffExitCode = executeGitCommandExitCode("diff",
                "--no-ext-diff", "--ignore-submodules", "--quiet",
                "--exit-code");

        if (SUCCESS_EXIT_CODE.equals(diffExitCode)) {
            // git diff-index --cached --quiet --ignore-submodules HEAD --
            final String diffIndexExitCode = executeGitCommandExitCode(
                    "diff-index", "--cached", "--quiet", "--ignore-submodules",
                    "HEAD", "--");
            if (!SUCCESS_EXIT_CODE.equals(diffIndexExitCode)) {
                uncommited = true;
            }
        } else {
            uncommited = true;
        }

        return uncommited;
    }

    /**
     * Executes git config commands to set Git Flow configuration.
     * 
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void initGitFlowConfig() throws MojoFailureException,
            CommandLineException {
        gitSetConfig("gitflow.branch.master",
                gitFlowConfig.getProductionBranch());
        gitSetConfig("gitflow.branch.develop",
                gitFlowConfig.getDevelopmentBranch());

        gitSetConfig("gitflow.prefix.feature",
                gitFlowConfig.getFeatureBranchPrefix());
        gitSetConfig("gitflow.prefix.release",
                gitFlowConfig.getReleaseBranchPrefix());
        gitSetConfig("gitflow.prefix.hotfix",
                gitFlowConfig.getHotfixBranchPrefix());
        gitSetConfig("gitflow.prefix.support",
                gitFlowConfig.getSupportBranchPrefix());
        gitSetConfig("gitflow.prefix.versiontag",
                gitFlowConfig.getVersionTagPrefix());
    }

    /**
     * Executes git config command.
     * 
     * @param name
     *            Option name.
     * @param value
     *            Option value.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    private void gitSetConfig(final String name, final String value)
            throws MojoFailureException, CommandLineException {
        // ignore error exit codes
        executeGitCommandExitCode("config", name, value);
    }

    /**
     * Executes git for-each-ref with <code>refname:short</code> format.
     * 
     * @param branchName
     *            Branch name to find.
     * @return Branch names which matches <code>refs/heads/{branchName}*</code>.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected String gitFindBranches(final String branchName)
            throws MojoFailureException, CommandLineException {
        String branches = executeGitCommandReturn("for-each-ref",
                "--format=\"%(refname:short)\"", "refs/heads/" + branchName
                        + "*");

        // on *nix systems return values from git for-each-ref are wrapped in
        // quotes
        // https://github.com/aleksandr-m/gitflow-maven-plugin/issues/3
        if (branches != null && !branches.isEmpty()) {
            branches = branches.replaceAll("\"", "");
        }

        return branches;
    }

    /**
     * Executes git checkout.
     * 
     * @param branchName
     *            Branch name to checkout.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitCheckout(final String branchName)
            throws MojoFailureException, CommandLineException {
        getLog().info("Checking out '" + branchName + "' branch.");

        executeGitCommand("checkout", branchName);
    }

    /**
     * Executes git checkout -b.
     * 
     * @param newBranchName
     *            Create branch with this name.
     * @param fromBranchName
     *            Create branch from this branch.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitCreateAndCheckout(final String newBranchName,
            final String fromBranchName) throws MojoFailureException,
            CommandLineException {
        getLog().info(
                "Creating a new branch '" + newBranchName + "' from '"
                        + fromBranchName + "' and checking it out.");

        executeGitCommand("checkout", "-b", newBranchName, fromBranchName);
    }

    /**
     * Executes git commit -a -m.
     * 
     * @param message
     *            Commit message.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitCommit(final String message) throws MojoFailureException,
            CommandLineException {
        getLog().info("Committing changes.");

        executeGitCommand("commit", "-a", "-m", message);
    }

    /**
     * Executes git merge --no-ff.
     * 
     * @param branchName
     *            Branch name to merge.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitMergeNoff(final String branchName)
            throws MojoFailureException, CommandLineException {
        getLog().info("Merging '" + branchName + "' branch.");

        executeGitCommand("merge", "--no-ff", branchName);
    }

    /**
     * Executes git tag -a -m.
     * 
     * @param tagName
     *            Name of the tag.
     * @param message
     *            Tag message.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitTag(final String tagName, final String message)
            throws MojoFailureException, CommandLineException {
        getLog().info("Creating '" + tagName + "' tag.");

        executeGitCommand("tag", "-a", tagName, "-m", message);
    }

    /**
     * Executes git branch -d.
     * 
     * @param branchName
     *            Branch name to delete.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitBranchDelete(final String branchName)
            throws MojoFailureException, CommandLineException {
        getLog().info("Deleting '" + branchName + "' branch.");

        executeGitCommand("branch", "-d", branchName);
    }

    /**
     * Executes 'set' goal of versions-maven-plugin.
     * 
     * @param version
     *            New version to set.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void mvnSetVersions(final String version)
            throws MojoFailureException, CommandLineException {
        getLog().info(
                "Updating pom(-s) version(s) to '" + version + "' version.");

        executeMvnCommand(VERSIONS_MAVEN_PLUGIN_SET_GOAL, "-DnewVersion="
                + version, "-DgenerateBackupPoms=false");
    }

    /**
     * Executes mvn clean test.
     * 
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void mvnCleanTest() throws MojoFailureException,
            CommandLineException {
        getLog().info("Cleaning and testing the project.");

        executeMvnCommand("clean", "test");
    }

    /**
     * Executes mvn clean install.
     * 
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void mvnCleanInstall() throws MojoFailureException,
            CommandLineException {
        getLog().info("Cleaning and installing the project.");

        executeMvnCommand("clean", "install");
    }

    /**
     * Executes Git command and returns output.
     * 
     * @param args
     *            Git command line arguments.
     * @return Command output.
     * @throws CommandLineException
     * @throws MojoFailureException
     */
    protected String executeGitCommandReturn(final String... args)
            throws CommandLineException, MojoFailureException {
        return executeCommand(cmdGit, true, false, args);
    }

    /**
     * Executes Git command and returns exit code.
     * 
     * @param args
     *            Git command line arguments.
     * @return Command output.
     * @throws CommandLineException
     * @throws MojoFailureException
     */
    private String executeGitCommandExitCode(final String... args)
            throws CommandLineException, MojoFailureException {
        return executeCommand(cmdGit, true, true, args);
    }

    /**
     * Executes Git command.
     * 
     * @param args
     *            Git command line arguments.
     * @throws CommandLineException
     * @throws MojoFailureException
     */
    private void executeGitCommand(final String... args)
            throws CommandLineException, MojoFailureException {
        executeCommand(cmdGit, false, false, args);
    }

    /**
     * Executes Maven command.
     * 
     * @param args
     *            Maven command line arguments.
     * @throws CommandLineException
     * @throws MojoFailureException
     */
    private void executeMvnCommand(final String... args)
            throws CommandLineException, MojoFailureException {
        executeCommand(cmdMvn, false, false, args);
    }

    /**
     * Executes command line.
     * 
     * @param cmd
     *            Command line.
     * @param returnOut
     *            Whether to return output. When <code>true</code> the output
     *            will not be printed into the console and will be returned from
     *            this method.
     * @param returnExitCode
     *            If <code>true</code> the exit code of the command will be
     *            returned, if <code>false</code> the output of the command will
     *            be returned. Will not have effect if the
     *            <code>returnOut</code> parameter is set to <code>false</code>.
     * @param args
     *            Command line arguments.
     * @return Output of the command or exit code or empty String depending on
     *         the parameters values.
     * @throws CommandLineException
     * @throws MojoFailureException
     *             If <code>returnExitCode</code> is <code>false</code> and
     *             command exit code is NOT equals to 0.
     */
    private String executeCommand(final Commandline cmd,
            final boolean returnOut, final boolean returnExitCode,
            final String... args) throws CommandLineException,
            MojoFailureException {
        // initialize executables
        initExecutables();

        if (getLog().isDebugEnabled()) {
            getLog().debug(
                    cmd.getExecutable() + " " + StringUtils.join(args, " "));
        }

        cmd.clearArgs();
        cmd.addArguments(args);

        final StreamConsumer out;
        if (returnOut) {
            out = new CommandLineUtils.StringStreamConsumer();
        } else if (verbose) {
            out = new DefaultConsumer();
        } else {
            out = null;
        }

        final CommandLineUtils.StringStreamConsumer err = new CommandLineUtils.StringStreamConsumer();

        // execute
        final int exitCode = CommandLineUtils.executeCommandLine(cmd, out, err);

        // throw error on NOT 0 exit code only if returnExitCode is false
        if (!returnExitCode && exitCode != 0) {
            throw new MojoFailureException(err.getOutput());
        }

        String ret = "";
        if (returnOut) {
            if (returnExitCode) {
                ret = String.valueOf(exitCode);
            } else if (out instanceof StringStreamConsumer) {
                ret = ((StringStreamConsumer) out).getOutput();
            }
        }
        return ret;
    }
}
