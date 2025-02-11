/*
 * SonarQube Scanner for Jenkins
 * Copyright (C) 2007-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package hudson.plugins.sonar;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.sonar.client.HttpClient;
import hudson.plugins.sonar.client.OkHttpClientSingleton;
import hudson.plugins.sonar.utils.BuilderUtils;
import hudson.plugins.sonar.utils.JenkinsRouter;
import hudson.plugins.sonar.utils.SonarUtils;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import static hudson.plugins.sonar.utils.SonarUtils.PROPERTY_SONAR_LOGIN;
import static hudson.plugins.sonar.utils.SonarUtils.PROPERTY_SONAR_TOKEN;

public class MsBuildSQRunnerBegin extends AbstractMsBuildSQRunner {

  /**
   * @deprecated since 2.4.3
   */
  @Deprecated
  private transient String msBuildRunnerInstallationName;
  /**
   * @since 2.4.3
   */
  private String msBuildScannerInstallationName;
  private String sonarInstallationName;
  private String projectKey;
  private String projectName;
  private String projectVersion;
  private String additionalArguments;

  @DataBoundConstructor
  public MsBuildSQRunnerBegin() {
    // no mandatory field by default
  }

  /**
   * @deprecated since 2.5. Moved to use {@link DataBoundSetter}
   */
  @Deprecated
  public MsBuildSQRunnerBegin(String msBuildScannerInstallationName, String sonarInstallationName, String projectKey, String projectName, String projectVersion,
    String additionalArguments) {
    this.msBuildScannerInstallationName = msBuildScannerInstallationName;
    this.sonarInstallationName = sonarInstallationName;
    this.projectKey = projectKey;
    this.projectName = projectName;
    this.projectVersion = projectVersion;
    this.additionalArguments = additionalArguments;
  }

  @Override
  public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
    ArgumentListBuilder args = new ArgumentListBuilder();

    EnvVars env = BuilderUtils.getEnvAndBuildVars(run, listener);
    SonarInstallation sonarInstallation = getSonarInstallation(getSonarInstallationName(), listener);
    MsBuildSQRunnerInstallation msBuildScanner = getDescriptor().getMsBuildScannerInstallation(msBuildScannerInstallationName);
    run.addAction(new SonarQubeScannerMsBuildParams(msBuildScannerInstallationName, getSonarInstallationName()));

    String scannerPath = getScannerPath(msBuildScanner, env, launcher, listener, workspace);
    if (isDotNetCoreTool(scannerPath)) {
      addDotNetCommand(args);
    }
    args.add(scannerPath);
    Map<String, String> props = getSonarProps(sonarInstallation, run);
    addArgsTo(args, sonarInstallation, env, props);

    int result = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(BuilderUtils.getModuleRoot(run, workspace)).join();

    if (result != 0) {
      throw new AbortException(Messages.MSBuildScanner_ExecFailed(result));
    }
  }

  private Map<String, String> getSonarProps(SonarInstallation inst, Run<?, ?> run) {
    Map<String, String> map = new LinkedHashMap<>();

    map.put("sonar.host.url", inst.getServerUrl());
    String token = inst.getServerAuthenticationToken(run);
    if (!StringUtils.isBlank(token)) {
      map.put(SonarUtils.getTokenProperty(inst, getClient()), token);
    }

    return map;
  }

  private void addArgsTo(ArgumentListBuilder args, SonarInstallation sonarInst, EnvVars env, Map<String, String> props) {
    args.add("begin");

    args.add("/k:" + env.expand(projectKey) + "");
    args.add("/n:" + env.expand(projectName) + "");
    args.add("/v:" + env.expand(projectVersion) + "");

    // expand macros using itself
    EnvVars.resolve(props);

    for (Map.Entry<String, String> e : props.entrySet()) {
      if (!StringUtils.isEmpty(e.getValue())) {
        // expand macros using environment variables and hide token
        boolean hide = e.getKey().contains(PROPERTY_SONAR_LOGIN) || e.getKey().contains(PROPERTY_SONAR_TOKEN);
        args.addKeyValuePair("/d:", e.getKey(), env.expand(e.getValue()), hide);
      }
    }

    args.add(sonarInst.getAdditionalAnalysisPropertiesWindows());
    args.addTokenized(sonarInst.getAdditionalProperties());
    args.addTokenized(env.expand(additionalArguments));
  }

  @Override
  public DescriptorImpl getDescriptor() {
    return (DescriptorImpl) super.getDescriptor();
  }

  public String getProjectKey() {
    return Util.fixNull(projectKey);
  }

  @DataBoundSetter
  public void setProjectKey(String projectKey) {
    this.projectKey = projectKey;
  }

  public String getProjectVersion() {
    return Util.fixNull(projectVersion);
  }

  @DataBoundSetter
  public void setProjectVersion(String projectVersion) {
    this.projectVersion = projectVersion;
  }

  public String getProjectName() {
    return Util.fixNull(projectName);
  }

  @DataBoundSetter
  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public String getSonarInstallationName() {
    return Util.fixNull(sonarInstallationName);
  }

  @DataBoundSetter
  public void setSonarInstallationName(String sonarInstallationName) {
    this.sonarInstallationName = sonarInstallationName;
  }

  public String getMsBuildScannerInstallationName() {
    return Util.fixNull(msBuildScannerInstallationName);
  }

  @DataBoundSetter
  public void setMsBuildScannerInstallationName(String msBuildScannerInstallationName) {
    this.msBuildScannerInstallationName = msBuildScannerInstallationName;
  }

  public String getAdditionalArguments() {
    return Util.fixNull(additionalArguments);
  }

  @DataBoundSetter
  public void setAdditionalArguments(String additionalArguments) {
    this.additionalArguments = additionalArguments;
  }

  protected Object readResolve() {
    // Migrate old field to new field
    if (msBuildRunnerInstallationName != null) {
      msBuildScannerInstallationName = Util.fixNull(msBuildRunnerInstallationName);
    }
    return this;
  }

  @Extension
  public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
    // Used in jelly configuration for conditional display of the UI
    public static final boolean BEFORE_V2 = JenkinsRouter.BEFORE_V2;

    public String getGlobalToolConfigUrl() {
      return JenkinsRouter.getGlobalToolConfigUrl();
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
      return true;
    }

    @Override
    public String getHelpFile() {
      return "/plugin/sonar/help-ms-build-sq-scanner-begin.html";
    }

    @Override
    public String getDisplayName() {
      return Messages.MsBuildScannerBegin_DisplayName();
    }

    public FormValidation doCheckProjectKey(@QueryParameter String value) {
      return checkNotEmpty(value);
    }

    private static FormValidation checkNotEmpty(String value) {
      if (!StringUtils.isEmpty(value)) {
        return FormValidation.ok();
      }
      return FormValidation.error(Messages.SonarGlobalConfiguration_MandatoryProperty());
    }

    @Nullable
    public MsBuildSQRunnerInstallation getMsBuildScannerInstallation(@Nullable String name) {
      MsBuildSQRunnerInstallation[] msInst = getMsBuildScannerInstallations();

      if (StringUtils.isEmpty(name) && msInst.length > 0) {
        return msInst[0];
      }

      for (MsBuildSQRunnerInstallation inst : msInst) {
        if (StringUtils.equals(name, inst.getName())) {
          return inst;
        }
      }

      return null;
    }

    public MsBuildSQRunnerInstallation[] getMsBuildScannerInstallations() {
      return Jenkins.getInstance().getDescriptorByType(MsBuildSQRunnerInstallation.DescriptorImpl.class).getInstallations();
    }

    public SonarInstallation[] getSonarInstallations() {
      return SonarInstallation.all();
    }
  }

}
