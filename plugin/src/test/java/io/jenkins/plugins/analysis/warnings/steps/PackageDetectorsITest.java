package io.jenkins.plugins.analysis.warnings.steps;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.junit.jupiter.api.Test;

import edu.hm.hafner.analysis.Issue;

import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;

import io.jenkins.plugins.analysis.core.model.AnalysisResult;
import io.jenkins.plugins.analysis.core.model.ResultAction;
import io.jenkins.plugins.analysis.core.testutil.IntegrationTestWithJenkinsPerSuite;
import io.jenkins.plugins.analysis.warnings.Eclipse;
import io.jenkins.plugins.analysis.warnings.FindBugs;
import io.jenkins.plugins.analysis.warnings.Java;

import static org.assertj.core.api.Assertions.*;

/**
 * This class is an integration test for the classes associated with {@code edu.hm.hafner.analysis.PackageDetectors}.
 *
 * @author Frank Christian Geyer
 * @author Deniz Mardin
 * @author Ullrich Hafner
 */
class PackageDetectorsITest extends IntegrationTestWithJenkinsPerSuite {
    private static final String PACKAGE_FILE_PATH = "detectors/";
    private static final String PACKAGE_WITH_FILES_CSHARP = PACKAGE_FILE_PATH + "csharp/";
    private static final String PACKAGE_WITH_FILES_JAVA = PACKAGE_FILE_PATH + "java/";
    private static final String DEFAULT_TAB_TO_INVESTIGATE = "packageName";
    private static final String DEFAULT_DEBUG_LOG_LINE = "Resolving package names (or namespaces) by parsing the affected files";

    /**
     * Verifies that the output is correct if there exist various namespaces (C#) and packages (Java) at the same time
     * in the expected HTML output.
     */
    @Test
    @org.jvnet.hudson.test.Issue("JENKINS-58538")
    void shouldShowFolderDistributionRatherThanPackageDistribution() {
        FreeStyleProject project = createFreeStyleProject();

        createFileInWorkspace(project, "java-issues.txt",
                createJavaWarning("one/SampleClassWithoutPackage.java", 1)
                        + createJavaWarning("two/SampleClassWithUnconventionalPackageNaming.java", 2)
                        + createJavaWarning("three/SampleClassWithBrokenPackageNaming.java", 3)
                        + createJavaWarning("four/SampleClassWithoutNamespace.cs", 4)
        );

        copySingleFileToWorkspace(project, PACKAGE_WITH_FILES_JAVA + "SampleClassWithoutPackage.java",
                "one/SampleClassWithoutPackage.java");
        copySingleFileToWorkspace(project, PACKAGE_WITH_FILES_JAVA + "SampleClassWithUnconventionalPackageNaming.java",
                "two/SampleClassWithUnconventionalPackageNaming.java");
        copySingleFileToWorkspace(project, PACKAGE_WITH_FILES_JAVA + "SampleClassWithBrokenPackageNaming.java",
                "three/SampleClassWithBrokenPackageNaming.java");
        copySingleFileToWorkspace(project, PACKAGE_WITH_FILES_CSHARP + "SampleClassWithoutNamespace.cs",
                "four/SampleClassWithoutNamespace.cs");

        enableGenericWarnings(project, new Java());
        Run<?, ?> build = buildSuccessfully(project);

        assertThat(PropertyRow.getRows(getResultAction(build), "folder")).containsExactlyInAnyOrder(
                new PropertyRow("four", 1),
                new PropertyRow("one", 1),
                new PropertyRow("three", 1),
                new PropertyRow("two", 1));
    }

    /**
     * Verifies that the output is correct if there exist various namespaces (C#) and packages (Java) at the same time
     * in the expected HTML output.
     */
    @Test
    void shouldShowNamespacesAndPackagesAltogetherForJavaAndCSharpInTheHtmlOutput() {
        ResultAction result = buildProject(PACKAGE_WITH_FILES_CSHARP + "eclipseForCSharpVariousClasses.txt",
                PACKAGE_WITH_FILES_JAVA + "eclipseForJavaVariousClasses.txt",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithPackage.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithoutPackage.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithUnconventionalPackageNaming.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithBrokenPackageNaming.java",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNamespace.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNamespaceBetweenCode.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNestedAndNormalNamespace.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithoutNamespace.cs"
        );

        verifyNamespaces(result,
                new PropertyRow("edu.hm.hafner.analysis._123.int.naming.structure", 1),
                new PropertyRow("SampleClassWithNamespace", 1),
                new PropertyRow("NestedNamespace", 1),
                new PropertyRow("SampleClassWithNestedAndNormalNamespace", 1),
                new PropertyRow("-", 6));
    }

