package io.jenkins.plugins.analysis.core.steps;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import edu.hm.hafner.analysis.Severity;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import org.jenkinsci.Symbol;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.BuildableItem;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;

import io.jenkins.plugins.analysis.core.filter.RegexpFilter;
import io.jenkins.plugins.analysis.core.model.AnalysisResult;
import io.jenkins.plugins.analysis.core.model.HealthReportBuilder;
import io.jenkins.plugins.analysis.core.model.ResultAction;
import io.jenkins.plugins.analysis.core.model.StaticAnalysisLabelProvider;
import io.jenkins.plugins.analysis.core.model.Tool;
import io.jenkins.plugins.analysis.core.steps.IssuesScanner.BlameMode;
import io.jenkins.plugins.analysis.core.steps.WarningChecksPublisher.AnnotationScope;
import io.jenkins.plugins.analysis.core.util.HealthDescriptor;
import io.jenkins.plugins.analysis.core.util.ModelValidation;
import io.jenkins.plugins.analysis.core.util.QualityGate;
import io.jenkins.plugins.analysis.core.util.QualityGate.QualityGateResult;
import io.jenkins.plugins.analysis.core.util.QualityGate.QualityGateType;
import io.jenkins.plugins.analysis.core.util.QualityGateEvaluator;
import io.jenkins.plugins.analysis.core.util.TrendChartType;
import io.jenkins.plugins.checks.steps.ChecksInfo;
import io.jenkins.plugins.prism.SourceCodeDirectory;
import io.jenkins.plugins.util.JenkinsFacade;
import io.jenkins.plugins.util.LogHandler;
import io.jenkins.plugins.util.RunResultHandler;
import io.jenkins.plugins.util.StageResultHandler;
import io.jenkins.plugins.util.ValidationUtilities;

/**
 * Freestyle or Maven job {@link Recorder} that scans report files or the console log for issues. Stores the created
 * issues in an {@link AnalysisResult}. The result is attached to a {@link Run} by registering a {@link ResultAction}.
 *
 * <p>
 * Additional features:
 * </p>
 * <ul>
 * <li>It provides a {@link QualityGateEvaluator} that is checked after each run. If the quality gate is not passed,
 * then the build will be set to {@link Result#UNSTABLE} or {@link Result#FAILURE}, depending on the configuration
 * properties.</li>
 * <li>It provides thresholds for the build health that could be adjusted in the configuration screen.
 * These values are used by the {@link HealthReportBuilder} to compute the health and the health trend graph.
 * </li>
 * </ul>
 *
 * @author Ullrich Hafner
 */
@SuppressWarnings({"PMD.ExcessivePublicCount", "PMD.ExcessiveClassLength", "PMD.ExcessiveImports", "PMD.TooManyFields", "PMD.DataClass", "PMD.GodClass", "PMD.CyclomaticComplexity", "ClassDataAbstractionCoupling", "ClassFanOutComplexity"})
public class IssuesRecorder extends Recorder {
    private static final ValidationUtilities VALIDATION_UTILITIES = new ValidationUtilities();
    static final String NO_REFERENCE_DEFINED = "-";
    static final String DEFAULT_ID = "analysis";

    private List<Tool> analysisTools = new ArrayList<>();

    private String sourceCodeEncoding = StringUtils.EMPTY;
    private String sourceDirectory = StringUtils.EMPTY;
    private Set<SourceCodeDirectory> sourceDirectories = new HashSet<>(); // @since 9.11.0

    private boolean ignoreQualityGate = false; // by default, a successful quality gate is mandatory;
    private boolean ignoreFailedBuilds = true; // by default, failed builds are ignored;
    private String referenceJobName;
    private String referenceBuildId;

    private boolean failOnError = false;

    private int healthy;
    private int unhealthy;
    private Severity minimumSeverity = Severity.WARNING_LOW;

    private List<RegexpFilter> filters = new ArrayList<>();

    private boolean isEnabledForFailure;
    private boolean isAggregatingResults;

