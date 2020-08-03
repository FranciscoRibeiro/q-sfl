package pt.up.fe.qsfl.maven;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import pt.up.fe.qsfl.common.messaging.Server;
import pt.up.fe.qsfl.common.messaging.Service;
import pt.up.fe.qsfl.instrumenter.agent.AgentConfigs;
import pt.up.fe.qsfl.instrumenter.granularity.GranularityFactory.GranularityLevel;
import pt.up.fe.qsfl.maven.service.InstrumentationRecorderService;

@Mojo(name = "init")
public class InitMojo extends AbstractMojo {

	static final String QSFL_ARTIFACT = "pt.up.fe.qsfl:qsfl-instrumenter";

	@Parameter(property = "plugin.artifactMap")
	private Map<String, Artifact> pluginArtifactMap;

	@Parameter(property = "project")
	protected MavenProject project;

	@Parameter(defaultValue = "${project.build.directory}/qsfl/")
	protected File reportDirectory;

	@Parameter(defaultValue = "false")
	protected boolean valueProbes;

	@Parameter(defaultValue = "method")
	protected GranularityLevel granularity;

	@Parameter
	protected String[] ignorePackages;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {

		reportDirectory.mkdirs();

		Artifact artifact = pluginArtifactMap.get(QSFL_ARTIFACT);

		String agentFilename = artifact.getFile().getAbsolutePath();

		try {
			ServerSocket serverSocket = new ServerSocket(0);
			Server s = new Server(serverSocket, new Service.ServiceFactory() {
				@Override
				public Service create(String id) {
					return InstrumentationRecorderService.getService(reportDirectory.getAbsolutePath() + "/");
				}
			});
			s.start();

			AgentConfigs agentConfigs = new AgentConfigs();
			agentConfigs.setPort(s.getPort());
			if (valueProbes) {
				agentConfigs.setInstrumentParameters(true);
			} else {
				agentConfigs.setInstrumentLandmarks(true);
			}
			agentConfigs.setGranularityLevel(granularity);
			agentConfigs.addPrefixToFilter("org.apache.maven.", "junit.", "org.junit.", "org.hamcrest.", "org.mockito.");
			if(ignorePackages != null){
				agentConfigs.addPrefixToFilter(ignorePackages);
			}

			StringBuilder sb = new StringBuilder();
			sb.append(project.getProperties().getProperty("argLine", ""));
			sb.append(" -javaagent:\"");
			sb.append(agentFilename);
			sb.append("\"=");
			sb.append(agentConfigs.serialize());

			System.out.println("command line: " + sb.toString());
			project.getProperties().setProperty("argLine", sb.toString());

		} catch (IOException e) {
			e.printStackTrace();
		}


	}

}