    /**
     * Verifies that the output is correct if there are only packages (Java) in the expected HTML output.
     */
    @Test
    void shouldShowPackagesForJavaOnly() {
        ResultAction details = buildProject(PACKAGE_WITH_FILES_JAVA + "eclipseForJavaVariousClasses.txt",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithPackage.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithoutPackage.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithUnconventionalPackageNaming.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithBrokenPackageNaming.java"
        );

        verifyPackages(details,
                new PropertyRow("-", 5, 100),
                new PropertyRow("edu.hm.hafner.analysis._123.int.naming.structure", 1, 20));
    }

    /**
     * Verifies that the output is correct if there are only namespaces (C#) in the expected HTML output.
     */
    @Test
    void shouldShowNamespacesForCSharpOnlyInTheHtmlOutput() {
        ResultAction details = buildProject(PACKAGE_WITH_FILES_CSHARP + "eclipseForCSharpVariousClasses.txt",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNamespace.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNamespaceBetweenCode.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNestedAndNormalNamespace.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithoutNamespace.cs");

        verifyNamespaces(details,
                new PropertyRow("SampleClassWithNamespace", 1),
                new PropertyRow("NestedNamespace", 1),
                new PropertyRow("SampleClassWithNestedAndNormalNamespace", 1),
                new PropertyRow("-", 3));
    }

    private void verifyNamespaces(final ResultAction details, final PropertyRow... packages) {
        assertThat(PropertyRow.getRows(details, "packageName")).containsExactlyInAnyOrder(packages);
    }

    private void verifyPackages(final ResultAction details, final PropertyRow... packages) {
        assertThat(PropertyRow.getRows(details, "packageName")).containsExactlyInAnyOrder(packages);
    }

    /**
     * Verifies that the output of the HTML page is empty if the project is empty.
     */
    @Test
    void shouldContainNoSpecificHtmlOutputForAnEmptyProject() {
        checkWebPageForExpectedEmptyResult(buildProject());
    }

    /**
     * Verifies that the output of the HTML page is empty if there is only one class without a package (Java).
     */
    @Test
    void shouldContainNoHtmlOutputForNoPackageDefinedJava() {
        checkWebPageForExpectedEmptyResult(
                buildProject(PACKAGE_WITH_FILES_JAVA + "eclipseForJavaOneClassWithoutPackage.txt",
                        PACKAGE_WITH_FILES_JAVA + "SampleClassWithoutPackage.java"
                ));
    }

    /**
     * Verifies that the output of the HTML page is empty if there is only one class with a package (Java).
     */
    @Test
    void shouldContainNoHtmlOutputForOnlyOnePackageDefinedJava() {
        checkWebPageForExpectedEmptyResult(
                buildProject(PACKAGE_WITH_FILES_JAVA + "eclipseForJavaOneClassWithPackage.txt",
                        PACKAGE_WITH_FILES_JAVA + "SampleClassWithPackage.java"
                ));
    }

    /**
     * Verifies that the output of the HTML page is empty if there is only one class without a namespace (C#).
     */
    @Test
    void shouldContainNoHtmlOutputForNoNamespaceDefinedCSharp() {
        checkWebPageForExpectedEmptyResult(buildProject(
                PACKAGE_WITH_FILES_CSHARP + "eclipseForCSharpOneClassWithoutNamespace.txt",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithoutNamespace.cs"
        ));
    }

    /**
     * Verifies that the output of the HTML page is empty if there is only one class with a namespace (C#).
     */
    @Test
    void shouldContainNoHtmlOutputForOnlyOneNamespaceDefinedCSharp() {
        checkWebPageForExpectedEmptyResult(
                buildProject(PACKAGE_WITH_FILES_CSHARP + "eclipseForCSharpOneClassWithNamespace.txt",
                        PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNamespace.cs"
                ));
    }

    /**
     * Verifies that the packages of Java files and namespaces of C# files are handled correctly when they are used
     * together in a build.
     */
    @Test
    void shouldDetectVariousNamespacesAndPackagesForCombinedJavaAndCSharpFiles() {
        ResultAction action = buildProject(PACKAGE_WITH_FILES_JAVA + "eclipseForJavaVariousClasses.txt",
                PACKAGE_WITH_FILES_CSHARP + "eclipseForCSharpVariousClasses.txt",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithPackage.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithoutPackage.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithUnconventionalPackageNaming.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithBrokenPackageNaming.java",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNamespace.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNamespaceBetweenCode.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNestedAndNormalNamespace.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithoutNamespace.cs"
        );
        AnalysisResult result = action.getResult();

        assertThat(result.getIssues()).hasSize(10);
        assertThat(result.getIssues().getPackages())
                .containsExactly("edu.hm.hafner.analysis._123.int.naming.structure", "SampleClassWithNamespace",
                        "NestedNamespace", "SampleClassWithNestedAndNormalNamespace", "-");

        Map<String, Long> totalByPackageName = collectPackageNames(result);
        assertThat(totalByPackageName).hasSize(5);
        assertThat(totalByPackageName.get("edu.hm.hafner.analysis._123.int.naming.structure")).isEqualTo(1L);
        assertThat(totalByPackageName.get("SampleClassWithNamespace")).isEqualTo(1L);
        assertThat(totalByPackageName.get("NestedNamespace")).isEqualTo(1L);
        assertThat(totalByPackageName.get("SampleClassWithNestedAndNormalNamespace")).isEqualTo(1L);
        assertThat(totalByPackageName.get("-")).isEqualTo(6L);

        String logOutput = getConsoleLog(result);
        assertThat(logOutput).contains(DEFAULT_DEBUG_LOG_LINE);
        assertThat(logOutput).contains(returnExpectedNumberOfResolvedPackageNames(10));
    }

    /**
     * Verifies that if there are two builds with different parsers which set the package name at a different time are
     * handled correctly.
     */
    @Test
    void shouldRunTwoIndependentBuildsWithTwoDifferentParsersAndCheckForCorrectPackageHandling() {
        FreeStyleProject jobWithFindBugsParser = createJobWithWorkspaceFiles(
                PACKAGE_FILE_PATH + "various/findbugs-packages.xml");
        enableGenericWarnings(jobWithFindBugsParser, new FindBugs());
        AnalysisResult resultWithFindBugsParser = scheduleBuildAndAssertStatus(jobWithFindBugsParser, Result.SUCCESS);

        ResultAction action = buildProject(
                PACKAGE_WITH_FILES_JAVA + "eclipseForJavaVariousClasses.txt",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithPackage.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithoutPackage.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithUnconventionalPackageNaming.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithBrokenPackageNaming.java"
        );

        assertThat(resultWithFindBugsParser.getIssues()).hasSize(3);
        assertThat(resultWithFindBugsParser.getIssues().getPackages())
                .containsExactly("edu.hm.hafner.analysis.123",
                        "edu.hm.hafner.analysis._test",
                        "edu.hm.hafner.analysis.int.naming.structure");

        Map<String, Long> findBugsTotalByPackageName = collectPackageNames(resultWithFindBugsParser);
        assertThat(findBugsTotalByPackageName).hasSize(3);
        assertThat(findBugsTotalByPackageName.get("edu.hm.hafner.analysis.123")).isEqualTo(1L);
        assertThat(findBugsTotalByPackageName.get("edu.hm.hafner.analysis._test")).isEqualTo(1L);
        assertThat(findBugsTotalByPackageName.get("edu.hm.hafner.analysis.int.naming.structure"))
                .isEqualTo(1L);

        String findBugsConsoleLog = getConsoleLog(resultWithFindBugsParser);
        assertThat(findBugsConsoleLog).contains(DEFAULT_DEBUG_LOG_LINE);
        assertThat(findBugsConsoleLog).contains("-> all affected files already have a valid package name");

        AnalysisResult resultWithEclipseParser = action.getResult();
        assertThat(resultWithEclipseParser.getIssues()).hasSize(6);
        assertThat(resultWithEclipseParser.getIssues().getPackages())
                .containsExactly("edu.hm.hafner.analysis._123.int.naming.structure", "-");

        Map<String, Long> eclipseTotalByPackageName = collectPackageNames(resultWithEclipseParser);
        assertThat(eclipseTotalByPackageName).hasSize(2);
        assertThat(eclipseTotalByPackageName.get("edu.hm.hafner.analysis._123.int.naming.structure"))
                .isEqualTo(1L);
        assertThat(eclipseTotalByPackageName.get("-")).isEqualTo(5L);

        String eclipseConsoleLog = getConsoleLog(resultWithEclipseParser);
        assertThat(eclipseConsoleLog).contains(DEFAULT_DEBUG_LOG_LINE);
        assertThat(eclipseConsoleLog).contains(returnExpectedNumberOfResolvedPackageNames(6));
    }

    /**
     * Verifies that various namespaces (C#) are handled correctly.
     */
    @Test
    void shouldDetectVariousNamespacesForCSharpFiles() {
        ResultAction action = buildProject(
                PACKAGE_WITH_FILES_CSHARP + "eclipseForCSharpVariousClasses.txt",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNamespace.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNamespaceBetweenCode.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithNestedAndNormalNamespace.cs",
                PACKAGE_WITH_FILES_CSHARP + "SampleClassWithoutNamespace.cs");

        String logOutput = getConsoleLog(action.getOwner());
        AnalysisResult result = action.getResult();
        Map<String, Long> collect = collectPackageNames(result);

        assertThat(result.getIssues()).hasSize(6);
        assertThat(result.getIssues().getPackages())
                .containsExactly("SampleClassWithNamespace", "NestedNamespace",
                        "SampleClassWithNestedAndNormalNamespace", "-");
        assertThat(collect).hasSize(4);
        assertThat(collect.get("SampleClassWithNamespace")).isEqualTo(1L);
        assertThat(collect.get("NestedNamespace")).isEqualTo(1L);
        assertThat(collect.get("SampleClassWithNestedAndNormalNamespace")).isEqualTo(1L);
        assertThat(collect.get("-")).isEqualTo(3L);
        assertThat(logOutput).contains(DEFAULT_DEBUG_LOG_LINE);
        assertThat(logOutput).contains(returnExpectedNumberOfResolvedPackageNames(6));
    }

    /**
     * Verifies that various packages (Java) are handled correctly.
     */
    @Test
    void shouldDetectVariousPackagesForJavaFiles() {
        ResultAction action = buildProject(
                PACKAGE_WITH_FILES_JAVA + "eclipseForJavaVariousClasses.txt",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithPackage.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithoutPackage.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithUnconventionalPackageNaming.java",
                PACKAGE_WITH_FILES_JAVA + "SampleClassWithBrokenPackageNaming.java"

        );

        AnalysisResult result = action.getResult();
        assertThat(result.getIssues()).hasSize(6);
        assertThat(result.getIssues().getPackages()).containsExactly(
                "edu.hm.hafner.analysis._123.int.naming.structure", "-");

        Map<String, Long> totalByPacakgeName = collectPackageNames(result);
        assertThat(totalByPacakgeName).hasSize(2);
        assertThat(totalByPacakgeName.get("edu.hm.hafner.analysis._123.int.naming.structure")).isEqualTo(1L);
        assertThat(totalByPacakgeName.get("-")).isEqualTo(5L);

        String consoleLog = getConsoleLog(result);
        assertThat(consoleLog).contains(DEFAULT_DEBUG_LOG_LINE);
        assertThat(consoleLog).contains(returnExpectedNumberOfResolvedPackageNames(6));
    }

    private String returnExpectedNumberOfResolvedPackageNames(final int expectedNumberOfResolvedPackageNames) {
        return "-> resolved package names of " + expectedNumberOfResolvedPackageNames + " affected files";
    }

    private Map<String, Long> collectPackageNames(final AnalysisResult result) {
        return result.getIssues().stream()
                .collect(Collectors.groupingBy(Issue::getPackageName, Collectors.counting()));
    }

    private void checkWebPageForExpectedEmptyResult(final ResultAction result) {
        assertThat(result.getTarget().getDetails(DEFAULT_TAB_TO_INVESTIGATE).getKeys().size()).isLessThanOrEqualTo(1);
    }

    private ResultAction buildProject(final String... files) {
        FreeStyleProject project = createJobWithWorkspaceFiles(files);
        enableGenericWarnings(project, new Eclipse());
        Run<?, ?> build = buildSuccessfully(project);
        return getResultAction(build);
    }

    /**
     * Creates a new {@link FreeStyleProject freestyle job} and copies the specified resources to the workspace folder.
     * The job will get a generated name.
     *
     * @param fileNames
     *         the files to copy to the workspace
     *
     * @return the created job
     */
    private FreeStyleProject createJobWithWorkspaceFiles(final String... fileNames) {
        FreeStyleProject job = createFreeStyleProject();
        copyMultipleFilesToWorkspaceWithSuffix(job, fileNames);
        return job;
    }

    /**
     * Skips .c and .java file names while renaming.
     *
     * @param fileName
     *         the filename
     */
    @Override
    protected String createWorkspaceFileName(final String fileName) {
        String[] genericFileNamesToKeep = {".cs", ".java"};

        List<Boolean> fileNamePrefixInList = Arrays.stream(genericFileNamesToKeep)
                .map(fileName::endsWith)
                .collect(Collectors.toList());
        if (fileNamePrefixInList.contains(true)) {
            return FilenameUtils.getName(fileName);
        }

        return super.createWorkspaceFileName(fileName);
    }
}