    private boolean quiet = false;

    private boolean isBlameDisabled;
    /**
     * Not used anymore.
     *
     * @deprecated since 8.5.0
     */
    @Deprecated
    private transient boolean isForensicsDisabled;

    private boolean skipPublishingChecks; // by default, checks will be published
    private boolean publishAllIssues; // by default, only new issues will be published

    @CheckForNull
    private ChecksInfo checksInfo;

    private String id;
    private String name;

    private List<QualityGate> qualityGates = new ArrayList<>();

    private TrendChartType trendChartType = TrendChartType.AGGREGATION_TOOLS;

    private String scm = StringUtils.EMPTY;

    /**
     * Creates a new instance of {@link IssuesRecorder}.
     */
    @DataBoundConstructor
    public IssuesRecorder() {
        super();

        // empty constructor required for Stapler
    }

    /**
     * Called after de-serialization to retain backward compatibility or to populate new elements (that would be
     * otherwise initialized to {@code null}).
     *
     * @return this
     */
    protected Object readResolve() {
        if (sourceDirectory == null) {
            sourceDirectory = StringUtils.EMPTY;
        }
        if (sourceDirectories == null) {
            sourceDirectories = new HashSet<>();
            if (StringUtils.isNotBlank(sourceDirectory)) {
                sourceDirectories.add(new SourceCodeDirectory(sourceDirectory));
            }
        }
        if (trendChartType == null) {
            trendChartType = TrendChartType.AGGREGATION_TOOLS;
        }
        if (analysisTools == null) {
            analysisTools = new ArrayList<>();
        }
        if (qualityGates == null) {
            qualityGates = new ArrayList<>();
        }
        if (scm == null) {
            scm = StringUtils.EMPTY;
        }
        return this;
    }

    /**
     * Sets the SCM that should be used to find the reference build for. The reference recorder will select the SCM
     * based on a substring comparison, there is no need to specify the full name.
     *
     * @param scm
     *         the ID of the SCM to use (a substring of the full ID)
     */
    @DataBoundSetter
    public void setScm(final String scm) {
        this.scm = scm;
    }

    public String getScm() {
        return scm;
    }

    /**
     * Defines the optional list of quality gates.
     *
     * @param qualityGates
     *         the quality gates
     */
    @SuppressWarnings("unused") // used by Stapler view data binding
    @DataBoundSetter
    public void setQualityGates(final List<QualityGate> qualityGates) {
        this.qualityGates = qualityGates;
    }

    /**
     * Appends the specified quality gates to the end of the list of quality gates.
     *
     * @param size
     *         the minimum number of issues that fails the quality gate
     * @param type
     *         the type of the quality gate
     * @param result
     *         determines whether the quality gate is a warning or failure
     */
    public void addQualityGate(final int size, final QualityGateType type, final QualityGateResult result) {
        qualityGates.add(new QualityGate(size, type, result));
    }

    @SuppressWarnings("unused") // used by Stapler view data binding
    public List<QualityGate> getQualityGates() {
        return qualityGates;
    }

    /**
     * Defines the ID of the results. The ID is used as URL of the results and as name in UI elements. If no ID is
     * given, then the ID of the associated result object is used.
     * <p>
     * Note: this property is not used if {@link #isAggregatingResults} is {@code false}. It is also not visible in the
     * UI in order to simplify the user interface.
     * </p>
     *
     * @param id
     *         the ID of the results
     */
    @DataBoundSetter
    public void setId(final String id) {
        VALIDATION_UTILITIES.ensureValidId(id);

        this.id = id;
    }

    public String getId() {
        return id;
    }

    /**
     * Defines the name of the results. The name is used for all labels in the UI. If no name is given, then the name of
     * the associated {@link StaticAnalysisLabelProvider} is used.
     * <p>
     * Note: this property is not used if {@link #isAggregatingResults} is {@code false}. It is also not visible in the
     * UI in order to simplify the user interface.
     * </p>
     *
     * @param name
     *         the name of the results
     */
    @DataBoundSetter
    public void setName(final String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * Gets the static analysis tools that will scan files and create issues.
     *
     * @return the static analysis tools (wrapped as {@link ToolProxy})
     * @see #getTools
     * @deprecated this method is only intended to be called by the UI
     */
    @CheckForNull
    @Deprecated
    public List<ToolProxy> getToolProxies() {
        return analysisTools.stream().map(ToolProxy::new).collect(Collectors.toList());
    }

    /**
     * Sets the static analysis tools that will scan files and create issues.
     *
     * @param toolProxies
     *         the static analysis tools (wrapped as {@link ToolProxy})
     *
     * @see #setTools(List)
     * @deprecated this method is only intended to be called by the UI
     */
    @DataBoundSetter
    @Deprecated
    public void setToolProxies(final List<ToolProxy> toolProxies) {
        analysisTools = toolProxies.stream().map(ToolProxy::getTool).collect(Collectors.toList());
    }

    /**
     * Sets the static analysis tools that will scan files and create issues.
     *
     * @param tools
     *         the static analysis tools
     */
    @DataBoundSetter
    public void setTools(final List<Tool> tools) {
        analysisTools = new ArrayList<>(tools);
    }

    /**
     * Sets the static analysis tools that will scan files and create issues.
     *
     * @param tool
     *         the static analysis tool
     * @param additionalTools
     *         additional static analysis tools (might be empty)
     *
     * @see #setTools(List)
     */
    public void setTools(final Tool tool, final Tool... additionalTools) {
        analysisTools = new ArrayList<>();
        analysisTools.add(tool);
        Collections.addAll(analysisTools, additionalTools);
    }

    /**
     * Returns the static analysis tools that will scan files and create issues.
     *
     * @return the static analysis tools
     */
    public List<Tool> getTools() {
        return new ArrayList<>(analysisTools);
    }

    @CheckForNull
    public String getSourceCodeEncoding() {
        return sourceCodeEncoding;
    }

    /**
     * Sets the encoding to use to read source files.
     *
     * @param sourceCodeEncoding
     *         the encoding, e.g. "ISO-8859-1"
     */
    @DataBoundSetter
    public void setSourceCodeEncoding(final String sourceCodeEncoding) {
        this.sourceCodeEncoding = sourceCodeEncoding;
    }

    public String getSourceDirectory() {
        return sourceDirectory;
    }

    /**
     * Sets the path to the directory that contains the source code. If not relative and thus not part of the workspace
     * then this directory needs to be added in Jenkins global configuration to prevent accessing of forbidden resources.
     *
     * @param sourceDirectory
     *         directory containing the source code
     */
    @DataBoundSetter
    public void setSourceDirectory(final String sourceDirectory) {
        this.sourceDirectory = sourceDirectory;
    }

    /**
     * Sets the paths to the directories that contain the source code. If not relative and thus not part of the workspace
     * then these directories need to be added in Jenkins global configuration to prevent accessing of forbidden resources.
     *
     * @param sourceDirectories
     *         directories containing the source code
     */
    @DataBoundSetter
    public void setSourceDirectories(final List<SourceCodeDirectory> sourceDirectories) {
        this.sourceDirectories = new HashSet<>(sourceDirectories);
    }

    public List<SourceCodeDirectory> getSourceDirectories() {
        return new ArrayList<>(sourceDirectories);
    }

    /**
     * Returns whether the results for each configured static analysis result should be aggregated into a single result
     * or if every tool should get an individual result.
     *
     * @return {@code true}  if the results of each static analysis tool should be aggregated into a single result,
     *         {@code false} if every tool should get an individual result.
     */
    @SuppressWarnings("PMD.BooleanGetMethodName")
    public boolean getAggregatingResults() {
        return isAggregatingResults;
    }

    @DataBoundSetter
    public void setAggregatingResults(final boolean aggregatingResults) {
        isAggregatingResults = aggregatingResults;
    }

    /**
     * Returns whether report logging should be enabled.
     *
     * @return {@code true}  report logging is disabled
     *         {@code false} report logging is enabled
     */
    public boolean isQuiet() {
        return quiet;
    }

    @DataBoundSetter
    public void setQuiet(final boolean quiet) {
        this.quiet = quiet;
    }

    /**
     * Returns whether SCM blaming should be disabled.
     *
     * @return {@code true} if SCM blaming should be disabled
     */
    @SuppressWarnings("PMD.BooleanGetMethodName")
    public boolean getBlameDisabled() {
        return isBlameDisabled;
    }

    @DataBoundSetter
    public void setBlameDisabled(final boolean blameDisabled) {
        isBlameDisabled = blameDisabled;
    }

    /**
     * Returns whether SCM blaming should be disabled.
     *
     * @return {@code true} if SCM blaming should be disabled
     */
    public boolean isSkipBlames() {
        return isBlameDisabled;
    }

    @DataBoundSetter
    public void setSkipBlames(final boolean skipBlames) {
        isBlameDisabled = skipBlames;
    }

    /**
     * Not used anymore.
     *
     * @return {@code true} if SCM forensics should be disabled
     * @deprecated Forensics will be automatically skipped if the Forensics recorder is not activated.
     */
    @SuppressWarnings("PMD.BooleanGetMethodName")
    @Deprecated
    public boolean getForensicsDisabled() {
        return isForensicsDisabled;
    }

    /**
     * Not used anymore.
     *
     * @param forensicsDisabled
     *         not used
     *
     * @deprecated Forensics will be automatically skipped if the Forensics recorder is not activated.
     */
    @DataBoundSetter
    @Deprecated
    public void setForensicsDisabled(final boolean forensicsDisabled) {
        isForensicsDisabled = forensicsDisabled;
    }

    /**
     * Returns whether publishing checks should be skipped.
     *
     * @return {@code true} if publishing checks should be skipped, {@code false} otherwise
     */
    public boolean isSkipPublishingChecks() {
        return skipPublishingChecks;
    }

    @DataBoundSetter
    public void setSkipPublishingChecks(final boolean skipPublishingChecks) {
        this.skipPublishingChecks = skipPublishingChecks;
    }

    /**
     * Returns whether all issues should be published using the Checks API. If set to {@code false} only new issues will
     * be published.
     *
     * @return {@code true} if all issues should be published, {@code false} if only new issues should be published
     */
    public boolean isPublishAllIssues() {
        return publishAllIssues;
    }

    @DataBoundSetter
    public void setPublishAllIssues(final boolean publishAllIssues) {
        this.publishAllIssues = publishAllIssues;
    }

    /**
     * Determines whether to fail the step on errors during the step of recording issues.
     *
     * @param failOnError
     *         if {@code true} then the build will be failed on errors, {@code false} then errors are only reported in
     *         the UI
     */
    @DataBoundSetter
    @SuppressWarnings("unused") // Used by Stapler
    public void setFailOnError(final boolean failOnError) {
        this.failOnError = failOnError;
    }

    @SuppressWarnings({"PMD.BooleanGetMethodName", "unused"})
    public boolean getFailOnError() {
        return failOnError;
    }

    /**
     * Returns whether recording should be enabled for failed builds as well.
     *
     * @return {@code true}  if recording should be enabled for failed builds as well, {@code false} if recording is
     *         enabled for successful or unstable builds only
     */
    @SuppressWarnings("PMD.BooleanGetMethodName")
    public boolean getEnabledForFailure() {
        return isEnabledForFailure;
    }

    @DataBoundSetter
    public void setEnabledForFailure(final boolean enabledForFailure) {
        isEnabledForFailure = enabledForFailure;
    }

    /**
     * If {@code true}, then the result of the quality gate is ignored when selecting a reference build. This option is
     * disabled by default so a failing quality gate will be passed from build to build until the original reason for
     * the failure has been resolved.
     *
     * @param ignoreQualityGate
     *         if {@code true} then the result of the quality gate is ignored, otherwise only build with a successful
     *         quality gate are selected
     */
    @DataBoundSetter
    public void setIgnoreQualityGate(final boolean ignoreQualityGate) {
        this.ignoreQualityGate = ignoreQualityGate;
    }

    @SuppressWarnings("PMD.BooleanGetMethodName")
    public boolean getIgnoreQualityGate() {
        return ignoreQualityGate;
    }

    /**
     * If {@code true}, then only successful or unstable reference builds will be considered. This option is enabled by
     * default, since analysis results might be inaccurate if the build failed. If {@code false}, every build that
     * contains a static analysis result is considered, even if the build failed.
     *
     * @param ignoreFailedBuilds
     *         if {@code true} then a stable build is used as reference
     */
    @DataBoundSetter
    public void setIgnoreFailedBuilds(final boolean ignoreFailedBuilds) {
        this.ignoreFailedBuilds = ignoreFailedBuilds;
    }

    @SuppressWarnings("PMD.BooleanGetMethodName")
    public boolean getIgnoreFailedBuilds() {
        return ignoreFailedBuilds;
    }

    /**
     * Sets the reference job to get the results for the issue difference computation.
     *
     * @param referenceJobName
     *         the name of reference job
     */
    @DataBoundSetter
    public void setReferenceJobName(final String referenceJobName) {
        if (NO_REFERENCE_DEFINED.equals(referenceJobName)) {
            this.referenceJobName = StringUtils.EMPTY;
        }
        this.referenceJobName = referenceJobName;
    }

    /**
     * Returns the reference job to get the results for the issue difference computation. If the job is not defined,
     * then {@link #NO_REFERENCE_DEFINED} is returned.
     *
     * @return the name of reference job, or {@link #NO_REFERENCE_DEFINED} if undefined
     */
    public String getReferenceJobName() {
        if (StringUtils.isBlank(referenceJobName)) {
            return NO_REFERENCE_DEFINED;
        }
        return referenceJobName;
    }

    /**
     * Sets the reference build id to get the results for the issue difference computatation.
     *
     * @param referenceBuildId
     *         the build id of the reference job
     */
    public void setReferenceBuildId(final String referenceBuildId) {
        if (NO_REFERENCE_DEFINED.equals(referenceBuildId)) {
            this.referenceBuildId = StringUtils.EMPTY;
        }
        else {
            this.referenceBuildId = referenceBuildId;
        }
    }

    /**
     * Returns the reference build id to get the results for the issue difference computation.  If the build id not
     * defined, then {@link #NO_REFERENCE_DEFINED} is returned.
     *
     * @return the build id of the reference job, or {@link #NO_REFERENCE_DEFINED} if undefined.
     */
    public String getReferenceBuildId() {
        if (StringUtils.isBlank(referenceBuildId)) {
            return NO_REFERENCE_DEFINED;
        }
        return referenceBuildId;
    }

    public int getHealthy() {
        return healthy;
    }

    /**
     * Sets the healthy threshold, i.e. the number of issues when health is reported as 100%.
     *
     * @param healthy
     *         the number of issues when health is reported as 100%
     */
    @DataBoundSetter
    public void setHealthy(final int healthy) {
        this.healthy = healthy;
    }

    public int getUnhealthy() {
        return unhealthy;
    }

    /**
     * Sets the healthy threshold, i.e. the number of issues when health is reported as 0%.
     *
     * @param unhealthy
     *         the number of issues when health is reported as 0%
     */
    @DataBoundSetter
    public void setUnhealthy(final int unhealthy) {
        this.unhealthy = unhealthy;
    }

    @CheckForNull
    public String getMinimumSeverity() {
        return minimumSeverity.getName();
    }

    /**
     * Sets the type of the trend chart that should be shown on the job page.
     *
     * @param trendChartType
     *         the type of the trend chart to use
     */
    @DataBoundSetter
    public void setTrendChartType(final TrendChartType trendChartType) {
        this.trendChartType = trendChartType;
    }

    public TrendChartType getTrendChartType() {
        return trendChartType;
    }

    /**
     * Sets the minimum severity to consider when computing the health report. Issues with a severity less than this
     * value will be ignored.
     *
     * @param minimumSeverity
     *         the severity to consider
     */
    @DataBoundSetter
    public void setMinimumSeverity(final String minimumSeverity) {
        this.minimumSeverity = Severity.valueOf(minimumSeverity, Severity.WARNING_LOW);
    }

    public List<RegexpFilter> getFilters() {
        return new ArrayList<>(filters);
    }

    @DataBoundSetter
    public void setFilters(final List<RegexpFilter> filters) {
        this.filters = new ArrayList<>(filters);
    }

    public void setChecksInfo(@CheckForNull final ChecksInfo checksInfo) {
        this.checksInfo = checksInfo;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public Descriptor getDescriptor() {
        return (Descriptor) super.getDescriptor();
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener)
            throws InterruptedException, IOException {
        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            throw new IOException("No workspace found for " + build);
        }

        perform(build, workspace, listener, new RunResultHandler(build));

        return true;
    }

    /**
     * Executes the build step. Used from {@link RecordIssuesStep} to provide a {@link StageResultHandler} that has
     * Pipeline-specific behavior.
     *
     * @param run
     *         the run of the pipeline or freestyle job
     * @param workspace
     *         workspace of the build
     * @param listener
     *         the logger
     * @param statusHandler
     *         reports the status for the build or for the stage
     *
     * @return the created results
     */
    List<AnalysisResult> perform(final Run<?, ?> run, final FilePath workspace, final TaskListener listener,
            final StageResultHandler statusHandler) throws InterruptedException, IOException {
        Result overallResult = run.getResult();
        if (isEnabledForFailure || overallResult == null || overallResult.isBetterOrEqualTo(Result.UNSTABLE)) {
            return record(run, workspace, listener, statusHandler);
        }
        else {
            LogHandler logHandler = new LogHandler(listener, createLoggerPrefix());
            logHandler.setQuiet(quiet);
            logHandler.log("Skipping execution of recorder since overall result is '%s'", overallResult);
            return Collections.emptyList();
        }
    }

    private String createLoggerPrefix() {
        return analysisTools.stream().map(Tool::getActualName).collect(Collectors.joining());
    }

    private List<AnalysisResult> record(final Run<?, ?> run, final FilePath workspace, final TaskListener listener,
            final StageResultHandler statusHandler) throws IOException, InterruptedException {
        List<AnalysisResult> results = new ArrayList<>();
        if (isAggregatingResults && analysisTools.size() > 1) {
            AnnotatedReport totalIssues = new AnnotatedReport(StringUtils.defaultIfEmpty(id, DEFAULT_ID));
            for (Tool tool : analysisTools) {
                totalIssues.add(scanWithTool(run, workspace, listener, tool), tool.getActualId());
            }
            String toolName = StringUtils.defaultIfEmpty(getName(), Messages.Tool_Default_Name());
            results.add(publishResult(run, listener, toolName, totalIssues, toolName, statusHandler));
        }
        else {
            for (Tool tool : analysisTools) {
                AnnotatedReport report = new AnnotatedReport(tool.getActualId());
                if (isAggregatingResults) {
                    report.logInfo("Ignoring 'aggregatingResults' and ID '%s' since only a single tool is defined.",
                            id);
                }
                report.add(scanWithTool(run, workspace, listener, tool));
                if (StringUtils.isNotBlank(id) || StringUtils.isNotBlank(name)) {
                    report.logInfo("Ignoring name='%s' and id='%s' when publishing non-aggregating reports",
                            name, id);
                }
                results.add(
                        publishResult(run, listener, tool.getActualName(), report, getReportName(tool), statusHandler));
            }
        }
        return results;
    }

    /**
     * Returns the name of the tool. If no name has been set, then an empty string is returned so that the default name
     * will be used.
     *
     * @param tool
     *         the tool
     *
     * @return the name
     */
    private String getReportName(final Tool tool) {
        if (StringUtils.isBlank(tool.getName())) {
            return StringUtils.EMPTY;
        }
        else {
            return tool.getActualName();
        }
    }

    private AnnotatedReport scanWithTool(final Run<?, ?> run, final FilePath workspace, final TaskListener listener,
            final Tool tool) throws IOException, InterruptedException {
        IssuesScanner issuesScanner = new IssuesScanner(tool, getFilters(), getSourceCodeCharset(),
                workspace, getSourceCodePaths(), run, new FilePath(run.getRootDir()), listener,
                scm, isBlameDisabled ? BlameMode.DISABLED : BlameMode.ENABLED, quiet);

        return issuesScanner.scan();
    }

    private Set<String> getSourceCodePaths() {
        return getSourceDirectories().stream().map(SourceCodeDirectory::getPath).collect(Collectors.toSet());
    }

    private Charset getSourceCodeCharset() {
        return getCharset(sourceCodeEncoding);
    }

    private Charset getCharset(final String encoding) {
        return VALIDATION_UTILITIES.getCharset(encoding);
    }

    /**
     * Publishes the results as {@link Action} in the job using an {@link IssuesPublisher}. Afterwards, all affected
     * files are copied to Jenkins' build folder so that they are available to show warnings in the UI.
     *
     * @param run
     *         the run
     * @param listener
     *         the listener
     * @param loggerName
     *         the name of the logger
     * @param annotatedReport
     *         the analysis report to publish
     * @param reportName
     *         the name of the report (might be empty)
     * @param statusHandler
     *         the status handler to use
     *
     * @return the created results
     */
    AnalysisResult publishResult(final Run<?, ?> run, final TaskListener listener, final String loggerName,
            final AnnotatedReport annotatedReport, final String reportName, final StageResultHandler statusHandler) {
        QualityGateEvaluator qualityGate = new QualityGateEvaluator();
        qualityGate.addAll(qualityGates);
        LogHandler logHandler = new LogHandler(listener, loggerName);

        logHandler.setQuiet(quiet);

        var report = annotatedReport.getReport();
        logHandler.logInfoMessages(report.getInfoMessages());
        logHandler.logErrorMessages(report.getErrorMessages());

        IssuesPublisher publisher = new IssuesPublisher(run, annotatedReport,
                new HealthDescriptor(healthy, unhealthy, minimumSeverity), qualityGate,
                reportName, getReferenceJobName(), getReferenceBuildId(), ignoreQualityGate, ignoreFailedBuilds,
                getSourceCodeCharset(), logHandler, statusHandler, failOnError);
        ResultAction action = publisher.attachAction(trendChartType);

        if (!skipPublishingChecks) {
            WarningChecksPublisher checksPublisher = new WarningChecksPublisher(action, listener, checksInfo);
            checksPublisher.publishChecks(
                    isPublishAllIssues() ? AnnotationScope.PUBLISH_ALL_ISSUES : AnnotationScope.PUBLISH_NEW_ISSUES);
        }

        return action.getResult();
    }

    /**
     * Descriptor for this step: defines the context and the UI elements.
     */
    @Extension
    @Symbol("recordIssues")
    @SuppressWarnings("unused") // most methods are used by the corresponding jelly view
    public static class Descriptor extends BuildStepDescriptor<Publisher> {
        private static final JenkinsFacade JENKINS = new JenkinsFacade();

        /** Retain backward compatibility. */
        @Initializer(before = InitMilestone.PLUGINS_STARTED)
        public static void addAliases() {
            Run.XSTREAM2.addCompatibilityAlias("io.jenkins.plugins.analysis.core.views.ResultAction",
                    ResultAction.class);
        }

        private final ModelValidation model = new ModelValidation();

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.ScanAndPublishIssues_DisplayName();
        }

        @Override
        public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
            return true;
        }

        /**
         * Performs on-the-fly validation of the ID.
         *
         * @param project
         *         the project that is configured
         * @param id
         *         the ID of the tool
         *
         * @return the validation result
         */
        @POST
        public FormValidation doCheckId(@AncestorInPath final BuildableItem project,
                @QueryParameter final String id) {
            if (!JENKINS.hasPermission(Item.CONFIGURE, project)) {
                return FormValidation.ok();
            }

            return VALIDATION_UTILITIES.validateId(id);
        }

        /**
         * Returns a model with all available charsets.
         *
         * @param project
         *         the project that is configured
         * @return a model with all available charsets
         */
        @POST
        public ComboBoxModel doFillSourceCodeEncodingItems(@AncestorInPath final BuildableItem project) {
            if (JENKINS.hasPermission(Item.CONFIGURE, project)) {
                return VALIDATION_UTILITIES.getAllCharsets();
            }
            return new ComboBoxModel();
        }

        /**
         * Returns a model with all available severity filters.
         *
         * @return a model with all available severity filters
         */
        @POST
        public ListBoxModel doFillMinimumSeverityItems() {
            if (JENKINS.hasPermission(Jenkins.READ)) {
                return model.getAllSeverityFilters();
            }
            return new ListBoxModel();
        }

        /**
         * Performs on-the-fly validation of the character encoding.
         *
         * @param project
         *         the project that is configured
         * @param reportEncoding
         *         the character encoding
         *
         * @return the validation result
         */
        @POST
        public FormValidation doCheckReportEncoding(@AncestorInPath final BuildableItem project,
                @QueryParameter final String reportEncoding) {
            if (!JENKINS.hasPermission(Item.CONFIGURE, project)) {
                return FormValidation.ok();
            }

            return VALIDATION_UTILITIES.validateCharset(reportEncoding);
        }

        /**
         * Performs on-the-fly validation on the character encoding.
         *
         * @param project
         *         the project that is configured
         * @param sourceCodeEncoding
         *         the character encoding
         *
         * @return the validation result
         */
        @POST
        public FormValidation doCheckSourceCodeEncoding(@AncestorInPath final BuildableItem project,
                @QueryParameter final String sourceCodeEncoding) {
            if (!JENKINS.hasPermission(Item.CONFIGURE, project)) {
                return FormValidation.ok();
            }

            return VALIDATION_UTILITIES.validateCharset(sourceCodeEncoding);
        }

        /**
         * Performs on-the-fly validation of the health report thresholds.
         *
         * @param project
         *         the project that is configured
         * @param healthy
         *         the healthy threshold
         * @param unhealthy
         *         the unhealthy threshold
         *
         * @return the validation result
         */
        @POST
        public FormValidation doCheckHealthy(@AncestorInPath final BuildableItem project,
                @QueryParameter final int healthy, @QueryParameter final int unhealthy) {
            if (!JENKINS.hasPermission(Item.CONFIGURE, project)) {
                return FormValidation.ok();
            }
            return model.validateHealthy(healthy, unhealthy);
        }

        /**
         * Performs on-the-fly validation of the health report thresholds.
         *
         * @param project
         *         the project that is configured
         * @param healthy
         *         the healthy threshold
         * @param unhealthy
         *         the unhealthy threshold
         *
         * @return the validation result
         */
        @POST
        public FormValidation doCheckUnhealthy(@AncestorInPath final BuildableItem project,
                @QueryParameter final int healthy, @QueryParameter final int unhealthy) {
            if (!JENKINS.hasPermission(Item.CONFIGURE, project)) {
                return FormValidation.ok();
            }
            return model.validateUnhealthy(healthy, unhealthy);
        }

        /**
         * Returns a model with all aggregation trend chart positions.
         *
         * @return a model with all aggregation trend chart positions
         */
        @POST
        public ListBoxModel doFillTrendChartTypeItems() {
            if (JENKINS.hasPermission(Jenkins.READ)) {
                return model.getAllTrendChartTypes();
            }
            return new ListBoxModel();
        }
    }
}
